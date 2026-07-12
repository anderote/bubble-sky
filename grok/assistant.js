const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder')
const Vec3 = require('vec3')

const DIR = __dirname
// Load .env into process.env (XAI_API_KEY, optional GODMODE, GROK_* model overrides).
for (const line of fs.readFileSync(path.join(DIR, '.env'), 'utf8').split('\n')) {
  const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.+?)\s*$/)
  if (m && !process.env[m[1]]) process.env[m[1]] = m[2]
}
const KEY = process.env.XAI_API_KEY
const ROUTER_MODEL = process.env.XAI_MODEL || process.env.GROK_ROUTER_MODEL || 'grok-4.20-0309-non-reasoning'
const USERNAME = process.env.MC_USER || 'Grok'
// Grok ONLY responds to these people (allowlist) — everyone/everything else is ignored.
const ALLOW = new Set((process.env.GROK_ALLOW || 'claudebert').toLowerCase().split(',').map(s => s.trim()).filter(Boolean))
const BOTNAMES = /^(Grok|GrokDev|Overseer|Assistant|Tester|Codex.*|.*Drone\d+|ViscousVermin\d+|CodexSwarm\d+)$/i

function log(...a) { console.log(`[${new Date().toISOString()}]`, ...a) }
const sleep = ms => new Promise(r => setTimeout(r, ms))
process.on('uncaughtException', e => { if (/PartialReadError|VarInt|buffer end/.test(e?.message || '')) return; log('uncaught', e?.message) })

const bot = mineflayer.createBot({
  host: process.env.MC_HOST || 'localhost', port: +(process.env.MC_PORT || 25565),
  username: USERNAME, auth: 'offline', version: '1.21.6'
})
bot.loadPlugin(pathfinder)

let followTarget = null
const history = []
let busy = false

// ---- throttled server-command queue (Grok is opped → /fill, /setblock, etc.) ----
const cmdQueue = []
let draining = false
function enqueue(cmd) { cmdQueue.push(cmd); drain() }
async function drain() { if (draining) return; draining = true; while (cmdQueue.length) { try { bot.chat(cmdQueue.shift()) } catch {} await sleep(130) } draining = false }
async function queueIdle() { while (cmdQueue.length || draining) await sleep(120) }

// ---- helpers shared with skill modules ----
const clamp = (v, lo, hi, def) => { const n = parseInt(v, 10); return isNaN(n) ? def : Math.min(Math.max(n, lo), hi) }
const B = (s, def) => { const x = String(s || '').toLowerCase().replace(/^minecraft:/, '').replace(/[^a-z0-9_]/g, ''); return x || def }

// ---- pluggable execution backend (godmode default; survival stub) ----
const makeHands = require('./lib/hands')
const hands = makeHands({ enqueue, bot, log })

// ---- skill libraries (all route through hands, never raw commands) ----
const sctx = { enqueue, fillBox: hands.fillBox.bind(hands), B, clamp, set: (x, y, z, b) => hands.setBlock(x, y, z, b) }
const structures = require('./structures')(sctx)
const details = require('./detail')(sctx)
const makeSkills = require('./lib/skills')
const skills = makeSkills({ hands, structures, details, B, clamp, log })

// ---- architect + project memory ----
const architect = require('./lib/architect')({ skills, log })
const memory = require('./lib/memory')(path.join(DIR, 'memory'))
const survey = require('./lib/survey')

log(`backend=${hands.name} (godmode=${hands.godmode}) router=${ROUTER_MODEL} architect=${architect.MODEL}`)

