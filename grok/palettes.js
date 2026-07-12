// grok/palettes.js — named, cohesive MATERIAL PALETTES.
//
// A palette assigns a real Minecraft block to each building ROLE so a build reads
// as one designed thing instead of a mono-material box. Roles:
//   wall       — the main field material of the walls
//   trim       — contrasting band / corner / beam material (framing)
//   accent     — secondary field material for greebling / variation
//   floor      — interior floor
//   roof       — roof material (a *_stairs block; slabs derived from it)
//   pillar     — vertical structural posts / beams (often a log)
//   glass      — window material (pane or full block)
//   light      — light source (lantern / sea_lantern / end_rod …)
//   path       — ground path material outside the door
//   foundation — the plinth / base course material
//   detail     — a chiseled / decorative feature block
// Extra (optional) keys used by furniture: carpet, bed, banner.
//
// Rule of thumb baked into every palette: wall/trim/accent are SIMILAR-toned so
// the variation is subtle, never garish; roof + light pick out the silhouette.

const ROLES = ['wall', 'trim', 'accent', 'floor', 'roof', 'pillar', 'glass', 'light', 'path', 'foundation', 'detail', 'window']

const PALETTES = {
  // Classic stone-brick + dark timber cottage/keep look. blockus stone + shingles
  // give the field + roof more character than plain vanilla.
  medieval: {
    wall: 'stone_bricks', trim: 'dark_oak_log', accent: 'blockus:gray_stone_bricks', floor: 'spruce_planks',
    roof: 'blockus:gray_shingles', pillar: 'oak_log', glass: 'glass_pane', light: 'lantern',
    path: 'blockus:bluestone', foundation: 'cobblestone', detail: 'blockus:chiseled_limestone',
    window: 'mcwwindows:oak_window2', carpet: 'red_carpet', bed: 'red', banner: 'red'
  },
  // Cosy whitewashed timber-frame cottage.
  cottage: {
    wall: 'oak_planks', trim: 'stripped_spruce_log', accent: 'white_terracotta', floor: 'spruce_planks',
    roof: 'spruce_stairs', pillar: 'stripped_oak_log', glass: 'glass_pane', light: 'lantern',
    path: 'gravel', foundation: 'cobblestone', detail: 'mossy_cobblestone',
    carpet: 'white_carpet', bed: 'white', banner: 'yellow'
  },
  // Otherworldly deepslate + prismarine with warped/purpur accents.
  fantasy: {
    wall: 'deepslate_bricks', trim: 'prismarine_bricks', accent: 'purpur_block', floor: 'warped_planks',
    roof: 'prismarine_stairs', pillar: 'crimson_stem', glass: 'light_blue_stained_glass_pane', light: 'sea_lantern',
    path: 'prismarine', foundation: 'polished_deepslate', detail: 'chiseled_deepslate',
    carpet: 'cyan_carpet', bed: 'cyan', banner: 'purple'
  },
  // Clean concrete + quartz + glass minimalism.
  modern: {
    wall: 'white_concrete', trim: 'gray_concrete', accent: 'smooth_quartz', floor: 'smooth_stone',
    roof: 'smooth_quartz_stairs', pillar: 'gray_concrete', glass: 'glass', light: 'end_rod',
    path: 'light_gray_concrete', foundation: 'gray_concrete', detail: 'quartz_pillar',
    carpet: 'light_gray_carpet', bed: 'light_gray', banner: 'light_gray'
  },
  // Warm tavern / rustic timber + cobble (manor/estate).
  rustic: {
    wall: 'spruce_planks', trim: 'stripped_dark_oak_log', accent: 'blockus:limestone', floor: 'dark_oak_planks',
    roof: 'blockus:gray_shingles', pillar: 'dark_oak_log', glass: 'glass_pane', light: 'lantern',
    path: 'dirt_path', foundation: 'cobblestone', detail: 'blockus:chiseled_limestone',
    window: 'mcwwindows:spruce_window2', carpet: 'brown_carpet', bed: 'brown', banner: 'orange'
  },
  // Heavy fortress stone with blockus marble/limestone dressing + shingle roofs.
  castle: {
    wall: 'stone_bricks', trim: 'blockus:gray_stone_bricks', accent: 'blockus:small_marble_bricks', floor: 'blockus:marble_tiles',
    roof: 'blockus:gray_shingles', pillar: 'blockus:marble_pillar', glass: 'glass_pane', light: 'lantern',
    path: 'blockus:bluestone', foundation: 'cobblestone', detail: 'blockus:chiseled_limestone',
    window: 'mcwwindows:stone_window2', carpet: 'red_carpet', bed: 'red', banner: 'red'
  }
}

// Style aliases → canonical palette key.
const ALIAS = {
  tavern: 'rustic', inn: 'rustic', farmhouse: 'cottage', cabin: 'cottage', house: 'cottage',
  keep: 'medieval', fort: 'castle', fortress: 'castle', stone: 'castle',
  magic: 'fantasy', wizard: 'fantasy', elven: 'fantasy', enchanted: 'fantasy',
  contemporary: 'modern', minimalist: 'modern', futuristic: 'modern'
}

function paletteKey(name) {
  const k = String(name || '').toLowerCase().replace(/[^a-z]/g, '')
  if (PALETTES[k]) return k
  if (ALIAS[k]) return ALIAS[k]
  return 'medieval'
}

// Some LLM tool-call backends occasionally serialize an object field as an XML-ish
// STRING, e.g. '\n<parameter name="wall">stone_bricks</parameter>...'. Salvage any
// role/value pairs out of such a string so a mangled palette still resolves.
function coercePalette(pal) {
  if (pal && typeof pal === 'object' && !Array.isArray(pal)) return pal
  if (typeof pal !== 'string') return {}
  if (!/name\s*=/.test(pal)) return null   // a plain style name, not markup
  const out = {}
  const re = /name\s*=\s*["']?([a-z_]+)["']?\s*>\s*([a-z0-9_:]+)/gi
  let m
  while ((m = re.exec(pal))) { const role = m[1].toLowerCase(); if (ROLES.includes(role)) out[role] = m[2].replace(/^minecraft:/, '') }
  return out
}

// Resolve a palette to a FULL role set.
//  - name string → look up by style (fallback medieval), OR salvage an XML-mangled
//                  palette string into role overrides.
//  - object      → treat as role overrides merged onto a base (its `style`/`base` key,
//                  else medieval), so partial palettes (just wall/roof) still fill in
//                  light/floor/etc.
//  - undefined   → medieval
// `overrides` (2nd arg) always win last.
function resolvePalette(name, overrides) {
  let base, over = {}
  const salvaged = typeof name === 'string' ? coercePalette(name) : null
  if (name && typeof name === 'object') {
    base = PALETTES[paletteKey(name.style || name.base)]
    over = name
  } else if (salvaged) {                    // mangled markup string → use extracted roles
    base = PALETTES[paletteKey(salvaged.wall)] || PALETTES.medieval
    over = salvaged
  } else {
    base = PALETTES[paletteKey(name)]
  }
  return Object.assign({}, base, over, overrides || {})
}

// Names for prompts.
const styleNames = Object.keys(PALETTES)

module.exports = { PALETTES, ROLES, ALIAS, paletteKey, resolvePalette, coercePalette, styleNames }
