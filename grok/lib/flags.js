// grok/lib/flags.js — named world markers ("flags") + rectangular REGIONS the
// user plants/defines and then references in build commands ("build a wall
// between A2 and B1", "build X at flag A2", "build a keep in region 1").
//
// ONE SHARED STORE: when the mod-side bridge is reachable, flags AND regions live
// server-side (the Layout Wand writes there too), so wand-planted markers and
// chat-planted flags are the SAME system. This module keeps a local
// memory/flags.json as a write-through CACHE + offline FALLBACK: reads used
// synchronously by the router (get / list / getRegion) hit the cache, and an
// async sync() refreshes the cache from the bridge before each message.
//
// For each flag it also places a VISIBLE in-world marker (colored-wool base +
// lightning_rod post) plus a FLOATING NAME LABEL (an invisible marker armor
// stand) via the throttled command queue — matching the wand's own marker.

const fs = require('fs')
const path = require('path')

const WOOLS = ['red', 'orange', 'yellow', 'lime', 'light_blue', 'magenta', 'pink', 'cyan', 'purple', 'white']

function hash(s) { let h = 0; for (const c of String(s)) h = (h * 31 + c.charCodeAt(0)) | 0; return h }

function makeFlags(ctx) {
  const { dir, enqueue, hands, log = () => {}, bridge = null } = ctx
  const FILE = path.join(dir, 'flags.json')

  // bridgeOk: null = untested, true = reachable, false = offline (use local only).
  let bridgeOk = bridge ? null : false

  const read = () => { try { return JSON.parse(fs.readFileSync(FILE, 'utf8')) } catch { return { flags: {}, regions: {} } } }
  const write = (data) => { try { fs.mkdirSync(path.dirname(FILE), { recursive: true }); fs.writeFileSync(FILE, JSON.stringify(data, null, 2)) } catch (e) { log('flags write err', e.message) } }

  const keyOf = (name) => String(name || '').trim().toLowerCase()
  const tagOf = (name) => 'grokflag_' + keyOf(name).replace(/[^a-z0-9_]/g, '_')

  // ---- bridge helpers (degrade gracefully; a failure disables the bridge for
  // this session so we never repeatedly hang on HTTP timeouts). ----
  async function viaBridge(fn) {
    if (!bridge || bridgeOk === false) return null
    try { const r = await fn(); bridgeOk = true; return r }
    catch (e) { if (bridgeOk === null) log('flags: bridge unreachable, using local json', e.message); bridgeOk = false; return null }
  }

  // Pull the server-side flags + regions into the local cache (bridge is the
  // source of truth). No-op / fallback when the bridge is offline.
  async function sync() {
    if (!bridge || bridgeOk === false) return
    const fr = await viaBridge(() => bridge.getFlags())
    const rr = await viaBridge(() => bridge.getRegions())
    if (!fr && !rr) return
    const data = read()
    if (fr && Array.isArray(fr.flags)) {
      data.flags = {}
      for (const f of fr.flags) data.flags[f.name] = { x: f.x, y: f.y, z: f.z, dim: f.dim || 'overworld' }
    }
    if (rr && Array.isArray(rr.regions)) {
      data.regions = {}
      for (const r of rr.regions) data.regions[r.name] = { a: r.a, b: r.b, dim: r.dim || 'overworld' }
    }
    write(data)
  }

  // ---- flags (sync reads over the cache) ----
  function get(name) {
    const data = read(); const k = keyOf(name)
    for (const [n, rec] of Object.entries(data.flags || {})) if (keyOf(n) === k) return { name: n, ...rec }
    return null
  }
  function list() { const data = read(); return Object.entries(data.flags || {}).map(([name, rec]) => ({ name, ...rec })) }

  // A distinctive marker + a floating name label at (x,y,z).
  function placeMarker(name, x, y, z) {
    const wool = WOOLS[Math.abs(hash(name)) % WOOLS.length] + '_wool'
    hands.setBlock(x, y, z, wool)                 // colored base
    hands.setBlock(x, y + 1, z, 'lightning_rod')  // distinctive metal post
    const label = String(name).replace(/["\\]/g, '')
    enqueue(`/summon armor_stand ${x + 0.5} ${y + 1.3} ${z + 0.5} {Invisible:1b,Marker:1b,NoGravity:1b,CustomNameVisible:1b,CustomName:'"${label}"',Tags:["grokflag","${tagOf(name)}"]}`)
  }

  function clearMarker(rec) {
    if (!rec) return
    enqueue(`/kill @e[tag=${tagOf(rec.name)}]`)
    hands.setBlock(rec.x, rec.y + 1, rec.z, 'air')
    hands.setBlock(rec.x, rec.y, rec.z, 'air')
  }

  // Plant/replace a flag at pos {x,y,z,dim}. Writes through to the bridge (shared
  // store) AND the local cache, and places the in-world marker. Returns the record.
  function set(name, pos) {
    const data = read()
    const clean = String(name || '').trim() || 'flag'
    const existing = get(clean); if (existing) clearMarker(existing)
    data.flags = data.flags || {}
    for (const n of Object.keys(data.flags)) if (keyOf(n) === keyOf(clean)) delete data.flags[n]
    const rec = { x: Math.round(pos.x), y: Math.round(pos.y), z: Math.round(pos.z), dim: pos.dim || 'overworld' }
    data.flags[clean] = rec; write(data)
    viaBridge(() => bridge.postFlag({ name: clean, x: rec.x, y: rec.y, z: rec.z, dim: rec.dim }))
    placeMarker(clean, rec.x, rec.y, rec.z)
    return { name: clean, ...rec }
  }

  function remove(name) {
    const data = read(); const rec = get(name)
    if (!rec) return false
    clearMarker(rec)
    for (const n of Object.keys(data.flags || {})) if (keyOf(n) === keyOf(name)) delete data.flags[n]
    write(data)
    viaBridge(() => bridge.deleteFlag(rec.name))
    return true
  }

  // ---- regions (sync reads over the cache; wand/agents define them) ----
  function getRegion(name) {
    const data = read(); const k = keyOf(name)
    for (const [n, rec] of Object.entries(data.regions || {})) if (keyOf(n) === k) return { name: n, ...rec }
    // Also accept a bare number ("region 1" → "1") matching "Region 1".
    const num = String(name || '').match(/\d+/)
    if (num) for (const [n, rec] of Object.entries(data.regions || {})) if (String(n).match(/\d+/)?.[0] === num[0]) return { name: n, ...rec }
    return null
  }
  function listRegions() { const data = read(); return Object.entries(data.regions || {}).map(([name, rec]) => ({ name, ...rec })) }

  function setRegion(name, a, b, dim) {
    const data = read()
    data.regions = data.regions || {}
    const clean = String(name || '').trim() || `Region ${Object.keys(data.regions).length + 1}`
    const rec = { a: { x: Math.round(a.x), y: Math.round(a.y), z: Math.round(a.z) }, b: { x: Math.round(b.x), y: Math.round(b.y), z: Math.round(b.z) }, dim: dim || 'overworld' }
    for (const n of Object.keys(data.regions)) if (keyOf(n) === keyOf(clean)) delete data.regions[n]
    data.regions[clean] = rec; write(data)
    viaBridge(() => bridge.postRegion({ name: clean, a: rec.a, b: rec.b, dim: rec.dim }))
    return { name: clean, ...rec }
  }

  return { get, list, set, remove, sync, getRegion, listRegions, setRegion, FILE, WOOLS }
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