// ---- one-shot tool schema (Phase 1: native tool calling) ----
const originProps = {
  origin: { type: 'string', enum: ['look', 'me'], description: '"look" = block the speaker looks at (use for "here"); "me" = speaker position' },
  x: { type: 'integer' }, y: { type: 'integer' }, z: { type: 'integer' }
}
const TOOLS = [
  { type: 'function', function: { name: 'build_structure', description: 'Build ONE named structure (quick, single object). For a big/detailed/multi-section build (grand castle, mansion, cathedral, "make it fancy/detailed") use plan_build instead.',
    parameters: { type: 'object', properties: { kind: { type: 'string', enum: skills.buildKinds },
      ...originProps, size: { type: 'integer' }, width: { type: 'integer' }, length: { type: 'integer' }, height: { type: 'integer' }, radius: { type: 'integer' }, base: { type: 'integer' },
      material: { type: 'string' }, roof_material: { type: 'string' }, floor: { type: 'string' }, axis: { type: 'string', enum: ['x', 'z'] }, reply: { type: 'string' } }, required: ['kind'] } } },
  { type: 'function', function: { name: 'plan_build', description: 'Escalate a large / detailed / multi-section build to the Architect (phased blueprint + detailing). Use for "big detailed castle", "grand X", follow-up edits to an existing build ("make it taller", "add a moat"), or anything that should have towers, roofs, trim, and lighting.',
    parameters: { type: 'object', properties: { goal: { type: 'string', description: 'the full build request in the user\'s words' }, ...originProps, reply: { type: 'string' } }, required: ['goal'] } } },
  { type: 'function', function: { name: 'fill_box', description: 'Fill a solid box of one block, sized by radius/height around an origin.',
    parameters: { type: 'object', properties: { ...originProps, block: { type: 'string' }, radius: { type: 'integer' }, height: { type: 'integer' }, reply: { type: 'string' } }, required: ['block'] } } },
  { type: 'function', function: { name: 'set_block', description: 'Place a single block. Target = the block the speaker looks at ("here") or explicit x,y,z.',
    parameters: { type: 'object', properties: { here: { type: 'boolean' }, ...originProps, block: { type: 'string' }, reply: { type: 'string' } }, required: ['block'] } } },
  { type: 'function', function: { name: 'dig', description: 'Remove a block (to air). here=true for the block the speaker looks at, or block=name for the nearest such block, or explicit x,y,z.',
    parameters: { type: 'object', properties: { here: { type: 'boolean' }, block: { type: 'string' }, x: { type: 'integer' }, y: { type: 'integer' }, z: { type: 'integer' }, reply: { type: 'string' } } } } },
  { type: 'function', function: { name: 'clear_area', description: 'Clear a big area to air.',
    parameters: { type: 'object', properties: { ...originProps, radius: { type: 'integer' }, height: { type: 'integer' }, reply: { type: 'string' } } } } },
  { type: 'function', function: { name: 'flatten', description: 'Clear an area and lay a flat floor.',
    parameters: { type: 'object', properties: { ...originProps, radius: { type: 'integer' }, height: { type: 'integer' }, floor: { type: 'string' }, reply: { type: 'string' } } } } },
  { type: 'function', function: { name: 'move', description: 'Movement / teleport. mode: come|follow (walk to & follow speaker), stop, goto (x,z), explore, tp (teleport SELF to speaker or x,y,z), bring (teleport the speaker/player to Grok).',
    parameters: { type: 'object', properties: { mode: { type: 'string', enum: ['come', 'follow', 'stop', 'goto', 'explore', 'tp', 'bring'] }, player: { type: 'string' }, x: { type: 'integer' }, y: { type: 'integer' }, z: { type: 'integer' }, reply: { type: 'string' } }, required: ['mode'] } } },
  { type: 'function', function: { name: 'world_cmd', description: 'World control. cmd: time (value day|night|noon|midnight), weather (value clear|rain|thunder), give (item + count to the speaker/player).',
    parameters: { type: 'object', properties: { cmd: { type: 'string', enum: ['time', 'weather', 'give'] }, value: { type: 'string' }, item: { type: 'string' }, count: { type: 'integer' }, player: { type: 'string' }, reply: { type: 'string' } }, required: ['cmd'] } } },
  { type: 'function', function: { name: 'answer', description: 'Pure conversation / answering questions (where are you, what\'s nearby, what can you do). No world change.',
    parameters: { type: 'object', properties: { reply: { type: 'string' } }, required: ['reply'] } } }
]

