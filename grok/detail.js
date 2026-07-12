// grok/detail.js — DETAILING skill library. These are the touches that make a
// plain box read as a real building: trim bands, pillar grids, framed windows,
// multi-depth wall greebling, plinths, roof overhangs, interior lighting cadence,
// battlements, banners, gabled/conical roofs.
//
// Every skill takes ONE object of ABSOLUTE world coordinates + params (so the
// Architect can compose them directly in a blueprint). ctx supplies hands-backed
// primitives { set, fillBox, B, clamp } — no raw commands here.
module.exports = function makeDetails(ctx) {
  const { set, fillBox, B, clamp } = ctx
  const sort2 = (a, b) => [Math.min(a, b), Math.max(a, b)]

  // Derive the stair/slab material base from any block or *_stairs name.
  //   stone_bricks -> stone_brick ; dark_oak_stairs -> dark_oak ; oak_planks -> oak
  function baseOf(block, def = 'oak') {
    let b = B(block, def).replace(/\[.*$/, '')
    b = b.replace(/_(stairs|slab|wall)$/, '')
    if (b.endsWith('_planks')) b = b.slice(0, -7)
    else if (/_(log|wood|stem|hyphae)$/.test(b)) b = b.replace(/_(log|wood|stem|hyphae)$/, '')
    else if (b === 'stone_bricks') b = 'stone_brick'
    else if (b === 'bricks') b = 'brick'
    else if (b === 'deepslate_tiles') b = 'deepslate_tile'
    else if (b.endsWith('_bricks')) b = b.slice(0, -1)   // deepslate_bricks -> deepslate_brick
    else if (b.endsWith('_tiles')) b = b.slice(0, -1)
    b = b.replace(/^stripped_/, '')   // stripped_oak(_log) -> oak: slab/stair are the plain-wood variants
    return b
  }
  const stairs = (block, def) => `${baseOf(block, def)}_stairs`
  const slab = (block, def) => `${baseOf(block, def)}_slab`
  const CARD = ['north', 'south', 'east', 'west']

  // Contrasting horizontal band around a footprint perimeter at height y.
  function trimCourse(a) {
    let { x1, z1, x2, z2, y, block } = a
    ;[x1, x2] = sort2(x1, x2);[z1, z2] = sort2(z1, z2); block = B(block, 'polished_andesite')
    fillBox(x1, y, z1, x2, y, z1, block); fillBox(x1, y, z2, x2, y, z2, block)
    fillBox(x1, y, z1, x1, y, z2, block); fillBox(x2, y, z1, x2, y, z2, block)
  }

  // Structural posts every `every` blocks along all four walls, y0..y1.
  function pillarGrid(a) {
    let { x1, z1, x2, z2, y0, y1, every, block } = a
    ;[x1, x2] = sort2(x1, x2);[z1, z2] = sort2(z1, z2)
    const n = clamp(every, 2, 12, 4); block = B(block, 'oak_log')
    for (let x = x1; x <= x2; x++) if ((x - x1) % n === 0 || x === x2) { fillBox(x, y0, z1, x, y1, z1, block); fillBox(x, y0, z2, x, y1, z2, block) }
    for (let z = z1; z <= z2; z++) if ((z - z1) % n === 0 || z === z2) { fillBox(x1, y0, z, x1, y1, z, block); fillBox(x2, y0, z, x2, y1, z, block) }
  }

  // A single framed window: glass inset, log/trapdoor frame, a slab sill.
  // axis = the wall's run ('x' wall faces ±z, 'z' wall faces ±x). (x,y,z)=lower-left of glass.
  function windowFrame(a) {
    let { x, y, z, axis, width, height, glass, frame, sill } = a
    const w = clamp(width, 1, 6, 2), h = clamp(height, 1, 6, 2)
    glass = B(glass, 'glass_pane'); frame = B(frame, 'stripped_oak_log'); const sillB = slab(sill || frame, 'oak')
    const along = (axis || 'x') === 'x' ? 'x' : 'z'
    for (let i = -1; i <= w; i++) for (let dy = -1; dy <= h; dy++) {
      const edge = i < 0 || i >= w || dy < 0 || dy >= h
      const px = along === 'x' ? x + i : x, pz = along === 'z' ? z + i : z, py = y + dy
      if (edge) { if (dy < 0) continue; set(px, py, pz, frame) }         // frame (skip below the sill line)
      else set(px, py, pz, glass)                                        // glazing
    }
    // sill: a slab shelf one below the glass, one wider each side
    for (let i = -1; i <= w; i++) { const px = along === 'x' ? x + i : x, pz = along === 'z' ? z + i : z; set(px, y - 1, pz, sillB) }
  }

  // Lights around a perimeter every `every` blocks at height y (exterior torch cadence).
  function torchCadence(a) {
    let { x1, z1, x2, z2, y, every, block } = a
    ;[x1, x2] = sort2(x1, x2);[z1, z2] = sort2(z1, z2)
    const n = clamp(every, 2, 12, 4); block = B(block, 'lantern')
    for (let x = x1; x <= x2; x++) if ((x - x1) % n === 0) { set(x, y, z1, block); set(x, y, z2, block) }
    for (let z = z1; z <= z2; z++) if ((z - z1) % n === 0) { set(x1, y, z, block); set(x2, y, z, block) }
  }

  // INTERIOR lighting so no spot is dark: a lantern grid across a floor plan.
  // Spacing ~every (default 5 ≈ light level stays >0 everywhere). Tall rooms hang
  // the lanterns from the ceiling (yTop) instead of sitting them on the floor.
  function lightingCadence(a) {
    let { x1, z1, x2, z2, y0, yTop, every, block } = a
    ;[x1, x2] = sort2(x1, x2);[z1, z2] = sort2(z1, z2)
    const n = clamp(every, 3, 10, 5); block = B(block, 'lantern')
    const tall = yTop != null && (yTop - y0) >= 6
    const hangY = yTop != null ? yTop - 1 : (y0 != null ? y0 + 3 : y0)
    const isRod = /end_rod/.test(block)
    for (let x = x1 + 1; x < x2; x += n) for (let z = z1 + 1; z < z2; z += n) {
      if (yTop != null) set(x, hangY, z, isRod ? 'end_rod' : `${block}[hanging=${tall}]`)
      else set(x, y0, z, block)
    }
  }

  // A ROW of wall banners along one wall at height y (great-hall dressing).
  function bannerRow(a) {
    let { x1, z1, x2, z2, y, axis, color, every } = a
    ;[x1, x2] = sort2(x1, x2);[z1, z2] = sort2(z1, z2)
    const c = B(color, 'red'), n = clamp(every, 2, 10, 4)
    if ((axis || 'x') === 'x') { for (let x = x1; x <= x2; x++) if ((x - x1) % n === 0) { set(x, y, z1, `${c}_wall_banner[facing=south]`); set(x, y, z2, `${c}_wall_banner[facing=north]`) } }
    else { for (let z = z1; z <= z2; z++) if ((z - z1) % n === 0) { set(x1, y, z, `${c}_wall_banner[facing=east]`); set(x2, y, z, `${c}_wall_banner[facing=west]`) } }
  }

  // Windows punched along all four walls of a footprint, `height` tall, spaced.
  function windowGrid(a) {
    let { x1, z1, x2, z2, y0, spacing, height, block } = a
    ;[x1, x2] = sort2(x1, x2);[z1, z2] = sort2(z1, z2)
    const s = clamp(spacing, 2, 12, 3), h = clamp(height, 1, 6, 2); block = B(block, 'glass_pane')
    for (let x = x1 + 2; x < x2 - 1; x += s) for (let dy = 0; dy < h; dy++) { set(x, y0 + dy, z1, block); set(x, y0 + dy, z2, block) }
    for (let z = z1 + 2; z < z2 - 1; z += s) for (let dy = 0; dy < h; dy++) { set(x1, y0 + dy, z, block); set(x2, y0 + dy, z, block) }
  }

  // Break up flat faces: alternate insets (accent) and outset stair/slab greebling
  // so a long wall has DEPTH instead of a single flat plane. y0..y1.
  function wallGreeble(a) {
    let { x1, z1, x2, z2, y0, y1, wall, accent } = a
    ;[x1, x2] = sort2(x1, x2);[z1, z2] = sort2(z1, z2)
    wall = B(wall, 'stone_bricks'); accent = B(accent, 'cobblestone')
    const sSlab = slab(wall), sStair = stairs(wall)
    const midY = Math.floor((y0 + y1) / 2)
    // vertical accent pilasters every 3 along the long walls, with a slab outset base + stair cap
    const doWall = (fixedIsZ, fixed, from, to, outDir) => {
      for (let t = from + 2; t < to - 1; t += 3) {
        const px = fixedIsZ ? t : fixed, pz = fixedIsZ ? fixed : t
        fillBox(px, y0, pz, px, y1, pz, accent)                        // recessed accent stripe
        // outset detail: slab at base, stair mid, slab cap — one block proud (outDir)
        const ox = fixedIsZ ? px : px + outDir, oz = fixedIsZ ? pz + outDir : pz
        set(ox, y0, oz, sSlab); set(ox, midY, oz, sStair); set(ox, y1, oz, sSlab)
      }
    }
    doWall(true, z1, x1, x2, -1); doWall(true, z2, x1, x2, +1)
    doWall(false, x1, z1, z2, -1); doWall(false, x2, z1, z2, +1)
  }

  // A wider 1-block base course (plinth) that steps the footprint out by `out`.
  function plinth(a) {
    let { x1, z1, x2, z2, y, out, block } = a
    ;[x1, x2] = sort2(x1, x2);[z1, z2] = sort2(z1, z2)
    const o = clamp(out, 1, 3, 1); block = B(block, 'stone_bricks')
    fillBox(x1 - o, y, z1 - o, x2 + o, y, z2 + o, block)               // solid wider base
    // stair skirt around the top edge so it reads as a moulded plinth
    const st = stairs(block)
    fillBox(x1 - o, y + 1, z1 - o, x2 + o, y + 1, z1 - o, `${st}[facing=south,half=top]`)
    fillBox(x1 - o, y + 1, z2 + o, x2 + o, y + 1, z2 + o, `${st}[facing=north,half=top]`)
    fillBox(x1 - o, y + 1, z1 - o, x1 - o, y + 1, z2 + o, `${st}[facing=east,half=top]`)
    fillBox(x2 + o, y + 1, z1 - o, x2 + o, y + 1, z2 + o, `${st}[facing=west,half=top]`)
  }

  // Extend the eaves 1 block beyond the wall line with upside-down stairs + a slab
  // lip, all around a footprint at height y (the wall-top line).
  function roofOverhang(a) {
    let { x1, z1, x2, z2, y, block } = a
    ;[x1, x2] = sort2(x1, x2);[z1, z2] = sort2(z1, z2)
    const st = stairs(block, 'spruce'), sl = slab(block, 'spruce')
    // inverted stairs one block out, tucked under the roof line
    fillBox(x1 - 1, y, z1 - 1, x2 + 1, y, z1 - 1, `${st}[facing=south,half=top]`)
    fillBox(x1 - 1, y, z2 + 1, x2 + 1, y, z2 + 1, `${st}[facing=north,half=top]`)
    fillBox(x1 - 1, y, z1 - 1, x1 - 1, y, z2 + 1, `${st}[facing=east,half=top]`)
    fillBox(x2 + 1, y, z1 - 1, x2 + 1, y, z2 + 1, `${st}[facing=west,half=top]`)
    fillBox(x1 - 1, y + 1, z1 - 1, x2 + 1, y + 1, z2 + 1, sl)          // thin slab lip corner tidy
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
      set(x - hw - 1, y + h - 1, z, `${stairs(block)}[facing=east,half=top]`); set(x + hw + 1, y + h - 1, z, `${stairs(block)}[facing=west,half=top]`)
    } else {
      fillBox(x, y, z - hw, x, y + h - 1, z + hw, 'air')
      fillBox(x, y + h, z - hw - 1, x, y + h, z + hw + 1, block)
      set(x, y + h - 1, z - hw - 1, `${stairs(block)}[facing=south,half=top]`); set(x, y + h - 1, z + hw + 1, `${stairs(block)}[facing=north,half=top]`)
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

  // Gabled roof over a footprint, ridge along the LONGER axis, stairs on slope,
  // each course bordered with a slab lip for a defined pitch.
  function roofGable(a) {
    let { x1, z1, x2, z2, y, block } = a
    ;[x1, x2] = sort2(x1, x2);[z1, z2] = sort2(z1, z2)
    const st = stairs(block, 'spruce'), sl = slab(block, 'spruce')
    const spanX = x2 - x1, spanZ = z2 - z1
    if (spanX <= spanZ) { // ridge along z, slope on x
      const half = spanX >> 1
      for (let i = 0; i <= half; i++) {
        const yy = y + i, xl = x1 + i, xr = x2 - i; if (xl > xr) break
        fillBox(xl, yy, z1 - 1, xl, yy, z2 + 1, `${st}[facing=east]`)
        fillBox(xr, yy, z1 - 1, xr, yy, z2 + 1, `${st}[facing=west]`)
        if (xl === xr) fillBox(xl, yy, z1 - 1, xl, yy, z2 + 1, sl)
      }
    } else { // ridge along x, slope on z
      const half = spanZ >> 1
      for (let i = 0; i <= half; i++) {
        const yy = y + i, zl = z1 + i, zr = z2 - i; if (zl > zr) break
        fillBox(x1 - 1, yy, zl, x2 + 1, yy, zl, `${st}[facing=south]`)
        fillBox(x1 - 1, yy, zr, x2 + 1, yy, zr, `${st}[facing=north]`)
        if (zl === zr) fillBox(x1 - 1, yy, zl, x2 + 1, yy, zl, sl)
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

  const skills = {
    trimCourse, pillarGrid, windowFrame, torchCadence, lightingCadence, bannerRow,
    windowGrid, wallGreeble, plinth, roofOverhang, archway, cornerPillars,
    crenellate, roofGable, roofCone
  }

  // Human-readable catalog for the Architect prompt.
  const catalog = [
    ['trimCourse', '{x1,z1,x2,z2,y,block}', 'contrasting band around footprint at a floor/roof line'],
    ['pillarGrid', '{x1,z1,x2,z2,y0,y1,every,block}', 'structural posts every N along all walls (breaks flat walls)'],
    ['windowFrame', '{x,y,z,axis,width,height,glass,frame,sill}', 'ONE framed window: glass inset + log frame + slab sill'],
    ['wallGreeble', '{x1,z1,x2,z2,y0,y1,wall,accent}', 'give walls DEPTH: recessed accent stripes + outset slab/stair pilasters'],
    ['plinth', '{x1,z1,x2,z2,y,out,block}', 'wider stepped base course with a stair skirt'],
    ['roofOverhang', '{x1,z1,x2,z2,y,block}', 'extend eaves 1 block with inverted stairs + slab lip'],
    ['lightingCadence', '{x1,z1,x2,z2,y0,yTop,every,block}', 'INTERIOR light grid so no spot is dark (hangs in tall rooms)'],
    ['bannerRow', '{x1,z1,x2,z2,y,axis,color,every}', 'wall banners along a wall'],
    ['torchCadence', '{x1,z1,x2,z2,y,every,block}', 'exterior lights around perimeter every N blocks'],
    ['windowGrid', '{x1,z1,x2,z2,y0,spacing,height,block}', 'plain windows down all four walls'],
    ['archway', '{x,y,z,width,height,axis,block}', 'gate arch: carve opening + framed stair top (axis x|z)'],
    ['cornerPillars', '{x1,z1,x2,z2,y0,y1,block}', 'vertical pillars at the four corners'],
    ['crenellate', '{x1,z1,x2,z2,y,block}', 'battlement merlons around perimeter top'],
    ['roofGable', '{x1,z1,x2,z2,y,block}', 'pitched gable roof (stairs + slab lip) over footprint'],
    ['roofCone', '{x,z,y,radius,height,block}', 'conical spire roof for round towers']
  ]
  return { skills, catalog, baseOf, stairs, slab }
}
