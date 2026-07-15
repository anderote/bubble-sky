// grok/foreman.js — the COLONY FOREMAN brain (design block #18b).
//
// A standalone LLM agent that strategically DIRECTS the Tower-Defense colonists
// through the mod-side bridge (interface #18a, already deployed + live). It is
// the mirror of the Warlord: same flank intel, opposite intent. Where the
// Warlord hurls its horde at the players' weakest compass flank, the Foreman
// reads that SAME weak flank and sends builders to FORTIFY it — raising cobble
// walls a few blocks out from the Idol — while the rest of the colony keeps the
// economy running (mine/chop/forage/haul).
//
// ADDITIVE + GRACEFUL: the colony already has a rule-based work brain. An order
// is applied only if valid; a "build" order needs a target. If the Foreman is
// offline or errors, the colony plays exactly as today (rule-based). Every LLM +
// bridge call is wrapped so the loop never crashes.
//
// Reuses lib/bridge (HTTP) + lib/llm (Anthropic). No new HTTP/LLM code.
//
//   node foreman.js            # main loop: poll colony+battlefield, direct each wave
//   node foreman.js --once     # run a single direction cycle then exit
//   node foreman.js --dry      # PRINT the orders instead of POSTing them (safe test)
//   node foreman.js --once --dry
//
// Env (loaded from grok/.env like warlord.js):
//   ANTHROPIC_API_KEY       required for the LLM call
//   BUBBLESKY_BRIDGE_URL    bridge base URL (default http://127.0.0.1:25580)
//   BUBBLESKY_BRIDGE_TOKEN  X-Bridge-Token (else read from server config below)
//   FOREMAN_MODEL           Anthropic model (default claude-opus-4-8)
//   FOREMAN_POLL_MS         colony poll interval (default 5000)

const fs = require('fs')
const path = require('path')

const DIR = __dirname

// ---- .env loading (mirrors warlord.js) ----
try {
  for (const line of fs.readFileSync(path.join(DIR, '.env'), 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.+?)\s*$/)
    if (m && !process.env[m[1]]) process.env[m[1]] = m[2]
  }
} catch { /* no .env — rely on the ambient environment */ }

// Fall back to the server's bridge config for the token if not already set.
if (!process.env.BUBBLESKY_BRIDGE_TOKEN) {
  const cfg = path.join(DIR, '..', 'server', 'config', 'bubblesky-bridge.json')
  try { process.env.BUBBLESKY_BRIDGE_TOKEN = JSON.parse(fs.readFileSync(cfg, 'utf8')).token || '' } catch { /* leave unset */ }
}

const makeBridge = require('./lib/bridge')
const { createLLM } = require('./lib/llm')

const MODEL = process.env.FOREMAN_MODEL || 'claude-opus-4-8'
const POLL_MS = Math.max(1000, parseInt(process.env.FOREMAN_POLL_MS, 10) || 5000)
const ARGV = new Set(process.argv.slice(2))
const ONCE = ARGV.has('--once')
const DRY = ARGV.has('--dry')

// The 7 job strings the mod accepts (map 1:1 to ColonistEntity.Job).
const JOBS = new Set(['mine', 'chop', 'hunt', 'forage', 'haul', 'idle', 'build'])

function log(...a) { console.log(`[${new Date().toISOString()}] [foreman]`, ...a) }
const sleep = ms => new Promise(r => setTimeout(r, ms))

const bridge = makeBridge({ timeoutMs: 8000 })
const llm = createLLM({ provider: 'anthropic' })

