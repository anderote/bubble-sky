// grok/lib/bridge.js — HTTP client for the mod-side AgentBridge.
//
// The towerdefense mod exposes an in-JVM HTTP API (see mods/.../bridge/ and
// BRIDGE.md) so agents can observe + act on a MODDED server WITHOUT the vanilla
// protocol — custom blocks/entities never touch a Mineflayer parser.
//
// This client mirrors the `hands` godmode surface (getBlock, readRegion, scan,
// setBlock, fillBox, command, batch) so it can later drop in as a hands backend
// with a one-line swap (the follow-up wiring described in BRIDGE.md).
//
// Config from env:
//   BUBBLESKY_BRIDGE_URL    base URL (default http://127.0.0.1:25580)
//   BUBBLESKY_BRIDGE_TOKEN  shared token sent as the X-Bridge-Token header
//
// Zero dependencies — Node's built-in http.

const http = require('http')
const { URL } = require('url')

// Keep block-state syntax intact (e.g. oak_stairs[facing=east]); only strip
// whitespace/quotes and lowercase. Mirrors hands.cleanBlock.
function cleanBlock(s, def) {
  const x = String(s == null ? '' : s).trim().toLowerCase()
    .replace(/^minecraft:/, '')
    .replace(/[^a-z0-9_:\[\]=,]/g, '')
  return x || (def || 'stone')
}

function makeBridge(opts = {}) {
  const base = opts.baseUrl || process.env.BUBBLESKY_BRIDGE_URL || 'http://127.0.0.1:25580'
  const token = opts.token || process.env.BUBBLESKY_BRIDGE_TOKEN || ''
  const timeoutMs = opts.timeoutMs || 8000
  const r = (n) => Math.round(n)

  function request(method, path, body) {
    return new Promise((resolve, reject) => {
      const u = new URL(path, base)
      const payload = body == null ? null : Buffer.from(JSON.stringify(body))
      const req = http.request({
        hostname: u.hostname,
        port: u.port,
        path: u.pathname + u.search,
        method,
        headers: {
          'X-Bridge-Token': token,
          'Content-Type': 'application/json',
          ...(payload ? { 'Content-Length': payload.length } : {})
        }
      }, (res) => {
        const chunks = []
        res.on('data', (c) => chunks.push(c))
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8')
          let json
          try { json = text ? JSON.parse(text) : {} } catch (e) { return reject(new Error(`bad JSON from ${path}: ${text.slice(0, 200)}`)) }
          if (res.statusCode >= 400 || json.ok === false) {
            return reject(new Error(`bridge ${method} ${path} ${res.statusCode}: ${json.error || text}`))
          }
          resolve(json)
        })
      })
      req.on('error', reject)
      req.setTimeout(timeoutMs, () => req.destroy(new Error(`bridge timeout ${method} ${path}`)))
      if (payload) req.write(payload)
      req.end()
    })
  }

  const qs = (o) => '?' + Object.entries(o).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&')

  return {
    name: 'bridge',
    baseUrl: base,
    cleanBlock,

    // ---- observation ----
    health() { return request('GET', '/health') },
    getBlock(x, y, z) { return request('GET', '/block' + qs({ x: r(x), y: r(y), z: r(z) })) },
    readRegion(x, y, z, rx = 4, ry = 4, rz = 4) {
      return request('GET', '/region' + qs({ x: r(x), y: r(y), z: r(z), rx: r(rx), ry: r(ry), rz: r(rz) }))
    },
    scan(x, y, z, radius = 16) {
      return request('GET', '/scan' + qs({ x: r(x), y: r(y), z: r(z), r: r(radius) }))
    },

    // ---- action (mirrors hands godmode primitives) ----
    setBlock(x, y, z, block) {
      return request('POST', '/setblock', { x: r(x), y: r(y), z: r(z), block: cleanBlock(block, 'stone') })
    },
    place(x, y, z, block) { return this.setBlock(x, y, z, block) },
    dig(x, y, z) { return this.setBlock(x, y, z, 'air') },
    fillBox(x1, y1, z1, x2, y2, z2, block) {
      return request('POST', '/fill', {
        x1: r(x1), y1: r(y1), z1: r(z1), x2: r(x2), y2: r(y2), z2: r(z2), block: cleanBlock(block, 'stone')
      })
    },
    clearArea(x, y, z, radius, height) {
      const rad = r(radius), h = r(height)
      return this.fillBox(x - rad, y, z - rad, x + rad, y + h, z + rad, 'air')
    },
    command(cmd) { return request('POST', '/command', { command: String(cmd).replace(/^\//, '') }) },
    setTime(v) { return this.command(`time set ${['day', 'night', 'noon', 'midnight'].includes(v) ? v : 'day'}`) },
    setWeather(v) { return this.command(`weather ${['clear', 'rain', 'thunder'].includes(v) ? v : 'clear'}`) },
    // tp/give mirror the godmode hands surface via /command (no vanilla protocol needed).
    tp(who, x, y, z) { if (x == null) return Promise.resolve(); return this.command(`tp ${who} ${(+x).toFixed(1)} ${(+y).toFixed(1)} ${(+z).toFixed(1)}`) },
    tpTo(who, target) { return this.command(`tp ${who} ${target}`) },
    give(who, item, count) { return this.command(`give ${who} ${cleanBlock(item, 'diamond')} ${Math.max(1, Math.min(64, count | 0 || 1))}`) },

    // ---- batched build (one server-thread hop) ----
    // ops: [{op:'setblock',x,y,z,block}, {op:'fill',x1..z2,block}, {op:'command',command}]
    batch(ops) { return request('POST', '/batch', { ops }) },

    // ---- chat + players + status (one-server unification) ----
    chat(since = 0) { return request('GET', '/chat' + qs({ since: since | 0 })) },
    sayAs(name, message) { return request('POST', '/say', { name: name || '', message: String(message) }) },
    player(name) { return request('GET', '/player' + qs({ name })) },
    players() { return request('GET', '/players') },
    testChat(player, text) { return request('POST', '/test/chat', { player, text: String(text) }) },
    postStatus(update) { return request('POST', '/status/agent', update) },
    getStatus() { return request('GET', '/status') }
  }
}

module.exports = makeBridge
module.exports.cleanBlock = cleanBlock
