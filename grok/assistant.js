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
// ---- pluggable LLM backend, per role (router/executor vs architect) ----
const { createLLM } = require('./lib/llm')
const ROUTER_PROVIDER = process.env.GROK_ROUTER_PROVIDER || 'xai'
const ROUTER_MODEL = process.env.GROK_ROUTER_MODEL || process.env.XAI_MODEL || 'grok-4.20-0309-non-reasoning'
const routerLLM = createLLM({ provider: ROUTER_PROVIDER })
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
// A vague build we asked a clarifying question about, awaiting the user's answer.
// { goal: <original request>, origin: {x,y,z}, asked: true }
let pendingBuild = null

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
const furniture = require('./furniture')(sctx)
const makeSkills = require('./lib/skills')
const skills = makeSkills({ hands, structures, details, furniture, B, clamp, log })
const { resolvePalette } = require('./palettes')

// ---- architect + project memory ----
const architect = require('./lib/architect')({ skills, log })
const memory = require('./lib/memory')(path.join(DIR, 'memory'))
const survey = require('./lib/survey')

// ---- agent-native build pipeline (semantic blueprint → additive, site-aware
// compiler → shared Codex job state). plan_build + edits route through here. ----
const build = require('./lib/build')
const BUILD_OUT = path.join(DIR, 'build-out')   // our own interop dir; NEVER Codex's live state.json

// ---- named FLAGS (spatial markers) + SCHEMATIC LIBRARY (save/reuse builds) ----
const flags = require('./lib/flags')({ dir: path.join(DIR, 'memory'), enqueue, hands, log })
const { wallBetween } = require('./lib/flags')
const library = require('./lib/build/library')({ dir: path.join(DIR, 'blueprints', 'saved'), log })

log(`backend=${hands.name} (godmode=${hands.godmode}) router=${ROUTER_PROVIDER}/${ROUTER_MODEL} architect=${architect.PROVIDER}/${architect.MODEL}`)