// ---- weak-flank analysis (copied from warlord.js — SAME intel, opposite intent) ----
// Classify each tower into a compass side relative to the Idol and weight it by
// tier, so the Foreman can fortify the softest wall the Warlord is about to hit.
// Minecraft axes: -Z = north, +Z = south, +X = east, -X = west.
function analyzeFlanks(idol, towers) {
  const sides = { n: { count: 0, strength: 0 }, e: { count: 0, strength: 0 }, s: { count: 0, strength: 0 }, w: { count: 0, strength: 0 } }
  if (idol) {
    for (const t of (towers || [])) {
      const dx = t.x - idol.x, dz = t.z - idol.z
      // Dominant axis decides the side (diagonal towers count for the bigger delta).
      const side = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? 'e' : 'w') : (dz >= 0 ? 's' : 'n')
      sides[side].count++
      sides[side].strength += Math.max(1, (t.tier | 0) || 1)
    }
  }
  // Weakest flank = lowest defensive strength (ties broken by fewest towers).
  const order = ['n', 'e', 's', 'w'].sort((a, b) =>
    (sides[a].strength - sides[b].strength) || (sides[a].count - sides[b].count))
  const weakest = order[0]
  const NAME = { n: 'north', e: 'east', s: 'south', w: 'west' }
  return { sides, weakest, weakestName: NAME[weakest],
    summary: ['n', 'e', 's', 'w'].map(k => `${NAME[k]}: ${sides[k].count} towers / strength ${sides[k].strength}`).join(', ') }
}

// ---- wall geometry --------------------------------------------------------
// Given the Idol and the weakest compass side, compute a wall SEGMENT to raise a
// few blocks out from the Idol on that side, spanning ACROSS the approach
// (perpendicular to the flank direction). Returns the origin corner + dir/length
// /height in the exact shape /td/colony/assign wants. The mod clamps length ≤ 16
// and height ≤ 6.
function wallSegment(idol, weakest, length, height) {
  if (!idol) return null
  const OFFSET = 6            // blocks out from the Idol
  const len = Math.max(1, Math.min(16, length | 0 || 10))
  const h = Math.max(1, Math.min(6, height | 0 || 4))
  const x0 = Math.round(idol.x), y0 = Math.round(idol.y), z0 = Math.round(idol.z)
  const half = Math.floor(len / 2)
  // dir = the axis the wall extends along (perpendicular to the flank normal).
  switch (weakest) {
    case 'n': // north wall (-Z): sits north of Idol, runs east-west
      return { target: { x: x0 - half, y: y0, z: z0 - OFFSET }, dir: 'east', length: len, height: h }
    case 's': // south wall (+Z): sits south of Idol, runs east-west
      return { target: { x: x0 - half, y: y0, z: z0 + OFFSET }, dir: 'east', length: len, height: h }
    case 'e': // east wall (+X): sits east of Idol, runs north-south
      return { target: { x: x0 + OFFSET, y: y0, z: z0 - half }, dir: 'south', length: len, height: h }
    case 'w': // west wall (-X): sits west of Idol, runs north-south
    default:
      return { target: { x: x0 - OFFSET, y: y0, z: z0 - half }, dir: 'south', length: len, height: h }
  }
}

// ---- the persona ----------------------------------------------------------
const FOREMAN_SYSTEM = `You are the COLONY FOREMAN — a gruff, practical, no-nonsense boss who runs a frontier colony under siege. You direct a handful of colonists (miners, choppers, foragers, haulers, builders) who dig, gather, and — when the enemy comes — throw up walls to protect the glowing Idol at the heart of the colony. You talk like a weathered work-crew chief: blunt, dry, a little impatient, but you look after your people.

Your job each turn: study your colonist roster and the battlefield, then hand out orders by calling the submit_colony_orders tool. Do not chat outside the tool — put your one spoken line in the tool's "say" field.

HOW TO DIRECT THE CREW:
1. FORTIFY THE WEAK FLANK — you are told the WEAKEST compass side of the Idol (the side the enemy Warlord will hit) and a ready-made wall SEGMENT (target/dir/length/height) sitting a few blocks out on that side, spanning across the approach. During intermission or when a wave is imminent, put 1–3 of your crew on job "build" with that wall segment as their "target" (reuse the provided target/dir/length/height; you may nudge each builder a few blocks along the wall so they don't stack up). If the LAST wave leaked a lot of enemies to the Idol, commit MORE builders and consider repairOnly=false to raise the wall higher; if the wall likely stands, one or two builders on repairOnly=true is enough.
2. KEEP THE ECONOMY RUNNING — do NOT put everyone on walls. Leave the rest of the crew gathering: spread them across mine, chop, forage, and haul so resources keep flowing. A colonist whose inventory is full (high invCount) should HAUL to deposit before gathering more.
3. USE ONLY REAL COLONISTS — assign orders ONLY to colonists that appear in the roster, by their exact name (or uuid). Every "job" must be one of: mine, chop, hunt, forage, haul, idle, build. A "build" order MUST include a target {x,y,z}.
4. SAY — ONE short, in-character foreman line (blunt, specific: name the flank you're walling or the crew you're moving, e.g. "West wall's bare — Grum, Bex, on the cobble, now."). Keep it to a sentence.

ADAPT to the telemetry when given:
- last wave leaked high → the wall failed or wasn't there; MORE builders, raise it higher.
- next wave imminent / intermission → this is the window to build; get walls up before the horde.
- quiet / defenses holding → lean the crew back into gathering, keep one on wall repair.

You will be given the colonist roster, the battlefield, a flank analysis, and a suggested wall segment. Return exactly one submit_colony_orders tool call.`

