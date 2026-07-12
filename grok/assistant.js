const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder')
const Vec3 = require('vec3')

const DIR = __dirname
const KEY = (fs.readFileSync(path.join(DIR, '.env'), 'utf8').match(/XAI_API_KEY=(.+)/) || [])[1]?.trim()
const MODEL = process.env.XAI_MODEL || 'grok-4.20-0309-non-reasoning'
const USERNAME = process.env.MC_USER || 'Grok'
const BOTNAMES = /^(Grok|Overseer|Drone\d+|CodexSwarm\d+|codex|ViscousVermin\d+|Claude|Tester|Assistant)$/i

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
Actions:
- "come"/"follow": walk to & follow the speaker (or args.player)
- "stop": hold position
- "goto": go to args.x,args.z (args.y optional)
- "explore": wander somewhere new
- "tp": teleport instantly to the speaker (or args.x,y,z) — use for "get over here fast"
- "tower": build a pillar args.n tall (1-40) where you stand
- "dig": remove a block. args.block = a type like "oak_log"/"stone" (nearest), OR args.here=true to break the block the speaker is looking at
- "clear": instantly clear a big area to air. args.radius (1-60), args.height (1-60). Origin: args.origin="look" (where speaker looks), "me" (speaker), or args.x/args.z. Great for "make a large clearing".
- "flatten": like clear, but also lays a flat floor (args.floor block, default grass_block)
- "idle": no movement — pure conversation / answering questions (answer in "reply")
Use speakerLookingAt as the origin when the player says "here" / "where I'm looking". Answer "where are you / what's nearby / what do you have" from world-state with action "idle". Keep replies under ~15 words, warm.`

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
  if (BOTNAMES.test(username)) return
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
    case 'flatten': { const o = resolveOrigin(args, speaker); const r = Math.min(Math.max(parseInt(args.radius || 12, 10), 1), 60); const h = Math.min(Math.max(parseInt(args.height || 20, 10), 1), 60); const floor = (args.floor || 'grass_block').replace(/[^a-z_]/gi, ''); fillBox(o.x - r, o.y, o.z - r, o.x + r, o.y + h, o.z + r, 'air'); fillBox(o.x - r, o.y - 1, o.z - r, o.x + r, o.y - 1, o.z + r, floor); bot.chat(`flattening a ${2 * r + 1}-wide platform`) } break
    case 'idle': default: break
  }
}

bot.on('kicked', r => log('KICKED', r)); bot.on('error', e => log('ERR', e.message)); bot.on('end', () => log('END'))