const SYS = `You are ${USERNAME}, a warm, genuinely helpful AI assistant living in a Minecraft world (creative, 1.21.6) with OPERATOR powers (edits are instant). Players talk in natural language; you act by calling exactly ONE tool.
You receive the message plus a JSON world-state: your position, nearby players/entities/resources, inventory, biome, and speakerLookingAt = the block coordinates the speaker is currently looking at.
Guidance:
- "build a house/tower/wall/bridge/pyramid/dome here" → build_structure (pick kind; origin "look" for "here").
- Anything BIG or DETAILED, a whole compound, "grand/epic/fancy/detailed", or a follow-up that edits an existing build ("make it taller", "add a moat", "add towers") → plan_build (the Architect makes a phased, detailed blueprint).
- Quick edits → fill_box / set_block / dig / clear_area / flatten.
- Movement/teleport → move. Time/weather/give → world_cmd.
- Questions & chit-chat ("where are you", "what's nearby", "what can you do") → answer, using world-state.
Default origin to "look" when they say "here" or "where I'm looking". Pick sensible sizes if unspecified. Put a short (<15 words), warm message in the tool's "reply".`

bot.once('spawn', () => {
  log('spawned at', bot.entity.position)
  const m = new Movements(bot); m.allowSprinting = true; m.canDig = false
  bot.pathfinder.setMovements(m)
  bot.chat(`${USERNAME} here (${hands.godmode ? 'godmode on' : 'survival'}) — try "build a big detailed castle here" or "follow me"`)
  setInterval(() => { if (!followTarget) return; const e = bot.players[followTarget]?.entity; if (e) bot.pathfinder.setGoal(new goals.GoalFollow(e, 2), true) }, 2000)
})

function lookTarget(username) {
  const e = bot.players[username]?.entity
  if (!e) return null
  const yaw = e.yaw, pitch = e.pitch
  const dir = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch))
  const from = e.position.offset(0, 1.62, 0)
  try { const hit = bot.world.raycast(from, dir.normalize(), 96); if (hit) { const p = hit.position || hit; return { x: p.x, y: p.y, z: p.z } } } catch {}
  return null
}

function resources() {
  const mcData = require('minecraft-data')(bot.version)
  const cats = { wood: ['oak_log', 'birch_log', 'spruce_log', 'jungle_log', 'acacia_log', 'dark_oak_log', 'mangrove_log'], stone: ['stone'], coal: ['coal_ore'], iron: ['iron_ore'], water: ['water'], sand: ['sand'] }
  const out = {}; const here = bot.entity.position
  for (const [k, names] of Object.entries(cats)) {
    const ids = names.map(n => mcData.blocksByName[n]?.id).filter(x => x != null); if (!ids.length) continue
    const f = bot.findBlocks({ matching: ids, maxDistance: 32, count: 1 })
    if (f.length) out[k] = { x: f[0].x, y: f[0].y, z: f[0].z, dist: Math.round(f[0].distanceTo(here)) }
  }
  return out
}

function world(speaker) {
  const p = bot.entity.position
  const players = Object.values(bot.players).filter(pl => pl.entity && !BOTNAMES.test(pl.username)).map(pl => ({ name: pl.username, dist: Math.round(pl.entity.position.distanceTo(p)) })).sort((a, b) => a.dist - b.dist)
  const entities = Object.values(bot.entities).filter(e => e !== bot.entity && e.position && e.type !== 'player' && e.position.distanceTo(p) < 24).map(e => ({ name: e.name || e.displayName, dist: Math.round(e.position.distanceTo(p)) })).sort((a, b) => a.dist - b.dist).slice(0, 6)
  let biome; try { biome = bot.blockAt(p)?.biome?.name } catch {}
  const activeProject = memory.context()
  return { position: { x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) }, biome, health: bot.health, food: bot.food, timeOfDay: bot.time.timeOfDay, players, entities, inventory: bot.inventory.items().map(i => `${i.name} x${i.count}`).slice(0, 12), resourcesNearby: resources(), following: followTarget, speakerLookingAt: speaker ? lookTarget(speaker) : null, activeProject: activeProject ? { project: activeProject.project, origin: activeProject.origin } : null }
}

