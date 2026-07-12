const fs = require('fs')
const path = require('path')

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

// ---- TRANSPORT: mineflayer (DEFAULT, vanilla protocol) or bridge (mod-side HTTP,
// runs on a MODDED server with no vanilla protocol). Abstracts chat in/out,
// look-target, position, hands, move/tp, world-state + status; the router,
// architect, skills, and build pipeline below are transport-agnostic + shared. ----
const TRANSPORT = String(process.env.GROK_TRANSPORT || 'mineflayer').toLowerCase()
const makeTransport = require('./lib/transport/' + (TRANSPORT === 'bridge' ? 'bridge' : 'mineflayer'))
const transport = makeTransport({ USERNAME, BOTNAMES, log })
const hands = transport.hands
const enqueue = transport.enqueue
const queueIdle = transport.queueIdle
const bot = transport.bot            // mineflayer bot, or null in bridge mode

const history = []
let busy = false
// Interrupt flag: set true by the STOP fast-path; build loops (runProject +
// realize() + the transport queues) check it and bail. Reset false at the start
// of each new build so the next request runs normally.
let aborted = false
// A vague build we asked a clarifying question about, awaiting the user's answer.
// { goal: <original request>, origin: {x,y,z}, asked: true }
let pendingBuild = null

// ---- helpers shared with skill modules ----
const clamp = (v, lo, hi, def) => { const n = parseInt(v, 10); return isNaN(n) ? def : Math.min(Math.max(n, lo), hi) }
const B = (s, def) => { const x = String(s || '').toLowerCase().replace(/^minecraft:/, '').replace(/[^a-z0-9_]/g, ''); return x || def }

// ---- skill libraries (all route through hands, never raw commands) ----
const sctx = { enqueue, fillBox: hands.fillBox.bind(hands), B, clamp, set: (x, y, z, b) => hands.setBlock(x, y, z, b) }
const structures = require('./structures')(sctx)
const details = require('./detail')(sctx)
const furniture = require('./furniture')(sctx)
const makeSkills = require('./lib/skills')
const skills = makeSkills({ hands, structures, details, furniture, B, clamp, log })

// ---- architect + project memory ----
const architect = require('./lib/architect')({ skills, log })
const memory = require('./lib/memory')(path.join(DIR, 'memory'))
const survey = require('./lib/survey')

// ---- agent-native build pipeline (semantic blueprint → additive, site-aware
// compiler → shared Codex job state). plan_build + edits route through here. ----
const build = require('./lib/build')
const BUILD_OUT = path.join(DIR, 'build-out')   // our own interop dir; NEVER Codex's live state.json

