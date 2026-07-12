const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder')
const Vec3 = require('vec3')

const DIR = __dirname
const KEY = (fs.readFileSync(path.join(DIR, '.env'), 'utf8').match(/XAI_API_KEY=(.+)/) || [])[1]?.trim()
const MODEL = process.env.XAI_MODEL || 'grok-4.20-0309-non-reasoning'
const USERNAME = process.env.MC_USER || 'Grok'
// Grok ONLY responds to these people (allowlist) — everyone/everything else is ignored.
// Override/extend with env GROK_ALLOW="name1,name2".
const ALLOW = new Set((process.env.GROK_ALLOW || 'claudebert').toLowerCase().split(',').map(s => s.trim()).filter(Boolean))
// For world-state "nearby players", still hide known bots.
const BOTNAMES = /^(Grok|Overseer|Assistant|Tester|Codex.*|.*Drone\d+|ViscousVermin\d+|CodexSwarm\d+)$/i

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

// ---- throttled server-command queue (Grok is opped → /fill, /setblock, etc. run instantly) ----
const cmdQueue = []
let draining = false
function enqueue(cmd) { cmdQueue.push(cmd); drain() }
async function drain() { if (draining) return; draining = true; while (cmdQueue.length) { try { bot.chat(cmdQueue.shift()) } catch {} await sleep(130) } draining = false }
function sort2(a, b) { return [Math.min(a, b), Math.max(a, b)] }
function fillBox(x1, y1, z1, x2, y2, z2, block) {
  [x1, x2] = sort2(x1, x2);[y1, y2] = sort2(y1, y2);[z1, z2] = sort2(z1, z2)
  y1 = Math.max(-64, y1); y2 = Math.min(319, y2)
  let n = 0
  for (let x = x1; x <= x2; x += 32) { const xe = Math.min(x + 31, x2)
    for (let y = y1; y <= y2; y += 32) { const ye = Math.min(y + 31, y2)
      for (let z = z1; z <= z2; z += 32) { const ze = Math.min(z + 31, z2)
        enqueue(`/fill ${x} ${y} ${z} ${xe} ${ye} ${ze} ${block}`); n++ } } }
  return n
}

const SYS = `You are ${USERNAME}, a friendly, genuinely helpful AI assistant in a Minecraft world (creative, 1.21.6). You have OPERATOR (godmode) powers, so big edits are instant. Players talk to you in natural language; interpret and act, answer, or chat.
You get the player's message plus a JSON world-state (your position, nearby players/entities/resources, inventory, biome, and speakerLookingAt = the block coordinates the speaking player is currently looking at, if known).
Respond with ONLY a JSON object:
{"reply":"<short natural message or empty>","action":"<one action>","args":{...}}
MOVEMENT:
- "come"/"follow": walk to & follow the speaker (or args.player)
- "stop": hold position
- "goto": go to args.x,args.z (args.y optional)
- "explore": wander somewhere new
- "tp": teleport yourself to the speaker (or args.x,y,z) — "get over here fast"
- "bring": teleport the SPEAKER to you (or args.player to you) — "bring me to you"
GODMODE EDITS (instant, need op). Every edit takes an origin: args.origin="look" (where speaker looks — DEFAULT when they say "here"), "me" (speaker), or explicit args.x/args.y/args.z.
- "clear": clear a big area to air. args.radius (1-60), args.height (1-60).
- "flatten": clear + lay a flat floor (args.floor block, default grass_block).
- "fill": fill a box with a block. args.block, args.radius, args.height.
- "set": set a single block. args.block; target = args.here (block speaker looks at) or args.x/y/z.
- "dig": remove a block. args.block type (nearest) OR args.here=true (block speaker looks at).
- "wall": build a wall. args.length, args.height, args.block, args.axis ("x" or "z").
- "platform": flat platform (disc). args.radius, args.block (default stone).
- "tower": pillar args.n tall (1-40) where you stand, args.block (default cobblestone).
- "room": a hollow building shell (walls+floor+roof, a door, windows). args.width, args.length, args.height, args.block (walls), args.floor. Use for a plain room.
- "build": construct a REAL structure (NOT a plain box — use this, never "fill", for named builds). args.structure = house|tower|round_tower|wall|bridge|road|pyramid|dome|platform|castle (aliases: cabin,hut,cottage,watchtower,keep,fort,fortress,igloo,path). Optional: size/width/length/height/radius, material (block name), roof_material, axis ("x"|"z"). Origin via look/me/x,z. Use for "build a castle/house/tower/wall/bridge/pyramid here".
WORLD (godmode):
- "time": args.value = "day"|"night"|"noon"|"midnight".
- "weather": args.value = "clear"|"rain"|"thunder".
- "give": give the speaker items. args.item, args.count.
- "idle": no action — pure conversation / answering questions (answer in "reply").
Use speakerLookingAt as origin when they say "here"/"where I'm looking". Answer "where are you / what's nearby / what do you have / what can you do" from world-state with action "idle". Pick sensible default sizes if unspecified. Keep replies under ~15 words, warm.`

