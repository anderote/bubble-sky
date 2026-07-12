// grok/lib/hands.js — pluggable execution backend for Grok.
//
// The rest of the code calls hands.<primitive>(...) and never touches raw
// server commands or mineflayer place/dig directly. Two backends are provided:
//
//   godmode  (DEFAULT) — uses operator commands (/fill /setblock /tp /give
//            /time /weather) via the throttled queue. Instant, reliable.
//   survival (stub)    — uses mineflayer place/dig/collect. Interface is clean
//            so godmode is swappable, but most primitives are TODO stubs.
//
// Toggle with env GODMODE (defaults true). GODMODE=false selects survival.
//
// Primitives: fillBox, setBlock, clearArea, place, dig, tp, give, setTime, setWeather.

// Keep block-state syntax intact (e.g. oak_stairs[facing=east,half=bottom]);
// only strip whitespace/quotes and force lowercase.
function cleanBlock(s, def) {
  const x = String(s == null ? '' : s).trim().toLowerCase()
    .replace(/^minecraft:/, '')
    .replace(/[^a-z0-9_:\[\]=,]/g, '')
  return x || (def || 'stone')
}

function makeHands(ctx) {
  const { enqueue, bot, log = () => {} } = ctx
  const GODMODE = String(process.env.GODMODE == null ? 'true' : process.env.GODMODE).toLowerCase() !== 'false'
  const sort2 = (a, b) => [Math.min(a, b), Math.max(a, b)]

  // ---- GODMODE backend (operator commands via throttled queue) ----
  const godmode = {
    name: 'godmode',
    fillBox(x1, y1, z1, x2, y2, z2, block) {
      block = cleanBlock(block, 'stone')
      ;[x1, x2] = sort2(x1, x2);[y1, y2] = sort2(y1, y2);[z1, z2] = sort2(z1, z2)
      x1 = Math.round(x1); x2 = Math.round(x2); z1 = Math.round(z1); z2 = Math.round(z2)
      y1 = Math.max(-64, Math.round(y1)); y2 = Math.min(319, Math.round(y2))
      let n = 0
      for (let x = x1; x <= x2; x += 32) { const xe = Math.min(x + 31, x2)
        for (let y = y1; y <= y2; y += 32) { const ye = Math.min(y + 31, y2)
          for (let z = z1; z <= z2; z += 32) { const ze = Math.min(z + 31, z2)
            enqueue(`/fill ${x} ${y} ${z} ${xe} ${ye} ${ze} ${block}`); n++ } } }
      return n
    },
    setBlock(x, y, z, block) {
      enqueue(`/setblock ${Math.round(x)} ${Math.round(y)} ${Math.round(z)} ${cleanBlock(block, 'stone')}`)
      return 1
    },
    place(x, y, z, block) { return this.setBlock(x, y, z, block) },
    dig(x, y, z) { enqueue(`/setblock ${Math.round(x)} ${Math.round(y)} ${Math.round(z)} air`); return 1 },
    clearArea(x, y, z, radius, height) {
      const r = Math.round(radius), h = Math.round(height)
      return this.fillBox(x - r, y, z - r, x + r, y + h, z + r, 'air')
    },
    tp(who, x, y, z) {
      if (x == null) return
      enqueue(`/tp ${who} ${(+x).toFixed(1)} ${(+y).toFixed(1)} ${(+z).toFixed(1)}`)
    },
    tpTo(who, target) { enqueue(`/tp ${who} ${target}`) },
    give(who, item, count) { enqueue(`/give ${who} ${cleanBlock(item, 'diamond')} ${Math.max(1, Math.min(64, count | 0 || 1))}`) },
    setTime(v) { enqueue(`/time set ${['day', 'night', 'noon', 'midnight'].includes(v) ? v : 'day'}`) },
    setWeather(v) { enqueue(`/weather ${['clear', 'rain', 'thunder'].includes(v) ? v : 'clear'}`) }
  }

  // ---- SURVIVAL backend (mineflayer; partial stub, clean interface) ----
  const survival = {
    name: 'survival',
    async setBlock(x, y, z, block) {
      // TODO: pathfind adjacent, equip block, bot.placeBlock against a reference face.
      log('[survival] setBlock TODO', x, y, z, block); return 0
    },
    async place(x, y, z, block) { return this.setBlock(x, y, z, block) },
    async fillBox(x1, y1, z1, x2, y2, z2, block) {
      // TODO: iterate cells calling setBlock; respect inventory/scaffolding.
      log('[survival] fillBox TODO', block); return 0
    },
    async dig(x, y, z) {
      // TODO: pathfind to block, bot.dig(); collect drops.
      log('[survival] dig TODO', x, y, z); return 0
    },
    async clearArea(x, y, z, radius, height) { log('[survival] clearArea TODO'); return 0 },
    async collect() { log('[survival] collect TODO'); return 0 },  // survival-only extra
    tp() { log('[survival] tp unsupported (no op)') },
    tpTo() { log('[survival] tp unsupported (no op)') },
    give() { log('[survival] give unsupported (no op)') },
    setTime() { log('[survival] setTime unsupported (no op)') },
    setWeather() { log('[survival] setWeather unsupported (no op)') }
  }

  const hands = GODMODE ? godmode : survival
  hands.godmode = GODMODE
  hands.cleanBlock = cleanBlock
  return hands
}

module.exports = makeHands
module.exports.cleanBlock = cleanBlock