// ---- named FLAGS (spatial markers) + REGIONS + SCHEMATIC LIBRARY (save/reuse builds) ----
// Flags/regions live in the SHARED server-side store when the mod bridge is
// reachable (the Layout Wand writes there too), with a local json fallback. Use
// the transport's bridge in bridge mode, else spin one up from env if configured.
const flagBridge = transport.bridge || (process.env.BUBBLESKY_BRIDGE_URL || process.env.BUBBLESKY_BRIDGE_TOKEN ? require('./lib/bridge')() : null)
const flags = require('./lib/flags')({ dir: path.join(DIR, 'memory'), enqueue, hands, log, bridge: flagBridge })
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
  { type: 'function', function: { name: 'build_in_region', description: 'Build INSIDE a named REGION defined with the Layout Wand ("build a castle in region 1", "put a keep in Region 2"). Resolves the region rectangle from the shared store, anchors the build to its corner, and sizes the footprint to the region. Routes through the Architect (furnished, detailed).',
    parameters: { type: 'object', properties: { region: { type: 'string', description: 'region name (e.g. "region 1" or "Region 2")' }, goal: { type: 'string', description: 'what to build, in the user\'s words' }, reply: { type: 'string' } }, required: ['region', 'goal'] } } },
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
- REGIONS (rectangles defined with the Layout Wand): "build a castle in region 1" / "put a keep in Region 2" → build_in_region (region="region 1", goal=the build request). The build is anchored to the region and sized to fit it.
- SAVED BUILDS (schematic library): "save this as <name>" / "save this build" → save_build. "list builds" / "what have I saved" → list_builds. "build <saved name> here" / "place my <name> here" → build_saved (origin "look" for "here"). "delete build <name>" → delete_build. After build_saved, "make it taller / add a tower" → edit_build as usual.
- Movement/teleport → move. Time/weather/give → world_cmd.
- Questions & chit-chat ("where are you", "what's nearby", "what can you do") → answer, using world-state.
CLARIFYING QUESTIONS (build requests only):
- If a build request is VAGUE / open-ended ("build a cozy cottage", "build a village", "make something cool") and 1-2 short questions about size / style / theme / location would materially help, call ask (put the ORIGINAL request in "goal", the questions in "reply", origin "look" for "here"). Keep it minimal & natural — never interrogate, max 2 questions, and only when it genuinely helps.
- A SPECIFIC request ("build a 9x9 stone tower here") must NOT trigger ask — build it directly.
- If your LAST turn asked a clarifying question (see the conversation history), treat the user's reply as the ANSWER: proceed to build with plan_build using the ORIGINAL request + their answer (combined into "goal"). Do NOT ask again.
Default origin to "look" when they say "here" or "where I'm looking". Pick sensible sizes if unspecified. Put a short (<15 words), warm message in the tool's "reply".`

// Transport-agnostic look-target ("here" = block the speaker looks at).
function lookTarget(username) { return transport.lookTarget(username) }

function world(speaker) {
  return transport.world(speaker, { activeProject: memory.context() })
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

// Words that INTERRUPT an in-progress build/movement (fast-path, no LLM).
const STOP_RE = /\b(stop|cancel|halt|abort|nvm|nevermind|freeze|stop building)\b/i

// Main chat handler — shared across transports (mineflayer 'chat' event or bridge
// /chat polling both feed it (username, message)). Router + dispatch are shared.
async function onChat(username, message) {
  if (!ALLOW.has(username.toLowerCase())) return
  // ---- STOP FAST-PATH (before the LLM, and works even when busy===true) ----
  // Clearing the command queue is the INSTANT stop — it's what floods /fill+/setblock.
  // The `aborted` flag then stops the build loop from re-filling it.
  if (STOP_RE.test(message)) {
    aborted = true
    transport.stop()             // clears the cmd/op queue + stops pathfinding
    history.push(`${username}: ${message}`); while (history.length > 30) history.shift()
    say('Stopped.')              // one line, allowed under quiet mode
    transport.status({ activity: 'idle', detail: 'stopped' })
    busy = false                 // release any in-progress turn's lock
    return
  }
  // Refresh the transport's per-speaker snapshot (bridge: fetch /player so "here"
  // + position resolve synchronously below; mineflayer: no-op).
  if (transport.refresh) { try { await transport.refresh(username) } catch (e) { log('refresh err', e.message) } }
  // Pull the shared server-side flags/regions into the local cache so wand-planted
  // markers resolve the same as chat-planted ones (no-op when the bridge is off).
  try { await flags.sync() } catch (e) { log('flags sync err', e.message) }
  history.push(`${username}: ${message}`); while (history.length > 30) history.shift()
  if (busy) return
  busy = true
  transport.status({ activity: 'thinking', detail: message.slice(0, 60) })
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
  transport.status({ activity: 'idle', detail: '' })
}

const say = s => { if (s) { transport.say(String(s).slice(0, 200)); history.push(`${USERNAME}: ${s}`); while (history.length > 30) history.shift() } }  // remember our own replies too

function resolveOrigin(args, speaker) {
  if (args.origin === 'flag' && args.flag) { const f = flags.get(args.flag); if (f) return { x: f.x, y: f.y, z: f.z } }
  if (args.here || args.origin === 'look') { const l = lookTarget(speaker); if (l) return round(l) }
  if (args.x != null && args.z != null) return { x: Math.round(+args.x), y: args.y != null ? Math.round(+args.y) : transport.selfPos().y, z: Math.round(+args.z) }
  if (args.origin === 'me') { const sp = transport.speakerPos(speaker); if (sp) return round(sp) }
  const l = lookTarget(speaker); if (l) return round(l)
  const sp = transport.speakerPos(speaker); if (sp) return round(sp)
  return round(transport.selfPos())
}
const round = p => ({ x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) })

