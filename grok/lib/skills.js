// grok/lib/skills.js — unified skill registry.
// Merges structures.js generators, detail.js detailing skills, and a few raw
// primitives into one name->fn map. Every fn takes ONE args object with
// absolute world coordinates (so the Architect can compose blueprints directly).
// Both the one-shot router and the Executor run steps through run().
module.exports = function makeSkills({ hands, structures, details, B, clamp, log = () => {} }) {
  const reg = {}

  // Structure generators: pull origin {x,y,z} out of args, pass the rest through.
  for (const [name, gen] of Object.entries(structures.gens)) {
    reg[name] = (a) => gen(originOf(a), a)
  }
  // Aliases resolve to their generator.
  for (const [a, real] of Object.entries(structures.alias)) {
    if (!reg[a] && reg[real]) reg[a] = reg[real]
  }
  // Detailing skills already take absolute-coord arg objects.
  for (const [name, fn] of Object.entries(details.skills)) reg[name] = (a) => fn(a)

  // Raw primitives.
  reg.fill_box = (a) => hands.fillBox(a.x1, a.y1, a.z1, a.x2, a.y2, a.z2, a.block)
  reg.set_block = (a) => hands.setBlock(a.x, a.y, a.z, a.block)
  reg.dig_block = (a) => hands.dig(a.x, a.y, a.z)
  reg.clear_area = (a) => hands.clearArea(a.x, a.y, a.z, clamp(a.radius, 1, 60, 12), clamp(a.height, 1, 60, 20))
  // Foundation slab: a flat plate one block under origin, `radius` out (or a box).
  reg.foundation = (a) => {
    const o = originOf(a)
    if (a.x1 != null) return hands.fillBox(a.x1, o.y - 1, a.z1, a.x2, o.y - 1, a.z2, B(a.block, 'stone_bricks'))
    const r = clamp(a.radius || a.size, 1, 60, 10)
    return hands.fillBox(o.x - r, o.y - 1, o.z - r, o.x + r, o.y - 1, o.z + r, B(a.block, 'stone_bricks'))
  }
  reg.flatten = (a) => {
    const o = originOf(a), r = clamp(a.radius, 1, 60, 12), h = clamp(a.height, 1, 60, 12)
    hands.fillBox(o.x - r, o.y, o.z - r, o.x + r, o.y + h, o.z + r, 'air')
    return hands.fillBox(o.x - r, o.y - 1, o.z - r, o.x + r, o.y - 1, o.z + r, B(a.floor, 'grass_block'))
  }

  function originOf(a) {
    return { x: Math.round(a.x != null ? +a.x : 0), y: Math.round(a.y != null ? +a.y : 64), z: Math.round(a.z != null ? +a.z : 0) }
  }

  // Run one blueprint step; merge blueprint palette+origin defaults into its args.
  function run(step, defaults = {}) {
    const name = String(step.skill || '').trim()
    const fn = reg[name]
    if (!fn) { log('skills: unknown skill', name); return { ok: false, skill: name } }
    const args = Object.assign({}, step.args || {})
    // Fall back to blueprint origin when a structure step omits coords.
    if (args.x == null && defaults.origin) { args.x = defaults.origin.x; args.y = defaults.origin.y; args.z = defaults.origin.z }
    else if (args.y == null && defaults.origin) args.y = defaults.origin.y
    try { const r = fn(args); return { ok: true, skill: name, n: typeof r === 'number' ? r : undefined } }
    catch (e) { log('skills: error in', name, e.message); return { ok: false, skill: name, err: e.message } }
  }

  const kinds = Object.keys(reg)
  const structureCatalog = structures.catalog
  const detailCatalog = details.catalog
  return { reg, run, kinds, structureCatalog, detailCatalog, buildKinds: [...Object.keys(structures.gens), ...Object.keys(structures.alias)] }
}