bot.once('spawn', () => {
  log('spawned at', bot.entity.position)
  const m = new Movements(bot); m.allowSprinting = true; m.canDig = false
  bot.pathfinder.setMovements(m)
  bot.chat(`${USERNAME} here (godmode on) — try "clear a big area where I'm looking" or "follow me"`)
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
  const cats = { wood: ['oak_log','birch_log','spruce_log','jungle_log','acacia_log','dark_oak_log','mangrove_log'], stone: ['stone'], coal: ['coal_ore'], iron: ['iron_ore'], water: ['water'], sand: ['sand'] }
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
  return { position: { x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) }, biome, health: bot.health, food: bot.food, timeOfDay: bot.time.timeOfDay, players, entities, inventory: bot.inventory.items().map(i => `${i.name} x${i.count}`).slice(0, 12), resourcesNearby: resources(), following: followTarget, speakerLookingAt: speaker ? lookTarget(speaker) : null }
}

async function askGrok(from, message) {
  const payload = { from, message, world: world(from), recentChat: history.slice(-8) }
  const res = await fetch('https://api.x.ai/v1/chat/completions', { method: 'POST', headers: { 'Authorization': `Bearer ${KEY}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ model: MODEL, temperature: 0.5, max_tokens: 220, messages: [{ role: 'system', content: SYS }, { role: 'user', content: JSON.stringify(payload) }] }) })
  if (!res.ok) throw new Error(`xai ${res.status}: ${(await res.text()).slice(0, 100)}`)
  let txt = (await res.json()).choices[0].message.content.trim().replace(/^```(json)?/i, '').replace(/```$/, '').trim()
  return JSON.parse(txt)
}

bot.on('chat', async (username, message) => {
  if (!ALLOW.has(username.toLowerCase())) return   // only listen to allowlisted people
  history.push(`${username}: ${message}`); while (history.length > 12) history.shift()
  if (busy) return
  busy = true
  let res; try { res = await askGrok(username, message) } catch (e) { log('grok err', e.message); busy = false; return }
  busy = false
  log(`<${username}> ${message}  ->  ${JSON.stringify(res)}`)
  if (res.reply) bot.chat(String(res.reply).slice(0, 140))
  execute(res, username)
})

function resolveOrigin(args, speaker) {
  if (args.origin === 'look' || args.here) { const l = lookTarget(speaker); if (l) return l }
  if (args.x != null && args.z != null) return { x: +args.x, y: args.y != null ? +args.y : Math.round(bot.entity.position.y), z: +args.z }
  const e = bot.players[speaker]?.entity
  if (args.origin === 'me' && e) return { x: Math.round(e.position.x), y: Math.round(e.position.y), z: Math.round(e.position.z) }
  const l = lookTarget(speaker); if (l) return l
  if (e) return { x: Math.round(e.position.x), y: Math.round(e.position.y), z: Math.round(e.position.z) }
  const p = bot.entity.position; return { x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) }
}

function execute(res, speaker) {
  const a = res.action || 'idle'; const args = res.args || {}; const p = bot.entity.position
  const spEnt = bot.players[speaker]?.entity
  switch (a) {
    case 'come': case 'follow': followTarget = args.player || speaker; { const e = bot.players[followTarget]?.entity; if (e) bot.pathfinder.setGoal(new goals.GoalFollow(e, 2), true) } break
    case 'stop': followTarget = null; bot.pathfinder.setGoal(null); bot.clearControlStates(); break
    case 'goto': if (args.x != null && args.z != null) { followTarget = null; bot.pathfinder.setGoal(new goals.GoalNear(+args.x, args.y != null ? +args.y : p.y, +args.z, 1)) } break
    case 'explore': followTarget = null; bot.pathfinder.setGoal(new goals.GoalNear(Math.floor(p.x + (Math.random() * 40 - 20)), p.y, Math.floor(p.z + (Math.random() * 40 - 20)), 1)); break
    case 'tp': { const o = (args.x != null) ? { x: +args.x, y: +args.y, z: +args.z } : (spEnt ? { x: spEnt.position.x, y: spEnt.position.y, z: spEnt.position.z } : null); if (o) enqueue(`/tp ${USERNAME} ${o.x.toFixed(1)} ${o.y.toFixed(1)} ${o.z.toFixed(1)}`) } break
    case 'tower': { const n = Math.min(Math.max(parseInt(args.n || 5, 10), 1), 40); fillBox(Math.round(p.x), Math.round(p.y), Math.round(p.z), Math.round(p.x), Math.round(p.y) + n, Math.round(p.z), 'cobblestone') } break
    case 'dig': { const o = resolveOrigin({ here: args.here, origin: args.here ? 'look' : args.origin }, speaker); if (args.block && !args.here) { const mcData = require('minecraft-data')(bot.version); const id = mcData.blocksByName[String(args.block).toLowerCase()]?.id; const b = id != null ? bot.findBlock({ matching: id, maxDistance: 64 }) : null; if (b) enqueue(`/setblock ${b.position.x} ${b.position.y} ${b.position.z} air`); else bot.chat(`no ${args.block} nearby`) } else if (o) enqueue(`/setblock ${o.x} ${o.y} ${o.z} air`) } break
    case 'clear': { const o = resolveOrigin(args, speaker); const r = Math.min(Math.max(parseInt(args.radius || 12, 10), 1), 60); const h = Math.min(Math.max(parseInt(args.height || 20, 10), 1), 60); const n = fillBox(o.x - r, o.y, o.z - r, o.x + r, o.y + h, o.z + r, 'air'); bot.chat(`clearing a ${2 * r + 1}-wide area (${n} fills)`) } break
    case 'flatten': { const o = resolveOrigin(args, speaker); const r = Math.min(Math.max(parseInt(args.radius || 12, 10), 1), 60); const h = Math.min(Math.max(parseInt(args.height || 20, 10), 1), 60); const floor = B(args.floor, 'grass_block'); fillBox(o.x - r, o.y, o.z - r, o.x + r, o.y + h, o.z + r, 'air'); fillBox(o.x - r, o.y - 1, o.z - r, o.x + r, o.y - 1, o.z + r, floor); bot.chat(`flattening a ${2 * r + 1}-wide platform`) } break
    case 'bring': { const who = args.player || speaker; enqueue(`/tp ${who} ${USERNAME}`); } break
    case 'fill': { const o = resolveOrigin(args, speaker); const r = clamp(args.radius, 1, 60, 6); const h = clamp(args.height, 0, 60, 4); const blk = B(args.block, 'stone'); fillBox(o.x - r, o.y, o.z - r, o.x + r, o.y + h, o.z + r, blk); bot.chat(`filling a ${2 * r + 1}-wide box with ${blk}`) } break
    case 'set': { const o = resolveOrigin({ here: args.here, origin: args.here ? 'look' : args.origin, x: args.x, y: args.y, z: args.z }, speaker); enqueue(`/setblock ${o.x} ${o.y} ${o.z} ${B(args.block, 'stone')}`) } break
    case 'wall': { const o = resolveOrigin(args, speaker); const len = clamp(args.length, 1, 128, 10); const h = clamp(args.height, 1, 40, 4); const blk = B(args.block, 'stone'); if ((args.axis || 'x') === 'z') fillBox(o.x, o.y, o.z, o.x, o.y + h - 1, o.z + len, blk); else fillBox(o.x, o.y, o.z, o.x + len, o.y + h - 1, o.z, blk); bot.chat(`building a ${len}-long, ${h}-tall wall`) } break
    case 'platform': { const o = resolveOrigin(args, speaker); const r = clamp(args.radius, 1, 60, 8); const blk = B(args.block, 'stone'); fillBox(o.x - r, o.y - 1, o.z - r, o.x + r, o.y - 1, o.z + r, blk); bot.chat(`platform down, ${2 * r + 1} wide`) } break
    case 'room': buildRoom(resolveOrigin(args, speaker), args); break
    case 'build': case 'house': case 'castle': {
      const o = resolveOrigin(args, speaker)
      let s = String(args.structure || (a === 'build' ? 'house' : a)).toLowerCase().replace(/[^a-z_]/g, '')
      const ST = require('./structures')({ enqueue, fillBox, B, clamp, set: (x, y, z, b) => enqueue(`/setblock ${x} ${y} ${z} ${b}`) })
      const gen = ST.gens[s] || ST.gens[ST.alias[s]]
      if (!gen) { bot.chat(`not sure how to build "${s}" — try house, tower, castle, wall, bridge, pyramid, dome`); break }
      try { gen(o, args) } catch (e) { log('build err', e.message); bot.chat('hit a snag building that') }
      break
    }
    case 'time': { const v = ({ day: 'day', night: 'night', noon: 'noon', midnight: 'midnight' })[String(args.value || 'day').toLowerCase()] || 'day'; enqueue(`/time set ${v}`); } break
    case 'weather': { const v = ({ clear: 'clear', rain: 'rain', thunder: 'thunder' })[String(args.value || 'clear').toLowerCase()] || 'clear'; enqueue(`/weather ${v}`); } break
    case 'give': { const who = args.player || speaker; enqueue(`/give ${who} ${B(args.item, 'diamond')} ${clamp(args.count, 1, 64, 1)}`); } break
    case 'idle': default: break
  }
}

const clamp = (v, lo, hi, def) => { const n = parseInt(v, 10); return isNaN(n) ? def : Math.min(Math.max(n, lo), hi) }
const B = (s, def) => { const x = String(s || '').toLowerCase().replace(/^minecraft:/, '').replace(/[^a-z0-9_]/g, ''); return x || def }

function buildRoom(o, args) {
  const w = clamp(args.width, 3, 40, 7), l = clamp(args.length, 3, 40, 7), h = clamp(args.height, 3, 20, 5)
  const blk = B(args.block, 'oak_planks'), floor = B(args.floor, blk)
  const x1 = o.x - Math.floor(w / 2), x2 = o.x + Math.floor(w / 2)
  const z1 = o.z - Math.floor(l / 2), z2 = o.z + Math.floor(l / 2)
  const y0 = o.y, y1 = o.y + h
  fillBox(x1, y0, z1, x2, y1, z2, blk)              // solid shell
  fillBox(x1 + 1, y0 + 1, z1 + 1, x2 - 1, y1 - 1, z2 - 1, 'air') // hollow interior (keeps floor/roof/walls)
  if (floor !== blk) fillBox(x1 + 1, y0, z1 + 1, x2 - 1, y0, z2 - 1, floor)
  enqueue(`/setblock ${o.x} ${y0 + 1} ${z2} air`); enqueue(`/setblock ${o.x} ${y0 + 2} ${z2} air`) // doorway
  enqueue(`/setblock ${o.x} ${y0 + 1} ${z2 - 1} oak_door[half=lower,facing=north]`)
  enqueue(`/setblock ${x1} ${y0 + 2} ${o.z} glass`); enqueue(`/setblock ${x2} ${y0 + 2} ${o.z} glass`); enqueue(`/setblock ${o.x} ${y0 + 2} ${z1} glass`) // windows
  enqueue(`/setblock ${o.x} ${y0 + 1} ${o.z} torch`) // a light inside
  bot.chat(`built a ${w}x${l} room, come check it out!`)
}

bot.on('kicked', r => log('KICKED', r)); bot.on('error', e => log('ERR', e.message)); bot.on('end', () => log('END'))