// ---- one-shot tool schema (Phase 1: native tool calling) ----
const originProps = {
  origin: { type: 'string', enum: ['look', 'me', 'flag'], description: '"look" = block the speaker looks at (use for "here"); "me" = speaker position; "flag" = a named flag marker (then also set "flag")' },
  x: { type: 'integer' }, y: { type: 'integer' }, z: { type: 'integer' },
  flag: { type: 'string', description: 'flag name to use as origin when origin="flag" (e.g. "A2")' }
}
const TOOLS = [
  { type: 'function', function: { name: 'build_structure', description: 'Build ONE named structure (quick, single BARE object — no interior furnishing). For anything FURNISHED, "nice", detailed, or multi-section (a furnished/nice house, grand castle, mansion, cathedral, "make it fancy/detailed", furnished rooms) use plan_build instead.',
    parameters: { type: 'object', properties: { kind: { type: 'string', enum: skills.buildKinds },
      ...originProps, size: { type: 'integer' }, width: { type: 'integer' }, length: { type: 'integer' }, height: { type: 'integer' }, radius: { type: 'integer' }, base: { type: 'integer' },
      material: { type: 'string' }, roof_material: { type: 'string' }, floor: { type: 'string' }, axis: { type: 'string', enum: ['x', 'z'] }, reply: { type: 'string' } }, required: ['kind'] } } },
  { type: 'function', function: { name: 'plan_build', description: 'Escalate a large / detailed / multi-section build to the Architect (semantic blueprint → additive, site-aware build with towers, roofs, trim, furniture, lighting). Use for "big detailed castle", "grand X", furnished houses, anything that should be furnished + detailed.',
    parameters: { type: 'object', properties: { goal: { type: 'string', description: 'the full build request in the user\'s words' }, ...originProps, reply: { type: 'string' } }, required: ['goal'] } } },
  { type: 'function', function: { name: 'edit_build', description: 'Incrementally + NON-DESTRUCTIVELY edit the ACTIVE build (a follow-up that modifies what was just built): "make it taller / raise the roof", "add a west tower", "remove the tower". Touches only the changed region, leaves the rest standing.',
    parameters: { type: 'object', properties: { op: { type: 'string', enum: ['raise_roof', 'add_tower', 'remove_region'] }, side: { type: 'string', enum: ['north', 'south', 'east', 'west'] }, amount: { type: 'integer', description: 'blocks to raise (raise_roof)' }, region: { type: 'string', description: 'region id to remove (remove_region)' }, reply: { type: 'string' } }, required: ['op'] } } },
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
  { type: 'function', function: { name: 'flag', description: 'Named spatial markers ("flags") the user plants and later references in builds. op: set (plant a flag named `name` at the origin — "flag A2 here" / "plant a flag called home"; origin "look" for "here"), list (say every flag + coords), remove (delete flag `name` + its marker), goto (teleport to flag `name`).',
    parameters: { type: 'object', properties: { op: { type: 'string', enum: ['set', 'list', 'remove', 'goto'] }, name: { type: 'string', description: 'flag name (e.g. "A2")' }, ...originProps, reply: { type: 'string' } }, required: ['op'] } } },
  { type: 'function', function: { name: 'build_between', description: 'Build a WALL spanning two named flags ("build a wall between A2 and B1"). flagA/flagB are flag names; optional block/material, height, thickness.',
    parameters: { type: 'object', properties: { flagA: { type: 'string' }, flagB: { type: 'string' }, kindOrBlock: { type: 'string' }, block: { type: 'string' }, material: { type: 'string' }, height: { type: 'integer' }, thickness: { type: 'integer' }, reply: { type: 'string' } }, required: ['flagA', 'flagB'] } } },
  { type: 'function', function: { name: 'save_build', description: 'Save the ACTIVE/last build to the schematic library for reuse ("save this as cozy_cottage").',
    parameters: { type: 'object', properties: { name: { type: 'string' }, reply: { type: 'string' } }, required: ['name'] } } },
  { type: 'function', function: { name: 'list_builds', description: 'Say the names of the saved builds in the library ("what have I saved", "list builds").',
    parameters: { type: 'object', properties: { reply: { type: 'string' } } } } },
  { type: 'function', function: { name: 'build_saved', description: 'Rebuild a previously SAVED build by name at a new spot ("build cozy_cottage here") — re-anchored to the new origin, additive + terrain-fit. A follow-up edit_build then modifies it.',
    parameters: { type: 'object', properties: { name: { type: 'string' }, ...originProps, reply: { type: 'string' } }, required: ['name'] } } },
  { type: 'function', function: { name: 'delete_build', description: 'Delete a saved build from the library by name.',
    parameters: { type: 'object', properties: { name: { type: 'string' }, reply: { type: 'string' } }, required: ['name'] } } },
  { type: 'function', function: { name: 'ask', description: 'Ask 1-2 SHORT clarifying questions BEFORE a VAGUE / open-ended build ("build a cozy cottage", "build a village", "make something cool") when a quick clarification (size / style / theme / location) would materially help. Do NOT use for specific requests ("build a 9x9 stone tower here") — build those directly. Never ask twice for the same request.',
    parameters: { type: 'object', properties: { goal: { type: 'string', description: 'the ORIGINAL build request in the user\'s words (so it can be combined with their answer)' }, ...originProps, reply: { type: 'string', description: 'the 1-2 short questions to ask, phrased warmly (<25 words)' } }, required: ['goal', 'reply'] } } },
  { type: 'function', function: { name: 'answer', description: 'Pure conversation / answering questions (where are you, what\'s nearby, what can you do). No world change.',
    parameters: { type: 'object', properties: { reply: { type: 'string' } }, required: ['reply'] } } }
]