// ---- one-shot: ask the router/executor model which tool to call ----
async function askOneShot(from, message) {
  const payload = { from, message, world: world(from), recentChat: history.slice(-8) }
  const res = await fetch('https://api.x.ai/v1/chat/completions', {
    method: 'POST', headers: { Authorization: `Bearer ${KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: ROUTER_MODEL, temperature: 0.4, max_tokens: 400, tools: TOOLS, tool_choice: 'auto',
      messages: [{ role: 'system', content: SYS }, { role: 'user', content: JSON.stringify(payload) }] })
  })
  if (!res.ok) throw new Error(`xai ${res.status}: ${(await res.text()).slice(0, 120)}`)
  const msg = (await res.json()).choices[0].message
  return { calls: msg.tool_calls || [], content: (msg.content || '').trim() }
}

bot.on('chat', async (username, message) => {
  if (!ALLOW.has(username.toLowerCase())) return
  history.push(`${username}: ${message}`); while (history.length > 12) history.shift()
  if (busy) return
  busy = true
  try {
    const { calls, content } = await askOneShot(username, message)
    if (!calls.length) { if (content) say(content); busy = false; return }
    for (const c of calls) {
      let args = {}; try { args = JSON.parse(c.function.arguments || '{}') } catch {}
      log(`<${username}> ${message}  ->  ${c.function.name} ${JSON.stringify(args)}`)
      await dispatch(c.function.name, args, username)
    }
  } catch (e) { log('grok err', e.message) }
  busy = false
})

const say = s => { if (s) bot.chat(String(s).slice(0, 200)) }

function resolveOrigin(args, speaker) {
  if (args.here || args.origin === 'look') { const l = lookTarget(speaker); if (l) return round(l) }
  if (args.x != null && args.z != null) return { x: Math.round(+args.x), y: args.y != null ? Math.round(+args.y) : Math.round(bot.entity.position.y), z: Math.round(+args.z) }
  const e = bot.players[speaker]?.entity
  if (args.origin === 'me' && e) return round(e.position)
  const l = lookTarget(speaker); if (l) return round(l)
  if (e) return round(e.position)
  return round(bot.entity.position)
}
const round = p => ({ x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) })

async function dispatch(tool, args, speaker) {
  if (args.reply) say(args.reply)
  const p = bot.entity.position
  const spEnt = bot.players[speaker]?.entity
  switch (tool) {
    case 'answer': break
    case 'move': {
      const mode = args.mode
      if (mode === 'come' || mode === 'follow') { followTarget = args.player || speaker; const e = bot.players[followTarget]?.entity; if (e) bot.pathfinder.setGoal(new goals.GoalFollow(e, 2), true) }
      else if (mode === 'stop') { followTarget = null; bot.pathfinder.setGoal(null); bot.clearControlStates() }
      else if (mode === 'goto') { if (args.x != null && args.z != null) { followTarget = null; bot.pathfinder.setGoal(new goals.GoalNear(+args.x, args.y != null ? +args.y : p.y, +args.z, 1)) } }
      else if (mode === 'explore') { followTarget = null; bot.pathfinder.setGoal(new goals.GoalNear(Math.floor(p.x + (Math.random() * 40 - 20)), p.y, Math.floor(p.z + (Math.random() * 40 - 20)), 1)) }
      else if (mode === 'tp') { const o = (args.x != null) ? { x: +args.x, y: +args.y, z: +args.z } : (spEnt ? spEnt.position : null); if (o) hands.tp(USERNAME, o.x, o.y, o.z) }
      else if (mode === 'bring') { hands.tpTo(args.player || speaker, USERNAME) }
      break
    }
    case 'world_cmd': {
      if (args.cmd === 'time') hands.setTime(String(args.value || 'day').toLowerCase())
      else if (args.cmd === 'weather') hands.setWeather(String(args.value || 'clear').toLowerCase())
      else if (args.cmd === 'give') hands.give(args.player || speaker, B(args.item, 'diamond'), clamp(args.count, 1, 64, 1))
      break
    }
    case 'set_block': { const o = resolveOrigin(args, speaker); hands.setBlock(o.x, o.y, o.z, args.block || 'stone'); break }
    case 'dig': {
      if (args.block && !args.here) { const mcData = require('minecraft-data')(bot.version); const id = mcData.blocksByName[B(args.block, '')]?.id; const b = id != null ? bot.findBlock({ matching: id, maxDistance: 64 }) : null; if (b) hands.dig(b.position.x, b.position.y, b.position.z); else say(`no ${args.block} nearby`) }
      else { const o = resolveOrigin({ here: true, x: args.x, y: args.y, z: args.z }, speaker); hands.dig(o.x, o.y, o.z) }
      break
    }
    case 'fill_box': { const o = resolveOrigin(args, speaker); const r = clamp(args.radius, 1, 60, 6), h = clamp(args.height, 0, 60, 4); const n = hands.fillBox(o.x - r, o.y, o.z - r, o.x + r, o.y + h, o.z + r, args.block || 'stone'); say(args.reply ? null : `filled a ${2 * r + 1}-wide box (${n} ops)`); break }
    case 'clear_area': { const o = resolveOrigin(args, speaker); const r = clamp(args.radius, 1, 60, 12), h = clamp(args.height, 1, 60, 20); const n = hands.clearArea(o.x, o.y, o.z, r, h); if (!args.reply) say(`clearing a ${2 * r + 1}-wide area (${n} ops)`); break }
    case 'flatten': { const o = resolveOrigin(args, speaker); const r = clamp(args.radius, 1, 60, 12), h = clamp(args.height, 1, 60, 12); hands.fillBox(o.x - r, o.y, o.z - r, o.x + r, o.y + h, o.z + r, 'air'); hands.fillBox(o.x - r, o.y - 1, o.z - r, o.x + r, o.y - 1, o.z + r, B(args.floor, 'grass_block')); if (!args.reply) say(`flattened a ${2 * r + 1}-wide platform`); break }
    case 'build_structure': {
      const o = resolveOrigin(args, speaker)
      let kind = B(args.kind, 'house')
      const gen = structures.gens[kind] || structures.gens[structures.alias[kind]]
      if (!gen) { say(`not sure how to build "${kind}"`); break }
      try { gen(o, args) } catch (e) { log('build err', e.message); say('hit a snag building that') }
      // remember simple builds too, so "make it taller" can follow up
      memory.save({ project: kind, origin: o, palette: { wall: B(args.material, 'stone_bricks') }, phasesDone: [kind], goal: '' })
      break
    }
    case 'plan_build': await runProject(args, speaker); break
    default: log('unknown tool', tool)
  }
}

// ---- Phase 2: SURVEY → ARCHITECT → observed goal LOOP → VERIFY/repair ----
const cap = s => String(s || '').charAt(0).toUpperCase() + String(s || '').slice(1)

// Actual footprint radius from a blueprint's absolute step coordinates.
function footprintRadius(bp) {
  let r = 6
  for (const ph of bp.phases) for (const st of ph.steps) {
    const a = st.args || {}
    for (const [kx, kz] of [['x', 'z'], ['x1', 'z1'], ['x2', 'z2']]) {
      if (a[kx] != null && a[kz] != null) r = Math.max(r, Math.abs(+a[kx] - bp.origin.x), Math.abs(+a[kz] - bp.origin.z))
    }
  }
  return Math.min(30, Math.round(r))
}

// Run the plan phase-by-phase: execute a section, PAUSE, READ the world, and
// re-run a phase that visibly placed nothing. Godmode makes each section instant,
// but the overall build is a paced, observed sequence.
async function executePlan(state, origin, fr) {
  const phases = state.plan.phases
  const yLo = origin.y - 2, yHi = origin.y + 20
  state.emitted = state.emitted || 0; state.okSteps = state.okSteps || 0; state.failSteps = state.failSteps || 0
  for (; state.cursor < phases.length; state.cursor++) {
    const ph = phases[state.cursor]
    // Repair-check only the load-bearing fill phases (doubling setblock-heavy
    // detailing risks spam and rarely fails).
    const structural = /found|shell|wall|tower|keep|base|floor/i.test(ph.phase)
    const before = structural ? survey.observe(bot, origin, fr, yLo, yHi) : null
    let phaseEmit = 0
    for (const step of ph.steps) { const r = skills.run(step, { origin: state.plan.origin }); if (r.ok) { state.okSteps++; phaseEmit += (r.n || 1) } else state.failSteps++ }
    state.emitted += phaseEmit
    await queueIdle()
    // observe: did this section actually add structure? Settle first so the
    // server's block-change packets have time to reach the bot's world view.
    // (World reads lag large /fills, so treat this as advisory: only repair when
    // the phase BOTH read empty AND emitted almost nothing.)
    if (before && ph.steps.length >= 1) {
      await sleep(600)
      const after = survey.observe(bot, origin, fr, yLo, yHi)
      const readEmpty = after.solid <= before.solid && after.solid / (after.total || 1) < 0.06
      if (readEmpty && phaseEmit < 3) {
        log(`phase "${ph.phase}" empty (read ${before.solid}->${after.solid}, emitted ${phaseEmit}); repairing once`)
        for (const step of ph.steps) skills.run(step, { origin: state.plan.origin }); await queueIdle()
      } else log(`phase "${ph.phase}" ok (read ${before.solid}->${after.solid}, emitted ${phaseEmit} ops)`)
    }
    state.done.push(ph.phase)
    const next = phases[state.cursor + 1]
    say(`${cap(ph.phase)} done${next ? `, starting ${next.phase}…` : '.'}`)
    await sleep(550)   // deliberate pacing between sections
  }
}

// Verify the finished build against the goal. Primary signal: did the executor
// actually issue the planned work (emitted ops, step success). World-sampling is
// advisory — the bot's world view lags big /fills and the true wall radius is
// unknown — so a real, completed build is not rejected by a stale/mis-located read.
function verifyBuild(bp, origin, fr, wallH, tally) {
  const overall = survey.observe(bot, origin, fr, origin.y, origin.y + Math.max(4, wallH || 6))
  const builtByExec = tally && tally.emitted >= 30 && tally.okSteps > tally.failSteps
  const builtByWorld = overall.ratio >= 0.04
  if (!builtByExec && !builtByWorld) return { ok: false, missing: 'the structure', deficiency: 'almost nothing landed at the site — rebuild the whole thing' }
  return { ok: true, fill: +overall.ratio.toFixed(3), emitted: tally ? tally.emitted : null, via: builtByWorld ? 'world+exec' : 'exec' }
}

async function runProject(args, speaker) {
  const o = resolveOrigin(args, speaker)
  const mem = memory.context()
  const isFollowUp = !!mem && !(args.here || args.origin || args.x != null)
  const origin0 = isFollowUp ? mem.origin : o

  // 1) SURVEY the terrain before planning.
  say('Let me look over the site…')
  const site = survey.surveySite(bot, origin0, 20)
  log('site survey', JSON.stringify(site))
  const origin = { x: origin0.x, y: (isFollowUp ? origin0.y : site.groundY + 1), z: origin0.z }

  // 2) ARCHITECT plans around the real terrain (+ any active project for edits).
  say(isFollowUp ? 'Updating the existing build…' : `Site looks ${site.note}. Drawing up a plan…`)
  let bp
  try { bp = await architect.plan({ goal: args.goal || 'build something impressive', origin, memory: mem, site }) }
  catch (e) { log('architect err', e.message); say('trouble planning that — try again?'); return }
  bp.origin = origin
  const v = bp._validation, fr = footprintRadius(bp)
  log(`architect "${bp.project}" phases=${v.phases} steps=${v.steps} materials=${v.materials} detailing=${v.hasDetailing} ok=${v.ok} footprintR=${fr} palette=${JSON.stringify(bp.palette)}`)
  say(`Plan: ${bp.project} — ${v.phases} phases. Foundation first…`)

  // 3) Observed goal loop, section by section.
  const state = { goal: args.goal, plan: bp, cursor: 0, done: [] }
  await executePlan(state, origin, fr)

  // 4) VERIFY against the goal; re-plan the deficient part (bounded retries).
  const wallH = 6
  await sleep(700)   // let final block updates reach the bot before sampling
  for (let tries = 0; tries < 1; tries++) {
    const chk = verifyBuild(bp, origin, fr, wallH, state)
    log('verify', JSON.stringify(chk))
    if (chk.ok) { say('Looks solid — build reads as done.'); break }
    say(`Not quite done — fixing ${chk.missing}.`)
    let rep
    try { rep = await architect.plan({ goal: args.goal, origin, memory: memory.context(), site, repair: chk }) }
    catch (e) { log('repair err', e.message); break }
    rep.origin = origin
    await executePlan({ goal: args.goal, plan: rep, cursor: 0, done: state.done }, origin, fr)
  }

  memory.save({ id: mem ? memory.active()?.id : undefined, project: bp.project, origin, palette: bp.palette, phasesDone: state.done, goal: args.goal })
  say(`${bp.project} done — come take a look!`)
}

bot.on('kicked', r => log('KICKED', r)); bot.on('error', e => log('ERR', e.message)); bot.on('end', () => log('END'))
