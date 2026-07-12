// grok/lib/transport/bridge.js — HTTP-bridge transport (no Mineflayer).
//
// Lets Grok run ENTIRELY over the mod-side AgentBridge (see BRIDGE.md), so ONE
// modded server can host the mods AND Grok. Implements the SAME transport
// surface as lib/transport/mineflayer.js — chat in/out, "here" look-targeting,
// position, hands, move/tp, world-state, status — but over plain HTTP + JSON, so
// the fragile vanilla protocol is never in the loop (modded blocks are fine).
//
// Selected with GROK_TRANSPORT=bridge. The router / architect / skills / build
// pipeline are shared and unchanged — they only need hands + look + chat.
//
// hands here is a SYNC-return, queue-draining backend (mirrors godmode): each
// setBlock/fillBox/command pushes an op and a coalescing flusher ships them via
// POST /batch (one server-thread hop). queueIdle() awaits the drain, so the
// shared build pipeline (which calls hands synchronously) works unchanged.

const makeBridge = require('../bridge')

module.exports = function makeBridgeTransport(deps) {
  const { USERNAME = 'Grok', log = () => {} } = deps
  const sleep = ms => new Promise(r => setTimeout(r, ms))
  const bridge = makeBridge()
  const cleanBlock = makeBridge.cleanBlock
  const r = Math.round
  const sort2 = (a, b) => [Math.min(a, b), Math.max(a, b)]

  // ---- op queue → POST /batch (coalesced, ordered) ----
  const pending = []
  let flushing = null
  const FILL_CHUNK = 40           // keep each /fill well under the server's 262k cap
  const BATCH_MAX = 2000          // ops per /batch request

  function schedule() { if (!flushing) flushing = flushLoop() }
  async function flushLoop() {
    await sleep(15)               // small coalescing window so a build's ops batch together
    try {
      while (pending.length) {
        if (stopped) { pending.length = 0; break }   // interrupted → drop the rest
        const chunk = pending.splice(0, BATCH_MAX)
        try { await bridge.batch(chunk) } catch (e) { log('bridge batch err', e.message) }
      }
    } finally { flushing = null }
    if (pending.length) schedule()
  }
  function pushOp(op) { if (stopped) return; pending.push(op); schedule() }
  async function queueIdle() { while (pending.length || flushing) await sleep(30) }
  function enqueue(cmd) { pushOp({ op: 'command', command: String(cmd).replace(/^\//, '') }) }

  // INSTANT STOP: drop every queued op so the flush loop drains empty and no more
  // /batch requests go out. `stopped` rejects late pushOps from a build loop that
  // hasn't yet noticed `aborted`; it's cleared in refresh() when the NEXT message
  // starts being handled (so the following build enqueues normally).
  let stopped = false
  function stop() { stopped = true; pending.length = 0 }

  const hands = {
    name: 'bridge', godmode: true, cleanBlock,
    fillBox(x1, y1, z1, x2, y2, z2, block) {
      block = cleanBlock(block, 'stone')
      ;[x1, x2] = sort2(r(x1), r(x2));[y1, y2] = sort2(r(y1), r(y2));[z1, z2] = sort2(r(z1), r(z2))
      y1 = Math.max(-64, y1); y2 = Math.min(319, y2)
      let n = 0
      for (let x = x1; x <= x2; x += FILL_CHUNK) { const xe = Math.min(x + FILL_CHUNK - 1, x2)
        for (let y = y1; y <= y2; y += FILL_CHUNK) { const ye = Math.min(y + FILL_CHUNK - 1, y2)
          for (let z = z1; z <= z2; z += FILL_CHUNK) { const ze = Math.min(z + FILL_CHUNK - 1, z2)
            pushOp({ op: 'fill', x1: x, y1: y, z1: z, x2: xe, y2: ye, z2: ze, block }); n++ } } }
      return n
    },
    setBlock(x, y, z, block) { pushOp({ op: 'setblock', x: r(x), y: r(y), z: r(z), block: cleanBlock(block, 'stone') }); return 1 },
    place(x, y, z, block) { return this.setBlock(x, y, z, block) },
    dig(x, y, z) { pushOp({ op: 'setblock', x: r(x), y: r(y), z: r(z), block: 'air' }); return 1 },
    clearArea(x, y, z, radius, height) { const rad = r(radius), h = r(height); return this.fillBox(x - rad, y, z - rad, x + rad, y + h, z + rad, 'air') },
    tp(who, x, y, z) { if (x == null) return; enqueue(`tp ${who} ${(+x).toFixed(1)} ${(+y).toFixed(1)} ${(+z).toFixed(1)}`) },
    tpTo(who, target) { enqueue(`tp ${who} ${target}`) },
    give(who, item, count) { enqueue(`give ${who} ${cleanBlock(item, 'diamond')} ${Math.max(1, Math.min(64, count | 0 || 1))}`) },
    setTime(v) { enqueue(`time set ${['day', 'night', 'noon', 'midnight'].includes(v) ? v : 'day'}`) },
    setWeather(v) { enqueue(`weather ${['clear', 'rain', 'thunder'].includes(v) ? v : 'clear'}`) }
  }

  // ---- per-speaker snapshot (refreshed at message time so sync look/pos work) ----
  let snap = { speaker: null, pos: null, lookingAt: null }
  let playersCache = []
  async function refresh(speaker) {
    stopped = false            // a new message is being handled → allow ops again
    try {
      const p = await bridge.player(speaker)
      snap = { speaker, pos: p.pos || null, lookingAt: p.lookingAt || null }
    } catch { snap = { speaker, pos: null, lookingAt: null } }
    try { playersCache = (await bridge.players()).players || [] } catch { playersCache = [] }
  }

  const round = p => ({ x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) })
  function lookTarget(speaker) { return snap.lookingAt ? { x: snap.lookingAt.x, y: snap.lookingAt.y, z: snap.lookingAt.z } : null }
  function speakerPos(speaker) { return snap.pos ? round(snap.pos) : null }
  function selfPos() { return snap.pos ? round(snap.pos) : { x: 0, y: 64, z: 0 } }
  function dimension() { return 'overworld' }

  function world(speaker, extra = {}) {
    const activeProject = extra.activeProject
    const players = playersCache.map(pl => ({ name: pl.name, pos: pl.pos })).filter(pl => pl.name !== USERNAME)
    return {
      transport: 'bridge',
      position: snap.pos || null,
      players,
      speakerLookingAt: snap.lookingAt ? { x: snap.lookingAt.x, y: snap.lookingAt.y, z: snap.lookingAt.z } : null,
      note: 'bridge mode: no embodied position/inventory; you build via commands and can read "here" from the speaker look.',
      activeProject: activeProject ? { project: activeProject.project, origin: activeProject.origin } : null
    }
  }

  // Grok has no walking body over the bridge → pathfinding/follow are unavailable.
  // Teleporting a player by coords still works via /command.
  function move(args, speaker, { say }) {
    const mode = args.mode
    if (mode === 'tp' && args.x != null) { hands.tp(args.player || speaker, +args.x, +args.y, +args.z); return }
    if (mode === 'bring' && args.x != null) { hands.tp(args.player || speaker, +args.x, +args.y, +args.z); return }
    say("I'm in bridge mode (no walking body) — I build with commands. Give me coords to teleport you, or a build to make!")
  }

  // No world scan over the bridge without a per-cell read; dig-by-name is unsupported.
  function digNearest() { return null }

  // Flat-site assumption anchored at the looked-at block (bridge has no cheap heightmap).
  function surveySite(origin, radius) {
    const g = Math.round(origin.y)
    return { groundY: g, minY: g, maxY: g, slope: 0, hasWater: false, hasLava: false, biome: 'unknown', obstacles: 0, samples: 0, note: 'bridge mode — flat assumption at looked-at block' }
  }

  function say(s) { if (s) bridge.sayAs(USERNAME, String(s).slice(0, 200)).catch(e => log('bridge say err', e.message)) }

  function status(update) {
    const u = Object.assign({ name: USERNAME }, update || {})
    bridge.postStatus(u).catch(() => {})
  }

  async function start({ onChat, greeting }) {
    // Verify the bridge is reachable before polling.
    try { const h = await bridge.health(); log(`bridge connected: MC ${h.mcVersion} mod ${h.modVersion} tps ${h.tps}`) }
    catch (e) { log('bridge health FAILED — is the modded server + bridge up?', e.message); throw e }
    if (greeting) await bridge.sayAs(USERNAME, greeting).catch(() => {})
    status({ activity: 'idle', detail: '' })
    // Start from the current cursor so we don't replay backlog.
    let since = 0
    try { since = (await bridge.chat(0)).cursor || 0 } catch {}
    log(`bridge chat poll starting at cursor ${since}`)
    ;(async function poll() {
      for (;;) {
        try {
          const { cursor, messages } = await bridge.chat(since)
          if (typeof cursor === 'number') since = cursor
          for (const m of messages || []) {
            try { onChat(m.player, m.text) } catch (e) { log('onChat err', e.message) }
          }
        } catch (e) { log('bridge poll err', e.message) }
        await sleep(1000)
      }
    })()
  }

  return {
    name: 'bridge', hands, enqueue, queueIdle, bridge, bot: null, survey: require('../survey'),
    lookTarget, speakerPos, selfPos, dimension, world, move, digNearest, surveySite,
    say, status, start, stop, refresh,
    followsSupported: false
  }
}