async function dispatch(tool, args, speaker, message) {
  // Grok is QUIET about the *work* — no per-block/per-phase spam (that flood came from
  // the sendCommandFeedback gamerule, now disabled). But it STILL speaks the one short
  // acknowledgement line the model wrote, so it never feels mute to a command.
  // `answer`/`ask` say their own reply below; plan_build emits its own start/finish lines.
  if (args.reply && !SELF_REPLY.has(tool)) say(args.reply)
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
    case 'move': transport.move(args, speaker, { say, username: USERNAME }); break
    case 'world_cmd': {
      if (args.cmd === 'time') hands.setTime(String(args.value || 'day').toLowerCase())
      else if (args.cmd === 'weather') hands.setWeather(String(args.value || 'clear').toLowerCase())
      else if (args.cmd === 'give') hands.give(args.player || speaker, B(args.item, 'diamond'), clamp(args.count, 1, 64, 1))
      break
    }
    case 'set_block': { const o = resolveOrigin(args, speaker); hands.setBlock(o.x, o.y, o.z, args.block || 'stone'); break }
    case 'dig': {
      if (args.block && !args.here) { const b = transport.digNearest(B(args.block, '')); if (b) hands.dig(b.x, b.y, b.z); else say(`no ${args.block} nearby`) }
      else { const o = resolveOrigin({ here: true, x: args.x, y: args.y, z: args.z }, speaker); hands.dig(o.x, o.y, o.z) }
      break
    }
    case 'fill_box': { const o = resolveOrigin(args, speaker); const r = clamp(args.radius, 1, 60, 6), h = clamp(args.height, 0, 60, 4); hands.fillBox(o.x - r, o.y, o.z - r, o.x + r, o.y + h, o.z + r, args.block || 'stone'); break }
    case 'clear_area': { const o = resolveOrigin(args, speaker); const r = clamp(args.radius, 1, 60, 12), h = clamp(args.height, 1, 60, 20); hands.clearArea(o.x, o.y, o.z, r, h); break }
    case 'flatten': { const o = resolveOrigin(args, speaker); const r = clamp(args.radius, 1, 60, 12), h = clamp(args.height, 1, 60, 12); hands.fillBox(o.x - r, o.y, o.z - r, o.x + r, o.y + h, o.z + r, 'air'); hands.fillBox(o.x - r, o.y - 1, o.z - r, o.x + r, o.y - 1, o.z + r, B(args.floor, 'grass_block')); break }
    case 'build_structure': {
      pendingBuild = null
      aborted = false               // fresh build → clear any prior interrupt
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
    case 'build_in_region': await runBuildInRegion(args, speaker); break
    case 'save_build': await runSaveBuild(args); break
    case 'list_builds': runListBuilds(); break
    case 'build_saved': await runBuildSaved(args, speaker); break
    case 'delete_build': runDeleteBuild(args); break
    default: log('unknown tool', tool)
  }
}

// Tools that speak their OWN reply line(s) below (so dispatch doesn't double-say).
const SELF_REPLY = new Set(['answer', 'ask', 'plan_build', 'edit_build', 'flag', 'build_between', 'build_in_region', 'save_build', 'list_builds', 'build_saved', 'delete_build'])