const ORDERS_TOOL = {
  name: 'submit_colony_orders',
  description: 'Hand out this cycle\'s per-colonist orders (fortify the weak flank + keep the economy running) plus one in-character foreman line.',
  schema: {
    type: 'object',
    properties: {
      orders: {
        type: 'array',
        description: 'Per-colonist orders. Assign ONLY real colonists from the roster. "build" orders MUST carry a target {x,y,z}.',
        items: {
          type: 'object',
          properties: {
            colonist: { type: 'string', description: 'Exact colonist name or uuid from the roster.' },
            job: { type: 'string', enum: ['mine', 'chop', 'hunt', 'forage', 'haul', 'idle', 'build'], description: 'One of the 7 jobs.' },
            target: {
              type: 'object',
              description: 'REQUIRED for job "build": the origin corner {x,y,z} of the wall segment.',
              properties: { x: { type: 'number' }, y: { type: 'number' }, z: { type: 'number' } }
            },
            length: { type: 'integer', description: 'Wall length in blocks (build only; clamped ≤16).' },
            dir: { type: 'string', description: 'Direction the wall extends: north/south/east/west (build only).' },
            height: { type: 'integer', description: 'Wall height in blocks (build only; clamped ≤6).' },
            repairOnly: { type: 'boolean', description: 'If true, only fill missing/air cells of the segment (build only).' }
          },
          required: ['colonist', 'job']
        }
      },
      say: { type: 'string', description: 'ONE short, in-character foreman line.' }
    },
    required: ['orders', 'say']
  }
}

// ---- direction generation (one LLM call) ----------------------------------
async function generateOrders(colony, bf) {
  const flanks = analyzeFlanks(bf.idol, bf.towers)
  const wall = wallSegment(bf.idol, flanks.weakest, 10, 4)
  const roster = (colony.colonists || []).map(c => ({
    name: c.name, uuid: c.uuid, job: c.job, x: c.x, y: c.y, z: c.z,
    invCount: c.invCount, priorities: c.priorities, target: c.target || null
  }))
  const lastWave = bf.lastWave || null
  const user = {
    roster,
    battlefield: {
      wave: bf.wave, nextWave: bf.nextWave, waveInProgress: bf.waveInProgress,
      intermission: bf.intermission, gameOver: bf.gameOver,
      idol: bf.idol || null, budget: bf.budget,
      lastWave
    },
    flankAnalysis: {
      idol: bf.idol || null,
      perSide: flanks.sides,
      weakestFlank: flanks.weakestName,
      summary: flanks.summary,
      note: 'The Warlord will hit the weakest flank. Fortify THAT side.'
    },
    suggestedWall: wall
      ? { ...wall, block: 'cobblestone', side: flanks.weakestName,
          note: 'Ready-made wall segment on the weak flank. Use as build targets; nudge builders along it so they spread out.' }
      : null,
    reminder: 'Send 1–3 builders to the suggested wall (job "build" WITH target); keep the rest gathering (mine/chop/forage/haul). Assign only real roster colonists. Call submit_colony_orders.'
  }
  const { toolCalls } = await llm.chat({
    system: FOREMAN_SYSTEM,
    messages: [{ role: 'user', content: JSON.stringify(user) }],
    tools: [ORDERS_TOOL], toolChoice: { name: 'submit_colony_orders' },
    maxTokens: 1500, model: MODEL
  })
  const tc = toolCalls && toolCalls[0]
  if (!tc || !tc.args) throw new Error('Foreman returned no orders')
  return { orders: normalizeOrders(tc.args, colony, wall), flanks, wall }
}