const SYS = `You are ${USERNAME}, a warm, genuinely helpful AI assistant living in a Minecraft world (creative, 1.21.6) with OPERATOR powers (edits are instant). Players talk in natural language; you act by calling exactly ONE tool.
You receive the message plus a JSON world-state: your position, nearby players/entities/resources, inventory, biome, and speakerLookingAt = the block coordinates the speaker is currently looking at.
Guidance:
- A quick BARE structure ("just a plain house/tower/wall/bridge/pyramid/dome here", no interior) → build_structure (pick kind; origin "look" for "here").
- Anything FURNISHED or "nice/good/detailed", a home someone could live in, BIG or multi-section builds, a whole compound, "grand/epic/fancy", furnished rooms (bedroom/kitchen/library/great hall) → plan_build (the Architect authors a furnished, lit, terrain-fit blueprint; it builds additively without disturbing surroundings). When in doubt, prefer plan_build.
- A follow-up that MODIFIES the build you just made ("make it taller", "raise the roof", "add a west tower", "remove the tower") → edit_build (non-destructive; only the changed part is touched).
- Quick edits → fill_box / set_block / dig / clear_area / flatten.
- FLAGS (named spatial markers): "flag A2 here" / "plant a flag called home" / "mark this as A2" → flag op:set (origin "look" for "here"). "list/show flags" → flag op:list. "remove/delete flag A2" → flag op:remove. "go to / teleport to flag A2" → flag op:goto.
- "build a wall between A2 and B1" (two flag NAMES) → build_between (flagA=A2, flagB=B1). To build something AT a flag, use a normal build tool with origin:"flag" + flag:"A2".
- SAVED BUILDS (schematic library): "save this as <name>" / "save this build" → save_build. "list builds" / "what have I saved" → list_builds. "build <saved name> here" / "place my <name> here" → build_saved (origin "look" for "here"). "delete build <name>" → delete_build. After build_saved, "make it taller / add a tower" → edit_build as usual.
- Movement/teleport → move. Time/weather/give → world_cmd.
- Questions & chit-chat ("where are you", "what's nearby", "what can you do") → answer, using world-state.
CLARIFYING QUESTIONS (build requests only):
- If a build request is VAGUE / open-ended ("build a cozy cottage", "build a village", "make something cool") and 1-2 short questions about size / style / theme / location would materially help, call ask (put the ORIGINAL request in "goal", the questions in "reply", origin "look" for "here"). Keep it minimal & natural — never interrogate, max 2 questions, and only when it genuinely helps.
- A SPECIFIC request ("build a 9x9 stone tower here") must NOT trigger ask — build it directly.
- If your LAST turn asked a clarifying question (see the conversation history), treat the user's reply as the ANSWER: proceed to build with plan_build using the ORIGINAL request + their answer (combined into "goal"). Do NOT ask again.
Default origin to "look" when they say "here" or "where I'm looking". Pick sensible sizes if unspecified. Put a short (<15 words), warm message in the tool's "reply".`

