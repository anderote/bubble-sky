// grok/warlord.js — the ENEMY AI WARLORD brain (design block #17b).
//
// A standalone LLM agent that directs the Tower-Defense mod's waves through the
// mod-side bridge (interface #17a, already deployed + live). It is a cunning
// necromancer-general besieging the players' Idol: each upcoming wave, it reads
// the live battlefield, PROBES THE WEAKEST FLANK from the tower distribution,
// composes an enemy army within the mod's threat BUDGET, picks a tactic, and
// snarls one in-character taunt — then submits the plan over the bridge.
//
// ADDITIVE + GRACEFUL: the mod keeps its procedural wave scaling. A plan is used
// only if present + valid; the mod validates + CLAMPS to the budget (over-budget
// plans get scaled down — expected). If the Warlord is offline or errors, the
// game plays exactly as today. Every LLM + bridge call is wrapped so the loop
// never crashes.
//
// Reuses lib/bridge (HTTP) + lib/llm (Anthropic). No new HTTP/LLM code.
//
//   node warlord.js            # main loop: poll battlefield, plan each new wave
//   node warlord.js --once     # run a single plan cycle then exit
//   node warlord.js --dry      # PRINT the plan instead of POSTing it (safe test)
//   node warlord.js --once --dry
//
// Env (loaded from grok/.env like assistant.js):
//   ANTHROPIC_API_KEY       required for the LLM call
//   BUBBLESKY_BRIDGE_URL    bridge base URL (default http://127.0.0.1:25580)
//   BUBBLESKY_BRIDGE_TOKEN  X-Bridge-Token (else read from server config below)
//   WARLORD_MODEL           Anthropic model (default claude-opus-4-8)
//   WARLORD_POLL_MS         battlefield poll interval (default 4000)

const fs = require('fs')
const path = require('path')

const DIR = __dirname

// ---- .env loading (mirrors assistant.js) ----
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

const MODEL = process.env.WARLORD_MODEL || 'claude-opus-4-8'
const POLL_MS = Math.max(1000, parseInt(process.env.WARLORD_POLL_MS, 10) || 4000)
const ARGV = new Set(process.argv.slice(2))
const ONCE = ARGV.has('--once')
const DRY = ARGV.has('--dry')

function log(...a) { console.log(`[${new Date().toISOString()}] [warlord]`, ...a) }
const sleep = ms => new Promise(r => setTimeout(r, ms))

const bridge = makeBridge({ timeoutMs: 8000 })
const llm = createLLM({ provider: 'anthropic' })

// ---- weak-flank analysis ---------------------------------------------------
// Classify each tower into a compass side relative to the Idol and weight it by
// tier, so the Warlord can throw its army at the softest wall. Minecraft axes:
// -Z = north, +Z = south, +X = east, -X = west.
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

// ---- the persona -----------------------------------------------------------
const WARLORD_SYSTEM = `You are the WARLORD — a cunning, ancient necromancer-general laying siege to the mortals' glowing Idol in a Tower-Defense battle. You command endless legions of the dead and the desperate. You are cold, patient, and cruel; you probe for weakness and press it without mercy. You speak in a dark, taunting, archaic voice.

Your job each turn: study the battlefield the mortals have built and decide the NEXT wave of your assault, then submit it by calling the submit_wave_plan tool. Do not chat — only call the tool.

RULES OF THE SIEGE:
1. COMPOSITION — choose how many of each available enemy type to send, drawing ONLY from the enemyTypes you are given (by their exact id). Every enemy costs "threatCost" units; your whole army must fit within the wave's threat "budget". Aim to spend most of the budget but not blow past it — the mortals' wards will CLAMP an over-budget host, scaling your legion down, so a bloated plan only weakens you. Mix fodder (goblin, footman) with heavier threats (knight, man_at_arms, undead, barbarian) and specialists (archer at range, sapper vs walls) to suit your tactic.
2. PROBE THE WEAKEST FLANK — you are told the tower count + strength on each compass side (north/east/south/west) of the Idol. Hurl your army at the SOFTEST side by setting spawnEmphasis.weights (n,e,s,w) heavy toward that flank (e.g. the weak side ~0.6–0.8, the rest sharing the remainder). Do not scatter evenly — concentrate.
3. TACTIC — a short battle-doctrine string (e.g. "swarm the bare eastern gap", "sapper vanguard to breach, undead behind", "feint north, mass south").
4. TAUNT — ONE line of in-character menace, dark and specific: name the real weakness or the mortals' fate (e.g. "Your eastern wall stands naked — my dead shall walk through it."). Keep it to a sentence or two.

ADAPT to the last wave's telemetry when given:
- killedByTowers high → their towers shredded your last host; change the flank or lean on tougher/faster units and sappers to close distance.
- leaked high (many reached the Idol) → you are winning; PRESS the same wound harder, add heavies to finish the Idol.
- the defenders wiped you with few losses → VARY the composition; do not repeat a failed army.

You will be given the full battlefield JSON plus a flank analysis. Return exactly one submit_wave_plan tool call.`