// Coerce + validate the LLM output into the wire shape /td/colony/assign expects.
// Only real colonists, only the 7 jobs, and every build order carries a target
// (falling back to the computed weak-flank wall if the model omitted one).
function normalizeOrders(args, colony, wall) {
  // name/uuid → canonical name, for membership checks + de-dup.
  const byKey = new Map()
  for (const c of (colony.colonists || [])) {
    if (c.name) byKey.set(String(c.name).toLowerCase(), c)
    if (c.uuid) byKey.set(String(c.uuid).toLowerCase(), c)
  }
  const DIRS = new Set(['north', 'south', 'east', 'west'])
  const seen = new Set()
  const orders = []
  for (const o of (args.orders || [])) {
    const key = String(o && o.colonist || '').toLowerCase()
    const c = byKey.get(key)
    if (!c) continue                              // not a real colonist — drop
    if (seen.has(c.name)) continue                // one order per colonist
    const job = String(o.job || '').toLowerCase()
    if (!JOBS.has(job)) continue                  // unknown job — drop
    const order = { colonist: c.name, job }
    if (job === 'build') {
      // A build order MUST carry a target; fall back to the computed weak-flank wall.
      let t = o.target
      const hasT = t && t.x != null && t.y != null && t.z != null
      if (!hasT && wall) t = wall.target
      if (!t || t.x == null || t.y == null || t.z == null) continue  // no usable target — drop
      order.target = { x: Math.round(+t.x), y: Math.round(+t.y), z: Math.round(+t.z) }
      const dir = String(o.dir || (wall && wall.dir) || 'east').toLowerCase()
      order.dir = DIRS.has(dir) ? dir : (wall && wall.dir) || 'east'
      order.length = Math.max(1, Math.min(16, (o.length | 0) || (wall && wall.length) || 10))
      order.height = Math.max(1, Math.min(6, (o.height | 0) || (wall && wall.height) || 4))
      order.block = 'cobblestone'
      if (o.repairOnly === true) order.repairOnly = true
    }
    seen.add(c.name)
    orders.push(order)
  }
  return { orders, say: String(args.say || '').slice(0, 300) }
}

// Compact one-line summary of an order for logging.
function summarizeOrder(o) {
  if (o.job === 'build') {
    const t = o.target
    return `${o.colonist} → build wall @(${t.x},${t.y},${t.z}) ${o.dir} len${o.length} h${o.height}${o.repairOnly ? ' repair' : ''}`
  }
  return `${o.colonist} → ${o.job}`
}

