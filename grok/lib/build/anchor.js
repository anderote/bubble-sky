// grok/lib/build/anchor.js — SITE-AWARE terrain fit.
//
// anchorToSite(bp, site) adjusts a blueprint so it sits ON the landscape instead
// of floating or burying itself:
//   - sets anchor.origin.y from the surveyed ground (local y=0 == ground top);
//   - terrainFit "follow" (default): steps FOOTINGS down to the ground contour
//     per column so the foundation meets sloping ground; bounds grow downward;
//   - terrainFit "flatten": footings for dips + carve bumps above the pad;
//   - terrainFit "float": leave the y as authored;
//   - orients the entrance toward the lowest (open/downhill) edge.
//
// `site` carries { groundY } and an injectable `heightAt(worldX, worldZ)` that
// returns the world Y of the ground top at a column (real = bot raycast; tests =
// a synthetic function). Pure + deterministic given heightAt.

function anchorToSite(bp, site = {}) {
  const fit = String(bp.anchor.terrainFit || site.terrainFit || 'follow').toLowerCase()
  const groundY = Number.isFinite(+site.groundY) ? Math.round(+site.groundY) : bp.anchor.origin.y
  const heightAt = typeof site.heightAt === 'function' ? site.heightAt : null

  if (fit === 'float') {
    bp.anchor.terrainFit = 'float'
    return bp
  }

  // Floor sits so local y=0 lands on the ground top.
  bp.anchor.origin.y = groundY

  // Footprint columns in the anchor frame (bounds.min.x..max.x, .z..max.z).
  const { min, max } = bp.bounds
  const origin = bp.anchor.origin
  const groundOf = (lx, lz) => {
    if (!heightAt) return groundY
    const g = heightAt(origin.x + lx, origin.z + lz)
    return Number.isFinite(g) ? Math.round(g) : groundY
  }

  const footings = []
  const carves = []
  let deepest = 0
  let lowSum = { north: 0, south: 0, east: 0, west: 0 }, lowN = 0
  for (let lx = min.x; lx <= max.x; lx++) {
    for (let lz = min.z; lz <= max.z; lz++) {
      const g = groundOf(lx, lz) // world Y of ground top
      const base = origin.y - 1 // world Y of the cell just below the foundation (local -1)
      if (g < base) {
        // dip: fill footing from ground+1 up to base (local yBottom..-1)
        const yBottom = (g + 1) - origin.y // negative local y
        deepest = Math.min(deepest, yBottom)
        footings.push({ kind: 'line', role: 'foundation', block: bp.palette.foundation || bp.palette.wall,
          a: { x: lx, y: yBottom, z: lz }, b: { x: lx, y: -1, z: lz } })
      } else if (fit === 'flatten' && g > origin.y) {
        // bump above the pad: carve it away (air is allowed via carve)
        carves.push({ kind: 'carve', a: { x: lx, y: 1, z: lz }, b: { x: lx, y: g - origin.y, z: lz } })
      }
      // edge lowness for orientation
      const edge = (lx === min.x && 'west') || (lx === max.x && 'east') || (lz === min.z && 'north') || (lz === max.z && 'south')
      if (edge) { lowSum[edge] += g; lowN++ }
    }
  }

  if (footings.length) {
    bp.regions.unshift({ id: 'footings', name: 'Footings', phase: 0, components: footings })
    bp.bounds.min.y = Math.min(bp.bounds.min.y, deepest)
  }
  if (carves.length) {
    bp.regions.push({ id: 'site_flatten', name: 'Site Flatten', phase: 0, components: carves })
  }

  // Orient the entrance toward the lowest (most open/downhill) edge.
  if (heightAt) {
    const avg = {}
    for (const k of ['north', 'south', 'east', 'west']) {
      // average ground along that edge
      let s = 0, n = 0
      if (k === 'west' || k === 'east') { const lx = k === 'west' ? min.x : max.x; for (let lz = min.z; lz <= max.z; lz++) { s += groundOf(lx, lz); n++ } }
      else { const lz = k === 'north' ? min.z : max.z; for (let lx = min.x; lx <= max.x; lx++) { s += groundOf(lx, lz); n++ } }
      avg[k] = n ? s / n : groundY
    }
    let best = 'north', bestV = Infinity
    for (const k of ['north', 'south', 'east', 'west']) if (avg[k] < bestV) { bestV = avg[k]; best = k }
    bp.anchor.facing = best
  }

  bp.anchor.terrainFit = fit
  return bp
}

// Build a heightAt(worldX, worldZ) from a live mineflayer bot: scan a column for
// the highest solid ground block. Mirrors survey.isGround semantics.
function heightSampler(bot, refY) {
  const Vec3 = require('vec3')
  const isGround = (b) => {
    if (!b) return false
    const n = b.name
    if (n === 'air' || n === 'cave_air' || n === 'void_air' || n === 'water' || n === 'lava') return false
    if (/leaves|_log$|_wood$|vine|snow|grass$|fern|flower|mushroom|sapling|bamboo|sugar_cane|tall_grass/.test(n)) return false
    return b.boundingBox === 'block'
  }
  return (x, z) => {
    const yTop = Math.round(refY) + 24, yBot = Math.round(refY) - 24
    for (let y = yTop; y >= yBot; y--) {
      let b; try { b = bot.blockAt(new Vec3(x, y, z)) } catch { b = null }
      if (isGround(b)) return y
    }
    return Math.round(refY) - 1
  }
}

module.exports = { anchorToSite, heightSampler }