const PLAN_TOOL = {
  name: 'submit_wave_plan',
  description: 'Submit the next wave of the assault: enemy composition, where to concentrate the spawn, the tactic, and one in-character taunt.',
  schema: {
    type: 'object',
    properties: {
      composition: {
        type: 'object',
        description: 'Map of enemy id → integer count, drawn ONLY from the given enemyTypes ids. Must fit within the wave budget (sum of count*threatCost <= budget).',
        additionalProperties: { type: 'integer', minimum: 0 }
      },
      spawnEmphasis: {
        type: 'object',
        description: 'Where the horde pours in. Prefer weights toward the weakest compass flank.',
        properties: {
          weights: {
            type: 'object',
            description: 'Relative spawn weight per compass side (they need not sum to 1).',
            properties: { n: { type: 'number' }, e: { type: 'number' }, s: { type: 'number' }, w: { type: 'number' } }
          },
          focus: {
            type: 'object',
            description: 'Optional single point {x,z} to concentrate the assault instead of/alongside weights.',
            properties: { x: { type: 'number' }, z: { type: 'number' } }
          }
        }
      },
      tactic: { type: 'string', description: 'Short battle-doctrine string.' },
      taunt: { type: 'string', description: 'ONE dark, in-character taunt naming the real weakness.' }
    },
    required: ['composition', 'spawnEmphasis', 'tactic', 'taunt']
  }
}

// ---- plan generation (one LLM call) ----------------------------------------
async function generatePlan(bf) {
  const flanks = analyzeFlanks(bf.idol, bf.towers)
  const user = {
    battlefield: bf,
    flankAnalysis: {
      idol: bf.idol || null,
      perSide: flanks.sides,
      weakestFlank: flanks.weakestName,
      summary: flanks.summary,
      note: 'Concentrate spawnEmphasis.weights on the weakest flank. Fit composition within budget; the mod clamps over-budget hosts.'
    },
    planningWave: bf.nextWave,
    reminder: 'Choose composition within the budget, emphasize the weakest flank, pick a tactic, write one taunt. Call submit_wave_plan.'
  }
  const { toolCalls } = await llm.chat({
    system: WARLORD_SYSTEM,
    messages: [{ role: 'user', content: JSON.stringify(user) }],
    tools: [PLAN_TOOL], toolChoice: { name: 'submit_wave_plan' },
    maxTokens: 1500, model: MODEL
  })
  const tc = toolCalls && toolCalls[0]
  if (!tc || !tc.args) throw new Error('Warlord returned no wave plan')
  return { plan: normalizePlan(tc.args, bf), flanks }
}

// Coerce the LLM output into the wire shape the bridge expects.
function normalizePlan(args, bf) {
  const validIds = new Set((bf.enemyTypes || []).map(e => e.id))
  const composition = {}
  for (const [id, n] of Object.entries(args.composition || {})) {
    const c = Math.max(0, Math.round(+n) || 0)
    if (c > 0 && validIds.has(id)) composition[id] = c
  }
  const se = args.spawnEmphasis || {}
  const spawnEmphasis = {}
  if (se.weights && typeof se.weights === 'object') {
    const w = {}
    for (const k of ['n', 'e', 's', 'w']) if (se.weights[k] != null && !isNaN(+se.weights[k])) w[k] = +se.weights[k]
    if (Object.keys(w).length) spawnEmphasis.weights = w
  }
  if (se.focus && se.focus.x != null && se.focus.z != null) spawnEmphasis.focus = { x: +se.focus.x, z: +se.focus.z }
  if (!spawnEmphasis.weights && !spawnEmphasis.focus) spawnEmphasis.weights = { n: 0.25, e: 0.25, s: 0.25, w: 0.25 }
  return {
    composition,
    spawnEmphasis,
    tactic: String(args.tactic || '').slice(0, 200),
    taunt: String(args.taunt || '').slice(0, 300)
  }
}

// Compact one-line summary of a (possibly clamped) plan for logging.
function summarizePlan(p, bf) {
  const comp = Object.entries(p.composition || {}).map(([k, v]) => `${k}x${v}`).join(' ') || '(none)'
  const cost = Object.entries(p.composition || {}).reduce((s, [id, n]) => {
    const t = (bf.enemyTypes || []).find(e => e.id === id)
    return s + (t ? t.threatCost * n : 0)
  }, 0)
  const w = p.spawnEmphasis && p.spawnEmphasis.weights
  const emph = w ? Object.entries(w).map(([k, v]) => `${k}:${(+v).toFixed(2)}`).join(',')
    : (p.spawnEmphasis && p.spawnEmphasis.focus) ? `focus(${p.spawnEmphasis.focus.x},${p.spawnEmphasis.focus.z})` : '-'
  return `[${comp}] ~${cost.toFixed(0)} threat | emph ${emph} | tactic: ${p.tactic}`
}

