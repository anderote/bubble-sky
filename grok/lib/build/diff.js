// grok/lib/build/diff.js — ADDITIVE diff: voxel target vs the live world.
//
// For every NON-ZERO target cell (unset cells are never considered), read the
// live world and emit a Codex-shaped job ONLY when the world differs from the
// target. Air is emitted for exactly one reason: the target cell itself is air
// (a carve / point-air) AND the world is not already air. Nothing else can
// delete — a build dropped over terrain/trees leaves every surrounding and
// unset cell untouched. There is NO bounding-box clear.
//
// `readBlock(x,y,z)` is injectable: real = a wrapper over bot.blockAt; tests =
// a Map lookup. It returns a bare block NAME (no state) or null/undefined for
// "unknown/air". Jobs are ordered by phase (region.phaseNum) then bottom-up.

const voxel = require('./voxel')
const { baseName, isAir, slug } = require('./util')

function diff(target, readBlock, opts = {}) {
  const structure = opts.structure || opts.name || target.name || 'build'
  const jobs = []
  let placed = 0, air = 0, skipped = 0

  voxel.forEachSet(target, (lx, ly, lz, block, i) => {
    const w = voxel.worldOf(target, lx, ly, lz)
    const worldName = normalizeWorld(safeRead(readBlock, w.x, w.y, w.z))
    const targetIsAir = isAir(block)
    if (targetIsAir) {
      if (worldName === 'air') { skipped++; return }         // already clear — nothing to do
      air++
    } else {
      if (worldName === baseName(block)) { skipped++; return } // world already matches — additive skip
      placed++
    }
    const m = target.meta.get(i) || {}
    jobs.push({
      pos: { x: w.x, y: w.y, z: w.z },
      block,
      phase: m.phase || 'walls',
      region: m.region || 'build',
      _phaseNum: Number.isFinite(m.phaseNum) ? m.phaseNum : 50,
      _y: w.y, _z: w.z, _x: w.x
    })
  })

  jobs.sort((a, b) =>
    (a._phaseNum - b._phaseNum) || (a._y - b._y) || (a._z - b._z) || (a._x - b._x))

  const base = slug(structure)
  jobs.forEach((j, n) => {
    j.id = `${base}-${String(n + 1).padStart(6, '0')}`
    delete j._phaseNum; delete j._y; delete j._z; delete j._x
  })

  return {
    jobs, additive: true, placed, air, skipped,
    total: jobs.length,
    size: { ...target.size },
    origin: { ...target.originWorld }
  }
}

function safeRead(readBlock, x, y, z) {
  try { return readBlock(x, y, z) } catch { return null }
}

// Coerce a readBlock result to a bare block name; unknown/empty → 'air'.
function normalizeWorld(v) {
  if (v == null) return 'air'
  const name = typeof v === 'string' ? v : (v.name || '')
  const n = baseName(name)
  return n || 'air'
}

// Wrap a mineflayer bot into a readBlock(x,y,z) → name function.
function botReadBlock(bot) {
  const Vec3 = require('vec3')
  return (x, y, z) => {
    let b; try { b = bot.blockAt(new Vec3(x, y, z)) } catch { b = null }
    return b ? b.name : 'air'
  }
}

module.exports = { diff, botReadBlock, normalizeWorld }
