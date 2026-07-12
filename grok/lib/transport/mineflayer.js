// grok/lib/transport/mineflayer.js — DEFAULT transport (unchanged behavior).
//
// Wraps a Mineflayer bot: chat in/out, "here" look-targeting via raycast,
// position, movement/pathfinding, a throttled operator-command queue, and the
// godmode `hands` backend. This is the surface assistant.js talks to; the
// router / architect / skills / build pipeline are transport-agnostic and shared.
//
// Selected when GROK_TRANSPORT=mineflayer (the default). The bridge transport
// (lib/transport/bridge.js) implements the SAME surface over HTTP so Grok can
// run on a modded server with no vanilla protocol.

const mineflayer = require('mineflayer')
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder')
const Vec3 = require('vec3')
const makeHands = require('../hands')
const survey = require('../survey')

module.exports = function makeMineflayerTransport(deps) {
  const { USERNAME, BOTNAMES, log = () => {} } = deps
  const sleep = ms => new Promise(r => setTimeout(r, ms))

  const bot = mineflayer.createBot({
    host: process.env.MC_HOST || 'localhost', port: +(process.env.MC_PORT || 25565),
    username: USERNAME, auth: 'offline', version: '1.21.6'
  })
  bot.loadPlugin(pathfinder)

  let followTarget = null

  // ---- throttled server-command queue (Grok is opped → /fill, /setblock, etc.) ----
  const cmdQueue = []
  let draining = false
  function enqueue(cmd) { cmdQueue.push(cmd); drain() }
  async function drain() { if (draining) return; draining = true; while (cmdQueue.length) { try { bot.chat(cmdQueue.shift()) } catch {} await sleep(130) } draining = false }
  async function queueIdle() { while (cmdQueue.length || draining) await sleep(120) }

  const hands = makeHands({ enqueue, bot, log })

  const round = p => ({ x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) })

  function lookTarget(username) {
    const e = bot.players[username]?.entity
    if (!e) return null
    const yaw = e.yaw, pitch = e.pitch
    const dir = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch))
    const from = e.position.offset(0, 1.62, 0)
    try { const hit = bot.world.raycast(from, dir.normalize(), 96); if (hit) { const p = hit.position || hit; return { x: p.x, y: p.y, z: p.z } } } catch {}
    return null
  }

  function speakerPos(username) {
    const e = bot.players[username]?.entity
    return e ? round(e.position) : null
  }

  function selfPos() { return round(bot.entity.position) }

  function dimension() {
    return (bot.game && bot.game.dimension ? String(bot.game.dimension).replace(/^minecraft:/, '') : 'overworld')
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

  function world(speaker, extra = {}) {
    const p = bot.entity.position
    const players = Object.values(bot.players).filter(pl => pl.entity && !BOTNAMES.test(pl.username)).map(pl => ({ name: pl.username, dist: Math.round(pl.entity.position.distanceTo(p)) })).sort((a, b) => a.dist - b.dist)
    const entities = Object.values(bot.entities).filter(e => e !== bot.entity && e.position && e.type !== 'player' && e.position.distanceTo(p) < 24).map(e => ({ name: e.name || e.displayName, dist: Math.round(e.position.distanceTo(p)) })).sort((a, b) => a.dist - b.dist).slice(0, 6)
    let biome; try { biome = bot.blockAt(p)?.biome?.name } catch {}
    const activeProject = extra.activeProject
    return { position: { x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) }, biome, health: bot.health, food: bot.food, timeOfDay: bot.time.timeOfDay, players, entities, inventory: bot.inventory.items().map(i => `${i.name} x${i.count}`).slice(0, 12), resourcesNearby: resources(), following: followTarget, speakerLookingAt: speaker ? lookTarget(speaker) : null, activeProject: activeProject ? { project: activeProject.project, origin: activeProject.origin } : null }
  }

  function move(args, speaker, { say }) {
    const p = bot.entity.position
    const mode = args.mode
    if (mode === 'come' || mode === 'follow') { followTarget = args.player || speaker; const e = bot.players[followTarget]?.entity; if (e) bot.pathfinder.setGoal(new goals.GoalFollow(e, 2), true) }
    else if (mode === 'stop') { followTarget = null; bot.pathfinder.setGoal(null); bot.clearControlStates() }
    else if (mode === 'goto') { if (args.x != null && args.z != null) { followTarget = null; bot.pathfinder.setGoal(new goals.GoalNear(+args.x, args.y != null ? +args.y : p.y, +args.z, 1)) } }
    else if (mode === 'explore') { followTarget = null; bot.pathfinder.setGoal(new goals.GoalNear(Math.floor(p.x + (Math.random() * 40 - 20)), p.y, Math.floor(p.z + (Math.random() * 40 - 20)), 1)) }
    else if (mode === 'tp') { if (args.x != null) hands.tp(USERNAME, +args.x, +args.y, +args.z); else hands.tpTo(USERNAME, args.player || speaker) }
    else if (mode === 'bring') { hands.tpTo(args.player || speaker, USERNAME) }
  }

  function digNearest(blockName) {
    try {
      const mcData = require('minecraft-data')(bot.version)
      const id = mcData.blocksByName[blockName]?.id
      const b = id != null ? bot.findBlock({ matching: id, maxDistance: 64 }) : null
      return b ? { x: b.position.x, y: b.position.y, z: b.position.z } : null
    } catch { return null }
  }

  function surveySite(origin, radius) { return survey.surveySite(bot, origin, radius) }

  function say(s) { if (s) bot.chat(String(s).slice(0, 200)) }

  // INSTANT STOP: drop every queued /fill+/setblock (that's the flood), reset the
  // drain loop, and halt movement/pathfinding. Any in-progress build loop then
  // sees `aborted` and stops re-filling the queue.
  function stop() {
    cmdQueue.length = 0
    draining = false
    followTarget = null
    try { bot.pathfinder.setGoal(null) } catch {}
    try { bot.clearControlStates() } catch {}
  }

  // status HUD posting is a bridge-mode feature; no-op on the vanilla transport.
  function status() {}

  function start({ onChat, greeting }) {
    bot.on('chat', (username, message) => { try { onChat(username, message) } catch (e) { log('onChat err', e.message) } })
    bot.once('spawn', () => {
      log('spawned at', bot.entity.position)
      // QUIET the build: silence /fill + /setblock command feedback so Grok builds
      // without flooding chat. (Grok still speaks via say() for answers.)
      bot.chat('/gamerule sendCommandFeedback false')
      bot.chat('/gamerule logAdminCommands false')
      const m = new Movements(bot); m.allowSprinting = true; m.canDig = false
      bot.pathfinder.setMovements(m)
      if (greeting) bot.chat(greeting)
      setInterval(() => { if (!followTarget) return; const e = bot.players[followTarget]?.entity; if (e) bot.pathfinder.setGoal(new goals.GoalFollow(e, 2), true) }, 2000)
    })
    bot.on('kicked', r => log('KICKED', r)); bot.on('error', e => log('ERR', e.message)); bot.on('end', () => log('END'))
  }

  return {
    name: 'mineflayer', hands, enqueue, queueIdle, bot, survey,
    lookTarget, speakerPos, selfPos, dimension, world, move, digNearest, surveySite,
    say, status, start, stop,
    followsSupported: true
  }
}
