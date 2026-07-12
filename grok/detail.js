// grok/detail.js — detailing skill library. These are the touches that make a
// plain box read as a real building: trim bands, torch cadence, window grids,
// archways, corner pillars, crenellations, gabled/conical roofs.
//
// Every skill takes ONE object of ABSOLUTE world coordinates + params (so the
// Architect can compose them directly in a blueprint). ctx supplies hands-backed
// primitives { set, fillBox, B, clamp } — no raw commands here.
module.exports = function makeDetails(ctx) {
  const { set, fillBox, B, clamp } = ctx
  const sort2 = (a, b) => [Math.min(a, b), Math.max(a, b)]

  // Contrasting horizontal band around a footprint perimeter at height y.
  function trimCourse(a) {
    let { x1, z1, x2, z2, y, block } = a
    ;[x1, x2] = sort2(x1, x2);[z1, z2] = sort2(z1, z2); block = B(block, 'polished_andesite')
    fillBox(x1, y, z1, x2, y, z1, block); fillBox(x1, y, z2, x2, y, z2, block)
    fillBox(x1, y, z1, x1, y, z2, block); fillBox(x2, y, z1, x2, y, z2, block)
  }

  // Lights around a perimeter every `every` blocks at height y.
  function torchCadence(a) {
    let { x1, z1, x2, z2, y, every, block } = a
    ;[x1, x2] = sort2(x1, x2);[z1, z2] = sort2(z1, z2)
    const n = clamp(every, 2, 12, 4); block = B(block, 'lantern')
    for (let x = x1; x <= x2; x++) if ((x - x1) % n === 0) { set(x, y, z1, block); set(x, y, z2, block) }
    for (let z = z1; z <= z2; z++) if ((z - z1) % n === 0) { set(x1, y, z, block); set(x2, y, z, block) }
  }

  // Windows punched along all four walls of a footprint, `height` tall, spaced.
  function windowGrid(a) {
    let { x1, z1, x2, z2, y0, spacing, height, block } = a
    ;[x1, x2] = sort2(x1, x2);[z1, z2] = sort2(z1, z2)
    const s = clamp(spacing, 2, 12, 3), h = clamp(height, 1, 6, 2); block = B(block, 'glass_pane')
    for (let x = x1 + 2; x < x2 - 1; x += s) for (let dy = 0; dy < h; dy++) { set(x, y0 + dy, z1, block); set(x, y0 + dy, z2, block) }
    for (let z = z1 + 2; z < z2 - 1; z += s) for (let dy = 0; dy < h; dy++) { set(x1, y0 + dy, z, block); set(x2, y0 + dy, z, block) }
  }

  // A gate arch: carve an opening and frame it. axis = wall orientation the
  // opening sits in ('x' wall runs along x, opening faces z; 'z' vice versa).
  function archway(a) {
    let { x, y, z, width, height, axis, block } = a
    const hw = (clamp(width, 1, 11, 3) - 1) >> 1, h = clamp(height, 2, 12, 4); block = B(block, 'stone_bricks')
    if ((axis || 'x') === 'x') {
      fillBox(x - hw, y, z, x + hw, y + h - 1, z, 'air')
      fillBox(x - hw - 1, y + h, z, x + hw + 1, y + h, z, block)
      set(x - hw - 1, y + h - 1, z, block); set(x + hw + 1, y + h - 1, z, block)
    } else {
      fillBox(x, y, z - hw, x, y + h - 1, z + hw, 'air')
      fillBox(x, y + h, z - hw - 1, x, y + h, z + hw + 1, block)
      set(x, y + h - 1, z - hw - 1, block); set(x, y + h - 1, z + hw + 1, block)
    }
  }

  // Solid vertical pillars at the four corners of a footprint, y0..y1.
  function cornerPillars(a) {
    let { x1, z1, x2, z2, y0, y1, block } = a
    ;[x1, x2] = sort2(x1, x2);[z1, z2] = sort2(z1, z2); block = B(block, 'stone_bricks')
    for (const [cx, cz] of [[x1, z1], [x1, z2], [x2, z1], [x2, z2]]) fillBox(cx, y0, cz, cx, y1, cz, block)
  }

  // Merlons (battlements) around a footprint perimeter at height y.
  function crenellate(a) {
    let { x1, z1, x2, z2, y, block } = a
    ;[x1, x2] = sort2(x1, x2);[z1, z2] = sort2(z1, z2); block = B(block, 'stone_bricks')
    for (let x = x1; x <= x2; x++) if ((x - x1) % 2 === 0) { set(x, y, z1, block); set(x, y, z2, block) }
    for (let z = z1; z <= z2; z++) if ((z - z1) % 2 === 0) { set(x1, y, z, block); set(x2, y, z, block) }
  }

  // Gabled roof over a footprint, ridge along the LONGER axis, stairs on slope.
  function roofGable(a) {
    let { x1, z1, x2, z2, y, block } = a
    ;[x1, x2] = sort2(x1, x2);[z1, z2] = sort2(z1, z2)
    const rf = B(block, 'spruce').replace(/_(planks|log)$/, '')
    const spanX = x2 - x1, spanZ = z2 - z1
    if (spanX <= spanZ) { // ridge along z, slope on x
      const half = spanX >> 1
      for (let i = 0; i <= half; i++) {
        const yy = y + i, xl = x1 + i, xr = x2 - i; if (xl > xr) break
        fillBox(xl, yy, z1 - 1, xl, yy, z2 + 1, `${rf}_stairs[facing=east]`)
        fillBox(xr, yy, z1 - 1, xr, yy, z2 + 1, `${rf}_stairs[facing=west]`)
        if (xl === xr) fillBox(xl, yy, z1 - 1, xl, yy, z2 + 1, `${rf}_slab`)
      }
    } else { // ridge along x, slope on z
      const half = spanZ >> 1
      for (let i = 0; i <= half; i++) {
        const yy = y + i, zl = z1 + i, zr = z2 - i; if (zl > zr) break
        fillBox(x1 - 1, yy, zl, x2 + 1, yy, zl, `${rf}_stairs[facing=south]`)
        fillBox(x1 - 1, yy, zr, x2 + 1, yy, zr, `${rf}_stairs[facing=north]`)
        if (zl === zr) fillBox(x1 - 1, yy, zl, x2 + 1, yy, zl, `${rf}_slab`)
      }
    }
  }

  // Conical / spire roof centred at (x,z), sitting on top at height y.
  function roofCone(a) {
    let { x, z, y, radius, height, block } = a
    const r = clamp(radius, 2, 16, 4), h = clamp(height, 2, 24, r + 2); block = B(block, 'deepslate_tiles')
    for (let i = 0; i <= h; i++) {
      const rr = r * (1 - i / h), yy = y + i
      for (let dx = -r; dx <= r; dx++) for (let dz = -r; dz <= r; dz++) {
        const d = Math.hypot(dx, dz)
        if (d <= rr + 0.4 && d > rr - 0.95) set(x + dx, yy, z + dz, block)
      }
    }
    set(x, y + h, z, block)
  }

  const skills = { trimCourse, torchCadence, windowGrid, archway, cornerPillars, crenellate, roofGable, roofCone }

  // Human-readable catalog for the Architect prompt.
  const catalog = [
    ['trimCourse', '{x1,z1,x2,z2,y,block}', 'contrasting band around footprint at a floor line'],
    ['torchCadence', '{x1,z1,x2,z2,y,every,block}', 'lights/torches around perimeter every N blocks'],
    ['windowGrid', '{x1,z1,x2,z2,y0,spacing,height,block}', 'windows down all four walls'],
    ['archway', '{x,y,z,width,height,axis,block}', 'gate arch: carve opening + frame (axis x|z)'],
    ['cornerPillars', '{x1,z1,x2,z2,y0,y1,block}', 'vertical pillars at the four corners'],
    ['crenellate', '{x1,z1,x2,z2,y,block}', 'battlement merlons around perimeter top'],
    ['roofGable', '{x1,z1,x2,z2,y,block}', 'pitched gable roof (stairs) over footprint'],
    ['roofCone', '{x,z,y,radius,height,block}', 'conical spire roof for round towers']
  ]
  return { skills, catalog }
}
