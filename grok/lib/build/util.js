// grok/lib/build/util.js — shared helpers for the build pipeline.
// Small, dependency-free utilities so every module (voxel/compile/diff/state)
// talks about blocks and coordinates the same way.

// Coerce a size/count to an int in [lo,hi] with a default (mirrors assistant.clamp).
function clamp(v, lo, hi, def) {
  const n = parseInt(v, 10)
  return isNaN(n) ? def : Math.min(Math.max(n, lo), hi)
}

// Normalise a bare block *name* (strip minecraft:, lowercase, keep [a-z0-9_]).
// Used where a state suffix is NOT wanted (role fallbacks, palette values).
function B(s, def) {
  const x = String(s == null ? '' : s).toLowerCase().replace(/^minecraft:/, '').replace(/[^a-z0-9_]/g, '')
  return x || def
}

// Clean a full block string but KEEP its [state] suffix intact
// (e.g. "oak_stairs[facing=east,half=top]"). Mirrors hands.cleanBlock.
function cleanBlock(s, def) {
  const x = String(s == null ? '' : s).trim().toLowerCase()
    .replace(/^minecraft:/, '')
    .replace(/[^a-z0-9_:\[\]=,]/g, '')
  return x || (def || 'stone')
}

// The bare block name, no state — "oak_stairs[facing=east]" -> "oak_stairs".
function baseName(block) {
  const s = String(block == null ? '' : block).toLowerCase().replace(/^minecraft:/, '')
  const i = s.indexOf('[')
  return (i === -1 ? s : s.slice(0, i)).replace(/[^a-z0-9_]/g, '')
}

// True if a block string denotes air (the only thing diff may emit as deletion).
function isAir(block) {
  const n = baseName(block)
  return n === 'air' || n === 'cave_air' || n === 'void_air' || n === ''
}

// url-safe slug for task/job ids (matches Codex's compiler slug()).
function slug(text) {
  return String(text)
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '') || 'build'
}

const sort2 = (a, b) => (a <= b ? [a, b] : [b, a])

module.exports = { clamp, B, cleanBlock, baseName, isAir, slug, sort2 }
