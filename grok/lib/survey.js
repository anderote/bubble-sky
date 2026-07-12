// grok/lib/survey.js — SITE AWARENESS + world observation.
// surveySite() scans the terrain around an origin (heightmap, slope, water/lava,
// biome, obstacles) BEFORE the Architect plans, so the build integrates with the
// landscape instead of floating or clipping into a hill. observe()/coverage()
// sample the world AFTER building so the goal loop can verify + repair.
const Vec3 = require('vec3')

function isGround(b) {
  if (!b) return false
  const n = b.name
  if (n === 'air' || n === 'cave_air' || n === 'void_air' || n === 'water' || n === 'lava') return false
  if (/leaves|_log$|_wood$|vine|snow|grass$|fern|flower|mushroom|sapling|bamboo|sugar_cane|tall_grass/.test(n)) return false
  return b.boundingBox === 'block'
}

// Survey a square footprint of side (2*radius+1) around origin.
function surveySite(bot, origin, radius) {
  const r = Math.max(4, Math.min(30, radius | 0 || 16))
  const stride = r > 12 ? 3 : 2
  const heights = []
  let hasWater = false, hasLava = false, obstacles = 0, sampled = 0
  const yTop = Math.round(origin.y) + 24, yBot = Math.round(origin.y) - 20
  for (let x = origin.x - r; x <= origin.x + r; x += stride) {
    for (let z = origin.z - r; z <= origin.z + r; z += stride) {
      sampled++
      let top = null, colObstacle = false
      for (let y = yTop; y >= yBot; y--) {
        let b; try { b = bot.blockAt(new Vec3(x, y, z)) } catch { b = null }
        if (!b) continue
        if (b.name === 'water') { hasWater = true; continue }
        if (b.name === 'lava') { hasLava = true; continue }
        if (isGround(b)) { top = y; break }
        // solid-ish stuff above ground that isn't natural cover => obstacle (build/tree trunk)
        if (b.boundingBox === 'block' && !/leaves|snow/.test(b.name)) colObstacle = true
      }
      if (top != null) { heights.push(top); if (colObstacle) obstacles++ }
    }
  }
  let biome; try { biome = bot.blockAt(new Vec3(origin.x, origin.y, origin.z))?.biome?.name } catch {}
  if (!heights.length) {
    const g = Math.round(origin.y) - 1
    return { groundY: g, minY: g, maxY: g, slope: 0, hasWater, hasLava, biome, obstacles, samples: 0, note: 'terrain unloaded — assumed flat at origin' }
  }
  heights.sort((a, b) => a - b)
  const groundY = heights[Math.floor(heights.length / 2)]
  const minY = heights[0], maxY = heights[heights.length - 1]
  return {
    groundY, minY, maxY, slope: maxY - minY, hasWater, hasLava, biome, obstacles, samples: heights.length,
    note: `${maxY - minY <= 1 ? 'flat' : (maxY - minY <= 4 ? 'gently sloped' : 'steep')} ground near Y=${groundY}${hasWater ? ', water present' : ''}${obstacles > heights.length / 6 ? ', existing obstacles/trees' : ''}`
  }
}

// Count solid vs air over a sampled grid+height range — a cheap "is there a
// building here" probe.
function observe(bot, origin, radius, yLo, yHi) {
  const r = Math.max(2, radius | 0), stride = r > 12 ? 3 : 2
  let solid = 0, total = 0
  for (let x = origin.x - r; x <= origin.x + r; x += stride)
    for (let z = origin.z - r; z <= origin.z + r; z += stride)
      for (let y = yLo; y <= yHi; y += 2) {
        total++
        let b; try { b = bot.blockAt(new Vec3(x, y, z)) } catch { b = null }
        if (b && b.name !== 'air' && b.name !== 'cave_air') solid++
      }
  return { solid, total, ratio: total ? solid / total : 0 }
}

// Perimeter coverage at a given height ring — how much of the wall footprint edge
// is actually solid. Used to verify a shell/wall phase really landed.
function coverage(bot, origin, radius, y) {
  const r = Math.max(2, radius | 0)
  let solid = 0, total = 0
  const check = (x, z) => { total++; let b; try { b = bot.blockAt(new Vec3(x, y, z)) } catch { b = null } if (b && b.name !== 'air' && b.name !== 'cave_air') solid++ }
  for (let x = origin.x - r; x <= origin.x + r; x++) { check(x, origin.z - r); check(x, origin.z + r) }
  for (let z = origin.z - r + 1; z < origin.z + r; z++) { check(origin.x - r, z); check(origin.x + r, z) }
  return { solid, total, ratio: total ? solid / total : 0 }
}

module.exports = { surveySite, observe, coverage }