// ---- FLAGS: set / list / remove / goto ----
async function runFlag(args, speaker) {
  const op = String(args.op || 'set').toLowerCase()
  const dim = transport.dimension()
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

// ---- build INSIDE a Layout-Wand region ----
// Resolve the region rectangle from the shared store, anchor the build to its
// min corner, and size the footprint to the region (honoring parseDims via the
// goal), then run the normal Architect pipeline so it stays inside the bounds.
async function runBuildInRegion(args, speaker) {
  const region = flags.getRegion(args.region)
  if (!region) { say(`No region named "${args.region}". Define one with the Layout Wand first.`); return }
  const a = region.a, b = region.b
  const x0 = Math.min(a.x, b.x), x1 = Math.max(a.x, b.x)
  const y0 = Math.min(a.y, b.y)
  const z0 = Math.min(a.z, b.z), z1 = Math.max(a.z, b.z)
  const spanW = x1 - x0 + 1, spanL = z1 - z0 + 1
  // Footprint sized to fit inside the rectangle with a margin for the Architect's
  // corner towers / banners / entrance overhang (~3 blocks/side), then CENTERED in
  // the region (composers treat origin as the min corner, so shift back by W/2).
  const MARGIN = 6
  const W = Math.max(6, spanW - MARGIN), L = Math.max(6, spanL - MARGIN)
  const cx = (x0 + x1) / 2, cz = (z0 + z1) / 2
  const origin = { x: Math.round(cx - W / 2), y: y0, z: Math.round(cz - L / 2) }
  aborted = false
  pendingBuild = null
  const site = transport.surveySite(origin, 20)
  const originF = { x: origin.x, y: site.groundY, z: origin.z }
  const goal = `${args.goal || 'a fitting structure'} (${W}x${L})`
  say(args.reply || `Building that in ${region.name} now…`)
  transport.status({ activity: 'building', detail: `${region.name}: ${(args.goal || '').slice(0, 40)}` })
  let summary
  try {
    summary = await build.planAndBuild(
      { goal, origin: originF, memory: memory.context(), site,
        terrainFit: 'follow', emit: true,
        statePath: path.join(BUILD_OUT, 'state.json'), schemPath: path.join(BUILD_OUT, 'last.schem') },
      { architect, bot, hands, survey, log, memory, shouldAbort: () => aborted })
  } catch (e) { log('build_in_region err', e.stack || e.message); say('Trouble building in that region — try again?'); return }
  await queueIdle()
  if (aborted) return
  log(`build_in_region ${region.name} (${W}x${L}) "${summary.project}" placed=${summary.placed}`)
  memory.save({ project: summary.project, origin: originF, palette: summary.blueprint.palette, phasesDone: summary.regions, goal, blueprint: summary.blueprint })
  say(args.reply ? `Done — ${summary.project} up in ${region.name}.` : `Done — ${summary.project} is up in ${region.name}.`)
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
  aborted = false                 // fresh build → clear any prior interrupt
  const origin0 = resolveOrigin(args, speaker)
  const site = transport.surveySite(origin0, 20)
  const origin = { x: origin0.x, y: site.groundY + 1, z: origin0.z }
  library.reanchor(bp, origin)
  say('Rebuilding that now…')   // ONE start line
  transport.status({ activity: 'building', detail: String(name).slice(0, 60) })
  let summary
  try {
    summary = await build.planAndBuild(
      { blueprint: bp, origin, site, terrainFit: 'follow', emit: true,
        statePath: path.join(BUILD_OUT, 'state.json'), schemPath: path.join(BUILD_OUT, 'last.schem') },
      { architect, bot, hands, survey, log, memory, shouldAbort: () => aborted })
  } catch (e) { log('build_saved err', e.stack || e.message); say('Trouble rebuilding that — try again?'); return }
  await queueIdle()
  if (aborted) return             // interrupted → the fast-path already said "Stopped."
  log(`build_saved "${name}" -> ${summary.project} regions=${summary.regionCount} jobs=${summary.jobs} placed=${summary.placed} additive=${summary.additive}`)
  memory.save({ project: summary.project, origin, palette: summary.blueprint.palette, phasesDone: summary.regions, goal: `saved:${name}`, blueprint: summary.blueprint })
  say(`Done — ${summary.project} rebuilt here.`)   // ONE finish line
}

function runDeleteBuild(args) {
  say(library.remove(args.name) ? `Deleted "${args.name}" from the library.` : `No saved build called "${args.name}".`)
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
  aborted = false                 // fresh build → clear any prior interrupt
  // 1) SURVEY the terrain before planning. (silent; bridge mode uses a flat assumption)
  const site = transport.surveySite(origin0, 20)
  log('site survey', JSON.stringify(site))
  const origin = { x: origin0.x, y: site.groundY + 1, z: origin0.z }

  say('Building that now…')   // the ONE start line for a multi-minute build
  transport.status({ activity: 'building', detail: (args.goal || 'build').slice(0, 60) })
  let summary
  try {
    summary = await build.planAndBuild(
      { goal: args.goal || 'build something impressive', origin, memory: memory.context(), site,
        terrainFit: 'follow', emit: true,
        statePath: path.join(BUILD_OUT, 'state.json'), schemPath: path.join(BUILD_OUT, 'last.schem') },
      { architect, bot, hands, survey, log, memory, shouldAbort: () => aborted })
  } catch (e) { log('build err', e.stack || e.message); say('Trouble building that — try again?'); return }

  log(`blueprint "${summary.project}" regions=${summary.regionCount} jobs=${summary.jobs} placed=${summary.placed} air=${summary.air} skipped=${summary.skipped} materials=${summary.materialCount} additive=${summary.additive}`)
  if (summary.filled) log(`  realized: ${summary.filled.fills} /fill + ${summary.filled.sets} /setblock = ${summary.filled.ops} ops`)
  await queueIdle()
  if (aborted) return             // interrupted → the fast-path already said "Stopped."

  // Persist the blueprint so edit_build follow-ups recompile the SAME build.
  memory.save({ id: memory.active()?.id, project: summary.project, origin, palette: summary.blueprint.palette, phasesDone: summary.regions, goal: args.goal, blueprint: summary.blueprint })
  say(`Done — ${summary.project} is up.`)   // the ONE finish line
}

// edit_build → incremental, non-destructive edit of the active blueprint.
async function runEdit(args, speaker) {
  const rec = memory.active()
  if (!rec || !rec.blueprint) { say("I don't have an active build to edit — build something first."); return }
  aborted = false                 // fresh edit → clear any prior interrupt
  const op = { type: args.op, side: args.side, amount: args.amount, dy: args.amount, regionId: args.region }
  say('On it…')
  let s
  try { s = await build.editBuild(op, rec.blueprint, { bot, hands, survey, log, shouldAbort: () => aborted }) }
  catch (e) { log('edit err', e.stack || e.message); say('Could not make that change.'); return }
  await queueIdle()
  if (aborted) return             // interrupted → the fast-path already said "Stopped."
  log(`edit ${args.op} -> touched ${(s.touched || []).join(', ')} jobs=${s.jobs} placed=${s.placed}`)
  memory.save({ id: rec.id, project: s.project, origin: rec.origin, palette: s.blueprint.palette, phasesDone: s.regions, goal: rec.goal, blueprint: s.blueprint })
  say(`Done — ${(s.touched || ['build']).join(', ')} updated.`)
}

// ---- start the selected transport: wire the shared chat handler + greeting. ----
const GREETING = `${USERNAME} here (${transport.name}, ${hands.godmode ? 'godmode on' : 'survival'}) — try "build a big detailed castle here"${transport.followsSupported ? ' or "follow me"' : ''}`
Promise.resolve(transport.start({ onChat, greeting: GREETING }))
  .catch(e => { log('transport start failed:', e.message); process.exit(1) })