// ---- one direction cycle: read → direct → apply ----------------------------
async function directCycle(colony, bf) {
  // Allow standalone (--once) callers to omit the snapshots; fetch them here.
  if (!colony) {
    try { colony = await bridge.request('GET', '/td/colony') }
    catch (e) { log('colony fetch failed:', e.message); return null }
  }
  if (!bf) {
    try { bf = await bridge.request('GET', '/td/battlefield') }
    catch (e) { log('battlefield fetch failed:', e.message); return null }
  }
  if (!colony || colony.ok === false) { log('colony unavailable'); return null }

  const crew = colony.colonists || []
  if (crew.length === 0) { log('no colonists recruited — nothing to direct, idling'); return null }
  if (bf && bf.gameOver) { log('game over — standing the crew down'); return null }

  const wave = (bf && (bf.nextWave != null ? bf.nextWave : bf.wave))
  log(`directing ${crew.length} colonist(s) for wave ${wave} ` +
    `(current wave ${bf ? bf.wave : '?'}, ${bf && bf.towers ? bf.towers.length : 0} towers, ` +
    `idol hp ${bf && bf.idol ? bf.idol.hp + '/' + bf.idol.max : '?'})`)
  if (bf && bf.lastWave) {
    const lw = bf.lastWave
    log(`last wave #${lw.number}: spawned ${lw.spawned}, leaked ${lw.leaked}, killedByTowers ${lw.killedByTowers}, killedByPlayers ${lw.killedByPlayers}`)
  }

  let gen
  try {
    gen = await generateOrders(colony, bf)
  } catch (e) { log('LLM direction failed (colony falls back to its rule-based brain):', e.message); return null }

  const { orders, flanks, wall } = gen
  log(`weakest flank: ${flanks.weakestName} (${flanks.summary})`)
  if (wall) log(`fortify wall: @(${wall.target.x},${wall.target.y},${wall.target.z}) ${wall.dir} len${wall.length} h${wall.height}`)
  if (!orders.orders.length) { log('no valid orders produced — leaving the crew to its rule-based brain'); return null }
  log(`orders (${orders.orders.length}): ${orders.orders.map(summarizeOrder).join(' | ')}`)
  log(`say: "${orders.say}"`)

  if (DRY) {
    log('--dry: NOT posting. Would POST /td/colony/assign for each + /say:')
    console.log(JSON.stringify(orders, null, 2))
    return { wave, orders }
  }

  // Apply each order, then broadcast the foreman line.
  let applied = 0
  for (const o of orders.orders) {
    try { await bridge.request('POST', '/td/colony/assign', o); applied++ }
    catch (e) { log(`assign failed for ${o.colonist}:`, e.message) }
  }
  log(`applied ${applied}/${orders.orders.length} order(s)`)
  if (orders.say) {
    try { await bridge.request('POST', '/say', { name: '🔨 Foreman', message: orders.say }) }
    catch (e) { log('foreman line broadcast failed:', e.message) }
  }
  return { wave, orders, applied }
}

// ---- roster signature (re-direct when the crew changes materially) ----------
function rosterSig(colony) {
  return (colony && colony.colonists || [])
    .map(c => `${c.name || c.uuid}`).sort().join(',')
}

// ---- main loop -------------------------------------------------------------
async function mainLoop() {
  log(`Foreman on shift. model=${MODEL} bridge=${bridge.baseUrl} poll=${POLL_MS}ms`)
  let lastDirectedWave = -1
  let lastRoster = ''
  for (;;) {
    let colony = null, bf = null
    try { colony = await bridge.request('GET', '/td/colony') } catch (e) { log('colony poll failed:', e.message) }
    try { bf = await bridge.request('GET', '/td/battlefield') } catch (e) { log('battlefield poll failed:', e.message) }

    if (colony && colony.ok !== false) {
      const crew = colony.colonists || []
      const wave = bf ? (bf.nextWave != null ? bf.nextWave : bf.wave) : null
      const sig = rosterSig(colony)
      const gameOver = bf && bf.gameOver
      // Direct at most once per wave, but also re-direct if the crew changed materially.
      const waveChanged = typeof wave === 'number' && wave !== lastDirectedWave
      const rosterChanged = sig !== lastRoster
      if (crew.length === 0) {
        if (lastRoster !== '') log('no colonists recruited — idling')
        lastRoster = sig
      } else if (!gameOver && (waveChanged || rosterChanged)) {
        try {
          await directCycle(colony, bf)
        } catch (e) { log('direction cycle error:', e.message) }
        // Mark handled regardless of accept/skip so we don't hammer the LLM.
        if (typeof wave === 'number') lastDirectedWave = wave
        lastRoster = sig
      }
    }
    await sleep(POLL_MS)
  }
}

// ---- entrypoint ------------------------------------------------------------
;(async () => {
  if (!process.env.ANTHROPIC_API_KEY) log('WARNING: ANTHROPIC_API_KEY not set — LLM calls will fail.')
  if (ONCE) {
    log(`single cycle (${DRY ? 'DRY — will not post' : 'live'})`)
    try { await directCycle() } catch (e) { log('cycle failed:', e.message) }
    return
  }
  try { await mainLoop() } catch (e) { log('fatal:', e.message); process.exit(1) }
})()