// ---- one plan cycle: read → plan → submit ----------------------------------
async function planCycle() {
  let bf
  try {
    bf = await bridge.request('GET', '/td/battlefield')
  } catch (e) { log('battlefield fetch failed:', e.message); return null }
  if (!bf || bf.ok === false) { log('battlefield unavailable'); return null }

  const wave = bf.nextWave
  log(`planning wave ${wave} (current wave ${bf.wave}, budget ${Math.round(bf.budget)}, ${(bf.towers || []).length} towers, idol hp ${bf.idol ? bf.idol.hp + '/' + bf.idol.max : '?'})`)
  if (bf.lastWave) {
    const lw = bf.lastWave
    log(`last wave #${lw.number}: spawned ${lw.spawned}, leaked ${lw.leaked}, killedByTowers ${lw.killedByTowers}, killedByPlayers ${lw.killedByPlayers}`)
  }

  let gen
  try {
    gen = await generatePlan(bf)
  } catch (e) { log('LLM plan failed (mod falls back to default wave):', e.message); return null }

  const { plan, flanks } = gen
  log(`weakest flank: ${flanks.weakestName} (${flanks.summary})`)
  log(`proposed: ${summarizePlan(plan, bf)}`)
  log(`taunt: "${plan.taunt}"`)

  if (DRY) {
    log('--dry: NOT posting. Would POST /td/waveplan:')
    console.log(JSON.stringify({ wave, ...plan }, null, 2))
    return { wave, plan }
  }

  try {
    const resp = await bridge.request('POST', '/td/waveplan', { wave, ...plan })
    const accepted = (resp && resp.accepted) || resp
    log(`ACCEPTED wave ${wave} (clamped by mod): ${summarizePlan(accepted, bf)}`)
    // NOTE: do NOT broadcast the taunt here — POST /td/waveplan already broadcasts
    // plan.taunt server-side (styled "☠ Warlord:"). A second /td/taunt would double it.
    return { wave, plan, accepted }
  } catch (e) { log('waveplan POST failed (mod falls back):', e.message); return null }
}

// ---- occasional mid-wave taunt ---------------------------------------------
// Rare, event-driven flavor: fire when the Idol takes a big hit or a wave was
// just cleared. Rate-limited to at most one per wave.
let lastTauntWave = -1
async function maybeMidWaveTaunt(bf, prev) {
  if (DRY || !bf) return
  if (bf.wave === lastTauntWave) return
  let line = null
  const idolHp = bf.idol ? bf.idol.hp : null
  const prevHp = prev && prev.idol ? prev.idol.hp : null
  if (idolHp != null && prevHp != null && prevHp - idolHp >= 15) {
    line = `Feel it — your Idol's light dims. ${idolHp} embers remain, and I have counted every one.`
  } else if (prev && prev.waveInProgress && !bf.waveInProgress && !bf.gameOver) {
    line = `You endured that tide. Good. There is so much more of me to spend.`
  }
  if (!line) return
  lastTauntWave = bf.wave
  try { await bridge.request('POST', '/td/taunt', { text: line }); log(`mid-wave taunt: "${line}"`) }
  catch (e) { log('mid-wave taunt failed:', e.message) }
}

// ---- main loop -------------------------------------------------------------
async function mainLoop() {
  log(`Warlord awakened. model=${MODEL} bridge=${bridge.baseUrl} poll=${POLL_MS}ms`)
  let lastPlannedWave = 0
  let prevBf = null
  for (;;) {
    let bf = null
    try { bf = await bridge.request('GET', '/td/battlefield') } catch (e) { log('poll failed:', e.message) }
    if (bf && bf.ok !== false) {
      // Plan any upcoming wave we haven't planned yet — ideally during intermission,
      // but also mid-wave if the next wave is still unplanned. Never plan twice.
      if (typeof bf.nextWave === 'number' && bf.nextWave > lastPlannedWave && !bf.gameOver) {
        const planWave = bf.nextWave
        try {
          const res = await planCycle()
          // Mark planned regardless of accept/skip so we don't hammer the LLM once/wave.
          lastPlannedWave = planWave
          if (!res) log(`wave ${planWave} left to the mod's default (no plan submitted)`)
        } catch (e) { log('plan cycle error:', e.message); lastPlannedWave = planWave }
      }
      try { await maybeMidWaveTaunt(bf, prevBf) } catch (e) { log('taunt check error:', e.message) }
      prevBf = bf
    }
    await sleep(POLL_MS)
  }
}

// ---- entrypoint ------------------------------------------------------------
;(async () => {
  if (!process.env.ANTHROPIC_API_KEY) log('WARNING: ANTHROPIC_API_KEY not set — LLM calls will fail.')
  if (ONCE) {
    log(`single cycle (${DRY ? 'DRY — will not post' : 'live'})`)
    try { await planCycle() } catch (e) { log('cycle failed:', e.message) }
    return
  }
  try { await mainLoop() } catch (e) { log('fatal:', e.message); process.exit(1) }
})()
