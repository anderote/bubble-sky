// grok/lib/flags.js — named world markers ("flags") the user plants and then
// references in build commands ("build a wall between A2 and B1", "build X at
// flag A2").
//
// Persists a name -> {x,y,z,dim} map in memory/flags.json (same JSON pattern as
// memory.js), and for each flag places a VISIBLE in-world marker (a colored-wool
// base + a lightning_rod post) plus a FLOATING NAME LABEL — an invisible marker
// armor stand whose CustomName is the flag name — via the throttled command
// queue. Also exposes helpers so a build origin/endpoint can resolve to a flag.

const fs = require('fs')
const path = require('path')

const WOOLS = ['red', 'orange', 'yellow', 'lime', 'light_blue', 'magenta', 'pink', 'cyan', 'purple', 'white']

function hash(s) { let h = 0; for (const c of String(s)) h = (h * 31 + c.charCodeAt(0)) | 0; return h }

function makeFlags(ctx) {
  const { dir, enqueue, hands, log = () => {} } = ctx
  const FILE = path.join(dir, 'flags.json')

  const read = () => { try { return JSON.parse(fs.readFileSync(FILE, 'utf8')) } catch { return { flags: {} } } }
  const write = (data) => { try { fs.mkdirSync(path.dirname(FILE), { recursive: true }); fs.writeFileSync(FILE, JSON.stringify(data, null, 2)) } catch (e) { log('flags write err', e.message) } }

  const keyOf = (name) => String(name || '').trim().toLowerCase()
  const tagOf = (name) => 'grokflag_' + keyOf(name).replace(/[^a-z0-9_]/g, '_')

  // Case-insensitive lookup by name (flags are typically "A2", "B1", "home", …).
  function get(name) {
    const data = read(); const k = keyOf(name)
    for (const [n, rec] of Object.entries(data.flags)) if (keyOf(n) === k) return { name: n, ...rec }
    return null
  }
  function list() { const data = read(); return Object.entries(data.flags).map(([name, rec]) => ({ name, ...rec })) }

  // A distinctive marker + a floating name label at (x,y,z).
  function placeMarker(name, x, y, z) {
    const wool = WOOLS[Math.abs(hash(name)) % WOOLS.length] + '_wool'
    hands.setBlock(x, y, z, wool)                 // colored base
    hands.setBlock(x, y + 1, z, 'lightning_rod')  // distinctive metal post
    // floating label: an invisible, no-gravity marker armor stand with a visible
    // CustomName. Tagged so it can be removed precisely later.
    const label = String(name).replace(/["\\]/g, '')
    enqueue(`/summon armor_stand ${x + 0.5} ${y + 1.3} ${z + 0.5} {Invisible:1b,Marker:1b,NoGravity:1b,CustomNameVisible:1b,CustomName:'"${label}"',Tags:["grokflag","${tagOf(name)}"]}`)
  }

  function clearMarker(rec) {
    if (!rec) return
    enqueue(`/kill @e[tag=${tagOf(rec.name)}]`)
    hands.setBlock(rec.x, rec.y + 1, rec.z, 'air')
    hands.setBlock(rec.x, rec.y, rec.z, 'air')
  }

  // Plant/replace a flag at pos {x,y,z,dim}. Returns the stored record.
  function set(name, pos) {
    const data = read()
    const clean = String(name || '').trim() || 'flag'
    const existing = get(clean); if (existing) clearMarker(existing)
    for (const n of Object.keys(data.flags)) if (keyOf(n) === keyOf(clean)) delete data.flags[n]
    const rec = { x: Math.round(pos.x), y: Math.round(pos.y), z: Math.round(pos.z), dim: pos.dim || 'overworld' }
    data.flags[clean] = rec; write(data)
    placeMarker(clean, rec.x, rec.y, rec.z)
    return { name: clean, ...rec }
  }

  function remove(name) {
    const data = read(); const rec = get(name)
    if (!rec) return false
    clearMarker(rec)
    for (const n of Object.keys(data.flags)) if (keyOf(n) === keyOf(name)) delete data.flags[n]
    write(data); return true
  }

  return { get, list, set, remove, FILE, WOOLS }
}

// Draw a straight wall between two world points A and B (interpolating x/z and y
// so it follows sloped ground), `height` tall and `thickness` wide. Uses the
// godmode filler (hands). Returns the approximate block count placed.
function wallBetween(A, B, opts, hands) {
  const block = (opts && opts.block) || 'stone_bricks'
  const height = Math.max(1, Math.min(24, (opts && opts.height) || 5))
  const thick = Math.max(1, Math.min(6, (opts && opts.thickness) || 1))
  const t = (thick - 1) >> 1
  const dx = B.x - A.x, dz = B.z - A.z, dy = B.y - A.y
  const steps = Math.max(Math.abs(dx), Math.abs(dz))
  if (steps === 0) { hands.fillBox(A.x - t, A.y, A.z - t, A.x + t, A.y + height - 1, A.z + t, block); return height }
  for (let i = 0; i <= steps; i++) {
    const x = Math.round(A.x + dx * i / steps)
    const z = Math.round(A.z + dz * i / steps)
    const y = Math.round(A.y + dy * i / steps)
    hands.fillBox(x - t, y, z - t, x + t, y + height - 1, z + t, block)
  }
  return (steps + 1) * height
}

module.exports = makeFlags
module.exports.wallBetween = wallBetween
