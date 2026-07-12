// grok/lib/build/voxel.js — Layer-B VOXEL TARGET (in-memory, world-anchored).
//
// A target is { originWorld, size, palette, grid, meta }:
//   originWorld  world coordinate of local cell (0,0,0)
//   size         {x,y,z} extent of the grid in cells
//   palette      string[]; index 0 is the reserved "<unset>" sentinel = "the
//                design says nothing here — PRESERVE the world". Only non-zero
//                cells are ever placed. This encodes the additive principle in
//                the data model.
//   grid         Int16Array(size.x*size.y*size.z); grid[i] = palette index.
//   meta         Map<index, {region, phase, phaseNum}> set by the compiler so
//                the diff can tag each job with its semantic region + phase.
//
// Grid layout matches prismarine-schematic exactly:  i = x + z*sx + y*sx*sz.
//
// `.schem` import/export goes through prismarine-schematic; block strings are
// converted to/from stateIds with prismarine-block so a grid round-trips.

const UNSET = 0

function createTarget(originWorld, size) {
  const sx = Math.max(1, Math.round(size.x)), sy = Math.max(1, Math.round(size.y)), sz = Math.max(1, Math.round(size.z))
  return {
    originWorld: { x: Math.round(originWorld.x), y: Math.round(originWorld.y), z: Math.round(originWorld.z) },
    size: { x: sx, y: sy, z: sz },
    palette: ['<unset>'],
    grid: new Int16Array(sx * sy * sz),
    meta: new Map(),
    oob: 0
  }
}

function inBounds(t, x, y, z) {
  return x >= 0 && y >= 0 && z >= 0 && x < t.size.x && y < t.size.y && z < t.size.z
}

function idx(t, x, y, z) {
  return x + z * t.size.x + y * t.size.x * t.size.z
}

// Intern a block string into the palette, returning its index (never 0 for a
// real block — 0 is reserved for <unset>).
function intern(t, block) {
  const b = String(block)
  let i = t.palette.indexOf(b)
  if (i === -1) { i = t.palette.length; t.palette.push(b) }
  return i
}

// Set a cell. Out-of-bounds writes are dropped (counted) so a generator that
// reaches past the design bounds can never touch the world outside the target.
// Returns the linear index written, or -1 if dropped.
function set(t, x, y, z, block) {
  x = Math.round(x); y = Math.round(y); z = Math.round(z)
  if (!inBounds(t, x, y, z)) { t.oob++; return -1 }
  const i = idx(t, x, y, z)
  t.grid[i] = intern(t, block)
  return i
}

// Read a cell's block string, or null when unset/out-of-bounds.
function get(t, x, y, z) {
  x = Math.round(x); y = Math.round(y); z = Math.round(z)
  if (!inBounds(t, x, y, z)) return null
  const v = t.grid[idx(t, x, y, z)]
  return v === UNSET ? null : t.palette[v]
}

// World coordinate of a local cell.
function worldOf(t, x, y, z) {
  return { x: t.originWorld.x + x, y: t.originWorld.y + y, z: t.originWorld.z + z }
}

// Iterate every non-zero (set) cell in y,z,x order.
function forEachSet(t, cb) {
  const { x: sx, y: sy, z: sz } = t.size
  for (let y = 0; y < sy; y++) for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) {
    const i = idx(t, x, y, z)
    const v = t.grid[i]
    if (v !== UNSET) cb(x, y, z, t.palette[v], i)
  }
}

// Count of set (non-zero) cells.
function countSet(t) {
  let n = 0
  for (let i = 0; i < t.grid.length; i++) if (t.grid[i] !== UNSET) n++
  return n
}

// ---- .schem interop (prismarine-schematic) ----
// Build a Schematic from the target. Unset cells become air (a .schem is a full
// box). Block strings are converted to stateIds via prismarine-block.
function toSchematic(t, version = '1.21.6') {
  const { Schematic } = require('prismarine-schematic')
  const { Vec3 } = require('vec3')
  const Block = require('prismarine-block')(version)
  const airId = Block.fromString('air', 0).stateId
  const stateCache = new Map([['<unset>', airId]])
  const stateOf = (s) => {
    if (stateCache.has(s)) return stateCache.get(s)
    let id
    try { id = Block.fromString(s, 0).stateId } catch { id = airId }
    if (id == null) id = airId
    stateCache.set(s, id)
    return id
  }
  const schemPalette = []
  const schemIndex = new Map()
  const internState = (stateId) => {
    if (schemIndex.has(stateId)) return schemIndex.get(stateId)
    const i = schemPalette.length; schemPalette.push(stateId); schemIndex.set(stateId, i); return i
  }
  internState(airId) // ensure air is palette 0
  const blocks = new Array(t.grid.length)
  for (let i = 0; i < t.grid.length; i++) {
    const v = t.grid[i]
    blocks[i] = internState(stateOf(t.palette[v]))
  }
  return new Schematic(
    version,
    new Vec3(t.size.x, t.size.y, t.size.z),
    new Vec3(t.originWorld.x, t.originWorld.y, t.originWorld.z),
    schemPalette,
    blocks
  )
}

// Rebuild a target from a Schematic (non-air blocks become set cells; air = unset).
function fromSchematic(schem, version = '1.21.6') {
  const { Vec3 } = require('vec3')
  const origin = { x: schem.offset.x, y: schem.offset.y, z: schem.offset.z }
  const t = createTarget(origin, { x: schem.size.x, y: schem.size.y, z: schem.size.z })
  const start = schem.start()
  for (let y = 0; y < t.size.y; y++) for (let z = 0; z < t.size.z; z++) for (let x = 0; x < t.size.x; x++) {
    const block = schem.getBlock(new Vec3(start.x + x, start.y + y, start.z + z))
    if (!block || block.name === 'air' || block.name === 'cave_air' || block.name === 'void_air') continue
    const props = block.getProperties ? block.getProperties() : {}
    const keys = Object.keys(props).sort()
    const state = keys.length ? `[${keys.map(k => `${k}=${props[k]}`).join(',')}]` : ''
    set(t, x, y, z, `${block.name}${state}`)
  }
  return t
}

async function writeSchemFile(t, filePath, version = '1.21.6') {
  const fs = require('fs/promises')
  const path = require('path')
  const buf = await toSchematic(t, version).write()
  await fs.mkdir(path.dirname(filePath), { recursive: true })
  await fs.writeFile(filePath, buf)
  return filePath
}

async function readSchemFile(filePath, version = '1.21.6') {
  const fs = require('fs/promises')
  const { Schematic } = require('prismarine-schematic')
  const schem = await Schematic.read(await fs.readFile(filePath), version)
  return fromSchematic(schem, version)
}

module.exports = {
  UNSET, createTarget, inBounds, idx, intern, set, get, worldOf, forEachSet,
  countSet, toSchematic, fromSchematic, writeSchemFile, readSchemFile
}