bot.once('spawn', () => {
  log('spawned at', bot.entity.position)
  // QUIET the build: /fill + /setblock otherwise broadcast "Successfully filled…" /
  // "Changed the block at…" to every op on EVERY command. Silence that feedback so
  // Grok builds without flooding chat. (Grok still speaks via its own say() for answers.)
  bot.chat('/gamerule sendCommandFeedback false')
  bot.chat('/gamerule logAdminCommands false')
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

// Neutral tool list (see lib/llm.js) derived from the OpenAI-format TOOLS above.
const NEUTRAL_TOOLS = TOOLS.map(t => ({ name: t.function.name, description: t.function.description, schema: t.function.parameters }))

// ---- one-shot: ask the router/executor model which tool to call ----
// Returns normalized calls: [{ id, name, args }] with args already JSON-parsed.
async function askOneShot(from, message) {
  const payload = { from, message, world: world(from), conversation: history.slice(-20) }
  const { text, toolCalls } = await routerLLM.chat({
    system: SYS,
    messages: [{ role: 'user', content: JSON.stringify(payload) }],
    tools: NEUTRAL_TOOLS, toolChoice: 'auto', maxTokens: 400, model: ROUTER_MODEL
  })
  return { calls: toolCalls, content: text }
}

bot.on('chat', async (username, message) => {
  if (!ALLOW.has(username.toLowerCase())) return
  history.push(`${username}: ${message}`); while (history.length > 30) history.shift()
  if (busy) return
  busy = true
  try {
    const { calls, content } = await askOneShot(username, message)
    if (!calls.length) { if (content) say(content); busy = false; return }
    for (const c of calls) {
      let name = c.name
      let args = c.args || {}
      // Loop guard: if we already asked a clarifying question and the model tries
      // to ask AGAIN, treat this reply as the answer and build instead of re-asking.
      if (name === 'ask' && pendingBuild && pendingBuild.asked) {
        name = 'plan_build'
        args = { goal: `${pendingBuild.goal} — ${message}` }
      }
      log(`<${username}> ${message}  ->  ${name} ${JSON.stringify(args)}`)
      await dispatch(name, args, username, message)
    }
  } catch (e) { log('grok err', e.message) }
  busy = false
})

const say = s => { if (s) { bot.chat(String(s).slice(0, 200)); history.push(`${USERNAME}: ${s}`); while (history.length > 30) history.shift() } }  // remember our own replies too

function resolveOrigin(args, speaker) {
  if (args.origin === 'flag' && args.flag) { const f = flags.get(args.flag); if (f) return { x: f.x, y: f.y, z: f.z } }
  if (args.here || args.origin === 'look') { const l = lookTarget(speaker); if (l) return round(l) }
  if (args.x != null && args.z != null) return { x: Math.round(+args.x), y: args.y != null ? Math.round(+args.y) : Math.round(bot.entity.position.y), z: Math.round(+args.z) }
  const e = bot.players[speaker]?.entity
  if (args.origin === 'me' && e) return round(e.position)
  const l = lookTarget(speaker); if (l) return round(l)
  if (e) return round(e.position)
  return round(bot.entity.position)
}
const round = p => ({ x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) })

async function dispatch(tool, args, speaker, message) {
  // Grok is QUIET about the *work* — no per-block/per-phase spam (that flood came from
  // the sendCommandFeedback gamerule, now disabled). But it STILL speaks the one short
  // acknowledgement line the model wrote, so it never feels mute to a command.
  // `answer`/`ask` say their own reply below; plan_build emits its own start/finish lines.
  if (args.reply && !SELF_REPLY.has(tool)) say(args.reply)
  const p = bot.entity.position
  const spEnt = bot.players[speaker]?.entity
  switch (tool) {
    case 'answer': say(args.reply); break
    case 'ask': {
      // Vague build → ask 1-2 short questions, remember the original request + origin
      // so the user's next message (the answer) proceeds straight to plan_build.
      const o = resolveOrigin(args, speaker)
      pendingBuild = { goal: args.goal || message, origin: o, asked: true }
      say(args.reply || 'Happy to! What size and style did you have in mind?')
      break
    }
    case 'move': {
      const mode = args.mode
      if (mode === 'come' || mode === 'follow') { followTarget = args.player || speaker; const e = bot.players[followTarget]?.entity; if (e) bot.pathfinder.setGoal(new goals.GoalFollow(e, 2), true) }
      else if (mode === 'stop') { followTarget = null; bot.pathfinder.setGoal(null); bot.clearControlStates() }
      else if (mode === 'goto') { if (args.x != null && args.z != null) { followTarget = null; bot.pathfinder.setGoal(new goals.GoalNear(+args.x, args.y != null ? +args.y : p.y, +args.z, 1)) } }
      else if (mode === 'explore') { followTarget = null; bot.pathfinder.setGoal(new goals.GoalNear(Math.floor(p.x + (Math.random() * 40 - 20)), p.y, Math.floor(p.z + (Math.random() * 40 - 20)), 1)) }
      else if (mode === 'tp') { if (args.x != null) hands.tp(USERNAME, +args.x, +args.y, +args.z); else hands.tpTo(USERNAME, args.player || speaker) }  // by name → works at any distance
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
    case 'fill_box': { const o = resolveOrigin(args, speaker); const r = clamp(args.radius, 1, 60, 6), h = clamp(args.height, 0, 60, 4); hands.fillBox(o.x - r, o.y, o.z - r, o.x + r, o.y + h, o.z + r, args.block || 'stone'); break }
    case 'clear_area': { const o = resolveOrigin(args, speaker); const r = clamp(args.radius, 1, 60, 12), h = clamp(args.height, 1, 60, 20); hands.clearArea(o.x, o.y, o.z, r, h); break }
    case 'flatten': { const o = resolveOrigin(args, speaker); const r = clamp(args.radius, 1, 60, 12), h = clamp(args.height, 1, 60, 12); hands.fillBox(o.x - r, o.y, o.z - r, o.x + r, o.y + h, o.z + r, 'air'); hands.fillBox(o.x - r, o.y - 1, o.z - r, o.x + r, o.y - 1, o.z + r, B(args.floor, 'grass_block')); break }
    case 'build_structure': {
      pendingBuild = null
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
    case 'edit_build': await runEdit(args, speaker); break
    case 'flag': await runFlag(args, speaker); break
    case 'build_between': await runBuildBetween(args, speaker); break
    case 'save_build': await runSaveBuild(args); break
    case 'list_builds': runListBuilds(); break
    case 'build_saved': await runBuildSaved(args, speaker); break
    case 'delete_build': runDeleteBuild(args); break
    default: log('unknown tool', tool)
  }
}

// Tools that speak their OWN reply line(s) below (so dispatch doesn't double-say).
const SELF_REPLY = new Set(['answer', 'ask', 'plan_build', 'edit_build', 'flag', 'build_between', 'save_build', 'list_builds', 'build_saved', 'delete_build'])

// ---- FLAGS: set / list / remove / goto ----
async function runFlag(args, speaker) {
  const op = String(args.op || 'set').toLowerCase()
  const dim = (bot.game && bot.game.dimension ? String(bot.game.dimension).replace(/^minecraft:/, '') : 'overworld')
  if (op === 'list') {
    const fl = flags.list()
    say(fl.length ? 'Flags: ' + fl.map(f => `${f.name} (${f.x},${f.y},${f.z})`).join(', ') : 'No flags planted yet.')
    return
  }
  if (op === 'remove' || op === 'delete') {
    const name = args.name || args.flag
    say(flags.remove(name) ? `Removed flag ${name}.` : `No flag named "${name}".`)
    return
  }
  if (op === 'goto' || op === 'tp') {
    const name = args.name || args.flag
    const f = flags.get(name)
    if (!f) { say(`No flag named "${name}".`); return }
    hands.tp(USERNAME, f.x, f.y + 1, f.z)
    say(args.reply || `Heading to flag ${f.name}.`)
    return
  }
  // set (default)
  const name = args.name || args.flag || 'flag'
  const o = resolveOrigin(args.origin === 'flag' ? { origin: 'look' } : args, speaker)
  const rec = flags.set(name, { x: o.x, y: o.y, z: o.z, dim })
  await queueIdle()
  say(args.reply || `Planted flag ${rec.name} at (${rec.x},${rec.y},${rec.z}).`)
}

// ---- build a wall spanning two flags ----
async function runBuildBetween(args, speaker) {
  const A = flags.get(args.flagA || args.a)
  const B2 = flags.get(args.flagB || args.b)
  if (!A) { say(`No flag named "${args.flagA || args.a}".`); return }
  if (!B2) { say(`No flag named "${args.flagB || args.b}".`); return }
  let block = B(args.block || args.material, '')
  if (!block) {
    const k = B(args.kindOrBlock, '')
    const kindMap = { wall: 'stone_bricks', rampart: 'stone_bricks', barrier: 'stone_bricks', fence: 'oak_fence', hedge: 'oak_leaves' }
    block = kindMap[k] || k || 'stone_bricks'
  }
  const n = wallBetween({ x: A.x, y: A.y, z: A.z }, { x: B2.x, y: B2.y, z: B2.z },
    { block, height: clamp(args.height, 1, 24, 5), thickness: clamp(args.thickness, 1, 6, 1) }, hands)
  await queueIdle()
  log(`build_between ${A.name}->${B2.name} block=${block} ~${n} blocks`)
  say(args.reply || `Wall up between ${A.name} and ${B2.name}.`)
}

// ---- SCHEMATIC LIBRARY: save / list / rebuild / delete ----
async function runSaveBuild(args) {
  const rec = memory.active()
  if (!rec || !rec.blueprint) { say('Nothing to save yet — build something first.'); return }
  const name = args.name || rec.project || 'build'
  try {
    const meta = await library.save(name, rec.blueprint, { project: rec.project, goal: rec.goal, origin: rec.origin })
    say(`Saved "${name}" (${meta.regions} regions) to the library.`)
  } catch (e) { log('save_build err', e.message); say('Could not save that build.') }
}

function runListBuilds() {
  const b = library.list()
  say(b.length ? 'Saved builds: ' + b.map(x => x.name).join(', ') : 'No saved builds yet.')
}

async function runBuildSaved(args, speaker) {
  const name = args.name
  const bp = library.load(name)
  if (!bp) { say(`No saved build called "${name}".`); return }
  const origin0 = resolveOrigin(args, speaker)
  const site = survey.surveySite(bot, origin0, 20)
  const origin = { x: origin0.x, y: site.groundY + 1, z: origin0.z }
  library.reanchor(bp, origin)
  say('Rebuilding that now…')   // ONE start line
  let summary
  try {
    summary = await build.planAndBuild(
      { blueprint: bp, origin, site, terrainFit: 'follow', emit: true,
        statePath: path.join(BUILD_OUT, 'state.json'), schemPath: path.join(BUILD_OUT, 'last.schem') },
      { architect, bot, hands, survey, log, memory })
  } catch (e) { log('build_saved err', e.stack || e.message); say('Trouble rebuilding that — try again?'); return }
  await queueIdle()
  log(`build_saved "${name}" -> ${summary.project} regions=${summary.regionCount} jobs=${summary.jobs} placed=${summary.placed} additive=${summary.additive}`)
  memory.save({ project: summary.project, origin, palette: summary.blueprint.palette, phasesDone: summary.regions, goal: `saved:${name}`, blueprint: summary.blueprint })
  say(`Done — ${summary.project} rebuilt here.`)   // ONE finish line
}

function runDeleteBuild(args) {
  say(library.remove(args.name) ? `Deleted "${args.name}" from the library.` : `No saved build called "${args.name}".`)
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
    const runDefaults = { origin: state.plan.origin, palette: state.plan.palette }
    for (const step of ph.steps) { const r = skills.run(step, runDefaults); if (r.ok) { state.okSteps++; phaseEmit += (r.n || 1) } else state.failSteps++ }
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
        for (const step of ph.steps) skills.run(step, runDefaults); await queueIdle()
      } else log(`phase "${ph.phase}" ok (read ${before.solid}->${after.solid}, emitted ${phaseEmit} ops)`)
    }
    state.done.push(ph.phase)
    await sleep(400)   // deliberate pacing between sections (silent)
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

// Validate + sanitize every block name/state in a blueprint against the real block
// registry (auto-repair pass). Unknown blocks fall back to the nearest palette role
// so a hallucinated block never breaks the build. Keeps blockstate suffixes intact.
function sanitizePlan(bp) {
  let mcData; try { mcData = require('minecraft-data')(bot.version) } catch { return 0 }
  const known = mcData.blocksByName || {}
  const P = resolvePalette(bp.palette)
  const roleFor = (key) => {
    if (/roof/.test(key)) return P.roof
    if (/floor/.test(key)) return P.floor
    if (/trim/.test(key)) return P.trim
    if (/accent/.test(key)) return P.accent
    if (/glass|window/.test(key)) return P.glass
    if (/light|lantern|torch/.test(key)) return P.light
    return P.wall
  }
  let fixed = 0
  const fix = (val, key) => {
    if (typeof val !== 'string') return val
    const clean = val.toLowerCase().replace(/^minecraft:/, '').trim()
    const base = clean.replace(/\[.*$/, '').replace(/[^a-z0-9_]/g, '')
    const state = clean.includes('[') ? clean.slice(clean.indexOf('[')) : ''
    if (base && known[base]) return val
    // a plain "<name>" that only exists as "<name>_block" (e.g. quartz -> quartz_block)
    if (base && known[base + '_block']) { fixed++; return base + '_block' + state }
    const repl = P[key] || roleFor(key)
    fixed++
    return String(repl) + state
  }
  for (const ph of bp.phases || []) for (const st of ph.steps || []) {
    const a = st.args || {}
    for (const k of ['block', 'material', 'roof_material', 'floor', 'trim', 'wall', 'accent', 'glass', 'light', 'frame', 'sill']) {
      if (a[k] != null) a[k] = fix(a[k], k)
    }
  }
  if (bp.palette && typeof bp.palette === 'object') for (const [k, v] of Object.entries(bp.palette)) bp.palette[k] = fix(v, k)
  return fixed
}

// plan_build → AGENT-NATIVE PIPELINE: survey → Architect authors a Layer-A
// semantic blueprint → anchor to terrain → compile → additive diff vs the live
// world → godmode realize (only the differing cells). Non-destructive: nothing
// outside the design is touched. Preserves quiet mode + the single start/finish
// chat lines. Also emits a shared state.json + .schem under build-out/ (NEVER
// Codex's live state.json) for interop.
async function runProject(args, speaker) {
  // If this build answers a pending clarifying question, reuse the origin captured
  // when we asked (the user may be looking elsewhere now), unless they named an
  // explicit flag/coords this turn.
  let origin0
  if (pendingBuild && pendingBuild.origin && args.origin !== 'flag' && args.x == null) origin0 = pendingBuild.origin
  else origin0 = resolveOrigin(args, speaker)
  pendingBuild = null
  // 1) SURVEY the terrain before planning. (silent)
  const site = survey.surveySite(bot, origin0, 20)
  log('site survey', JSON.stringify(site))
  const origin = { x: origin0.x, y: site.groundY + 1, z: origin0.z }

  say('Building that now…')   // the ONE start line for a multi-minute build
  let summary
  try {
    summary = await build.planAndBuild(
      { goal: args.goal || 'build something impressive', origin, memory: memory.context(), site,
        terrainFit: 'follow', emit: true,
        statePath: path.join(BUILD_OUT, 'state.json'), schemPath: path.join(BUILD_OUT, 'last.schem') },
      { architect, bot, hands, survey, log, memory })
  } catch (e) { log('build err', e.stack || e.message); say('Trouble building that — try again?'); return }

  log(`blueprint "${summary.project}" regions=${summary.regionCount} jobs=${summary.jobs} placed=${summary.placed} air=${summary.air} skipped=${summary.skipped} materials=${summary.materialCount} additive=${summary.additive}`)
  if (summary.filled) log(`  realized: ${summary.filled.fills} /fill + ${summary.filled.sets} /setblock = ${summary.filled.ops} ops`)
  await queueIdle()

  // Persist the blueprint so edit_build follow-ups recompile the SAME build.
  memory.save({ id: memory.active()?.id, project: summary.project, origin, palette: summary.blueprint.palette, phasesDone: summary.regions, goal: args.goal, blueprint: summary.blueprint })
  say(`Done — ${summary.project} is up.`)   // the ONE finish line
}

// edit_build → incremental, non-destructive edit of the active blueprint.
async function runEdit(args, speaker) {
  const rec = memory.active()
  if (!rec || !rec.blueprint) { say("I don't have an active build to edit — build something first."); return }
  const op = { type: args.op, side: args.side, amount: args.amount, dy: args.amount, regionId: args.region }
  say('On it…')
  let s
  try { s = await build.editBuild(op, rec.blueprint, { bot, hands, survey, log }) }
  catch (e) { log('edit err', e.stack || e.message); say('Could not make that change.'); return }
  await queueIdle()
  log(`edit ${args.op} -> touched ${(s.touched || []).join(', ')} jobs=${s.jobs} placed=${s.placed}`)
  memory.save({ id: rec.id, project: s.project, origin: rec.origin, palette: s.blueprint.palette, phasesDone: s.regions, goal: rec.goal, blueprint: s.blueprint })
  say(`Done — ${(s.touched || ['build']).join(', ')} updated.`)
}

bot.on('kicked', r => log('KICKED', r)); bot.on('error', e => log('ERR', e.message)); bot.on('end', () => log('END'))
