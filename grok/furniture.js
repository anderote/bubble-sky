// grok/furniture.js — FURNITURE builders + furnishRoom.
//
// Each builder places blocks at a position + facing to make a piece of furniture
// out of vanilla blocks + blockstates (stairs, trapdoors, barrels, beds…). They
// are FUNCTIONAL components: mostly single-point /setblock work with state props.
//
// Every builder takes ONE args object with ABSOLUTE world coords (so the Architect
// composes them directly) plus an optional `palette` (name string or role object).
// ctx supplies hands-backed primitives { set, fillBox, B, clamp }.
const { resolvePalette } = require('./palettes')

// Cardinal helpers.
const DIRV = { north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0] }
const OPP = { north: 'south', south: 'north', east: 'west', west: 'east' }
const LEFT = { north: 'west', south: 'east', east: 'north', west: 'south' } // 90° CCW
const CARD = ['north', 'south', 'east', 'west']

module.exports = function makeFurniture(ctx) {
  const { set, fillBox, B, clamp } = ctx
  const face = (f) => (CARD.includes(String(f)) ? String(f) : 'north')
  const pal = (p) => resolvePalette(p)
  // Derive a wood family from a palette (for stairs/trapdoor/sign/fence furniture).
  function wood(P) {
    const src = B(P.floor, '') || B(P.pillar, '') || 'oak'
    const m = src.match(/(oak|spruce|birch|jungle|acacia|dark_oak|mangrove|cherry|bamboo|crimson|warped)/)
    return m ? m[1] : 'oak'
  }
  const step = (x, z, f, n = 1) => [x + DIRV[f][0] * n, z + DIRV[f][1] * n]

  // ---- individual furniture pieces ----

  // Chair: a stair seat (back away from `facing`) with trapdoor armrests.
  function chair(a) {
    const P = pal(a.palette), w = a.wood || wood(P), f = face(a.facing)
    const { x, y, z } = a
    // Seat: stairs with tall back on the side OPPOSITE the sitter's facing.
    set(x, y, z, `${w}_stairs[facing=${OPP[f]},half=bottom]`)
    // Armrests: closed top-trapdoors flanking the seat.
    const l = LEFT[f], r = OPP[l]
    const [lx, lz] = step(x, z, l), [rx, rz] = step(x, z, r)
    set(lx, y, lz, `${w}_trapdoor[facing=${r},half=top,open=false]`)
    set(rx, y, rz, `${w}_trapdoor[facing=${l},half=top,open=false]`)
    return 3
  }

  // Table: fence legs + carpet top. Supports w×l footprint.
  function table(a) {
    const P = pal(a.palette), w = a.wood || wood(P)
    const nx = clamp(a.w || a.width, 1, 8, 1), nz = clamp(a.l || a.length, 1, 8, 1)
    const carpet = B(a.carpet || P.carpet, 'white_carpet')
    let n = 0
    for (let dx = 0; dx < nx; dx++) for (let dz = 0; dz < nz; dz++) {
      set(a.x + dx, a.y, a.z + dz, `${w}_fence`); set(a.x + dx, a.y + 1, a.z + dz, carpet); n += 2
    }
    return n
  }

  // Sofa: a row of stairs (backs away from `facing`) with trapdoor arms at the ends.
  function sofa(a) {
    const P = pal(a.palette), w = a.wood || wood(P), f = face(a.facing)
    const len = clamp(a.length, 1, 12, 3), run = OPP[LEFT[f]] // extend along the wall
    let x = a.x, z = a.z, n = 0
    for (let i = 0; i < len; i++) {
      set(x, a.y, z, `${w}_stairs[facing=${OPP[f]},half=bottom]`); n++
      if (i === 0 || i === len - 1) { // arms at the ends
        const armDir = i === 0 ? LEFT[f] : run
        const [ax, az] = step(x, z, armDir)
        set(ax, a.y, az, `${w}_trapdoor[facing=${OPP[armDir]},half=top,open=false]`); n++
      }
      ;[x, z] = step(x, z, run)
    }
    return n
  }

  // Bed: vanilla two-block colored bed. foot at (x,y,z), head one step in `facing`.
  function bed(a) {
    const P = pal(a.palette), f = face(a.facing), color = B(a.color || P.bed, 'red')
    set(a.x, a.y, a.z, `${color}_bed[facing=${f},part=foot]`)
    const [hx, hz] = step(a.x, a.z, f)
    set(hx, a.y, hz, `${color}_bed[facing=${f},part=head]`)
    return 2
  }

  // Bookshelf: a wall of bookshelves, w wide × h tall, growing along `facing`'s wall.
  function bookshelf(a) {
    const P = pal(a.palette), f = face(a.facing)
    const w = clamp(a.width || a.length, 1, 16, 3), h = clamp(a.height, 1, 8, 2)
    const run = OPP[LEFT[f]], block = B(a.block, 'bookshelf')
    let x = a.x, z = a.z, n = 0
    for (let i = 0; i < w; i++) { for (let dy = 0; dy < h; dy++) { set(x, a.y + dy, z, block); n++ } ;[x, z] = step(x, z, run) }
    return n
  }

  // Desk: a barrel base + slab writing top, optional chair tucked in front.
  function desk(a) {
    const P = pal(a.palette), w = a.wood || wood(P), f = face(a.facing)
    set(a.x, a.y, a.z, 'barrel[facing=up]')
    set(a.x, a.y + 1, a.z, `${w}_slab[type=bottom]`)
    let n = 2
    if (a.chair !== false) { const [cx, cz] = step(a.x, a.z, f); n += chair({ x: cx, y: a.y, z: cz, facing: OPP[f], palette: P }) }
    return n
  }

  // Wardrobe: two stacked/side barrels fronted with trapdoor doors.
  function wardrobe(a) {
    const P = pal(a.palette), w = a.wood || wood(P), f = face(a.facing)
    const l = LEFT[f], [x2, z2] = step(a.x, a.z, l)
    for (const [bx, bz] of [[a.x, a.z], [x2, z2]]) {
      set(bx, a.y, bz, `${w}_planks`); set(bx, a.y + 1, bz, 'barrel[facing=up]')
      const [dx, dz] = step(bx, bz, f)
      set(dx, a.y + 1, dz, `${w}_trapdoor[facing=${OPP[f]},half=bottom,open=false]`)
    }
    return 6
  }

  // Kitchen counter: a run of appliances + a cauldron sink + trapdoor cabinet fronts.
  function kitchen_counter(a) {
    const P = pal(a.palette), w = a.wood || wood(P), f = face(a.facing)
    const len = clamp(a.length, 2, 14, 4), run = OPP[LEFT[f]]
    const tops = ['smoker', 'blast_furnace', 'water_cauldron[level=3]', 'barrel[facing=up]', 'crafting_table', 'furnace']
    let x = a.x, z = a.z, n = 0
    for (let i = 0; i < len; i++) {
      const top = i === 0 ? `smoker[facing=${f}]` : i === Math.floor(len / 2) ? 'water_cauldron[level=3]' : (i % 2 ? 'barrel[facing=up]' : `${w}_planks`)
      set(x, a.y, z, top)
      // cabinet front (trapdoor) facing out
      const [fx, fz] = step(x, z, f); set(fx, a.y, fz, `${w}_trapdoor[facing=${OPP[f]},half=bottom,open=false]`)
      n += 2;[x, z] = step(x, z, run)
    }
    return n
  }

  // Fireplace: a brick/deepslate recess with a lit campfire and a chimney upward.
  function fireplace(a) {
    const P = pal(a.palette), f = face(a.facing)
    const back = B(a.block, /deepslate|blackstone/.test(B(P.wall, '')) ? 'deepslate_bricks' : 'bricks')
    const { x, y, z } = a, l = LEFT[f], r = OPP[l], top = clamp(a.height, 3, 12, 5)
    const [lx, lz] = step(x, z, l), [rx, rz] = step(x, z, r), [bx, bz] = step(x, z, OPP[f])
    // hearth surround: two jambs + the back, rising y..y+2
    fillBox(lx, y, lz, lx, y + 2, lz, back); fillBox(rx, y, rz, rx, y + 2, rz, back)
    fillBox(bx, y, bz, bx, y + 2, bz, back)
    set(x, y, z, 'campfire[lit=true]')
    // mantle: a slab lip capping the opening, then the chimney up to `top`
    const mantle = /deepslate|blackstone/.test(back) ? 'deepslate_brick_slab' : 'brick_slab'
    set(x, y + 3, z, mantle)
    fillBox(bx, y + 3, bz, bx, y + top, bz, back)
    return 12
  }

  // Chandelier: fences hung from the ceiling with lanterns/end_rods around a hub.
  function chandelier(a) {
    const P = pal(a.palette), light = B(a.light || P.light, 'lantern')
    const { x, y, z } = a, drop = clamp(a.drop, 1, 6, 2), r = clamp(a.radius, 1, 3, 1)
    const w = wood(P)
    for (let d = 0; d < drop; d++) set(x, y - d, z, `${w}_fence`)
    const hy = y - drop
    for (const f of CARD) { const [ax, az] = step(x, z, f, r); set(ax, hy, az, `${w}_fence`); set(ax, hy - 1, az, /end_rod/.test(light) ? 'end_rod' : `${light}[hanging=true]`) }
    set(x, hy - 1, z, /end_rod/.test(light) ? 'end_rod' : `${light}[hanging=true]`)
    return 4 + drop + 4
  }

  // Lamp post: fence column topped with a lantern (for paths / yards).
  function lamp_post(a) {
    const P = pal(a.palette), light = B(a.light || P.light, 'lantern'), w = wood(P)
    const h = clamp(a.height, 2, 8, 4)
    for (let i = 0; i < h; i++) set(a.x, a.y + i, a.z, `${w}_fence`)
    set(a.x, a.y + h, a.z, /end_rod/.test(light) ? 'end_rod' : `${light}[hanging=true]`)
    return h + 1
  }

  // Rug: a carpet rectangle with an optional contrasting border.
  function rug(a) {
    const P = pal(a.palette)
    const c = B(a.color || P.carpet, 'red_carpet'), border = a.border ? B(a.border, 'white_carpet') : null
    const x1 = Math.min(a.x1 ?? a.x, a.x2 ?? a.x), x2 = Math.max(a.x1 ?? a.x, a.x2 ?? a.x)
    const z1 = Math.min(a.z1 ?? a.z, a.z2 ?? a.z), z2 = Math.max(a.z1 ?? a.z, a.z2 ?? a.z)
    let n = 0
    for (let x = x1; x <= x2; x++) for (let z = z1; z <= z2; z++) {
      const edge = x === x1 || x === x2 || z === z1 || z === z2
      set(x, a.y, z, border && edge ? border : c); n++
    }
    return n
  }

  // Wall shelf: top-trapdoor brackets with a slab plank as the shelf board.
  function shelf(a) {
    const P = pal(a.palette), w = a.wood || wood(P), f = face(a.facing)
    const len = clamp(a.length, 1, 10, 3), run = OPP[LEFT[f]]
    let x = a.x, z = a.z, n = 0
    for (let i = 0; i < len; i++) { set(x, a.y, z, `${w}_trapdoor[facing=${f},half=top,open=false]`); n++;[x, z] = step(x, z, run) }
    return n
  }

  // Potted plant: a filled flower pot (potted_* one-shot block).
  function potted_plant(a) {
    const plant = B(a.plant, 'potted_oak_sapling')
    set(a.x, a.y, a.z, plant.startsWith('potted_') ? plant : `potted_${plant}`)
    return 1
  }

  // ---- FURNISHED ROOMS: place furniture + light an EXISTING interior ----
  // Coords are the interior floor rectangle (x1,z1)-(x2,z2) at y1; ceiling ~ y2.
  function furnishRoom(a) {
    const P = pal(a.palette || a.style)
    let x1 = Math.min(a.x1, a.x2), x2 = Math.max(a.x1, a.x2)
    let z1 = Math.min(a.z1, a.z2), z2 = Math.max(a.z1, a.z2)
    const y = Math.round(a.y1 ?? a.y), yTop = Math.round(a.y2 ?? (y + 4))
    const type = String(a.type || 'living').toLowerCase()
    const f = face(a.facing)
    const cx = (x1 + x2) >> 1, cz = (z1 + z2) >> 1
    const tall = (yTop - y) >= 6
    let n = 0

    // Always start with a rug/floor accent + ensure the room is lit.
    const lightEvery = 5
    const litHanging = tall
    const placeLight = (lx, lz) => {
      if (litHanging) { set(lx, yTop - 1, lz, /end_rod/.test(B(P.light, '')) ? 'end_rod' : `${B(P.light, 'lantern')}[hanging=true]`) }
      else set(lx, yTop - 1, lz, /end_rod/.test(B(P.light, '')) ? 'end_rod' : `${B(P.light, 'lantern')}[hanging=true]`)
      n++
    }
    // lighting cadence across the ceiling so no interior spot is dark
    const lightAll = () => { for (let x = x1 + 1; x < x2; x += lightEvery) for (let z = z1 + 1; z < z2; z += lightEvery) placeLight(x, z) }

    if (type === 'bedroom') {
      bed({ x: x1 + 1, y, z: cz, facing: 'east', palette: P }); n += 2
      set(x1 + 1, y, cz - 1, 'barrel[facing=up]'); set(x1 + 1, y + 1, cz - 1, `${B(P.light, 'lantern')}`); n += 2 // nightstand + lamp
      wardrobe({ x: x2 - 1, y, z: z1 + 1, facing: 'west', palette: P }); n += 6
      rug({ x1: cx - 1, z1: cz - 1, x2: cx + 1, z2: cz + 1, y, palette: P }); n += 9
      potted_plant({ x: x2 - 1, y, z: z2 - 1, plant: 'potted_fern' }); n += 1
      lightAll()
    } else if (type === 'kitchen') {
      kitchen_counter({ x: x1 + 1, y, z: z1 + 1, facing: 'south', length: Math.max(2, z2 - z1 - 1), palette: P }); n += 8
      table({ x: cx, y, z: cz, w: 2, l: 1, palette: P }); n += 4 // island
      set(cx, y, cz, 'barrel[facing=up]')
      wardrobe({ x: x2 - 1, y, z: z2 - 1, facing: 'west', palette: P }); n += 6 // pantry
      lightAll()
    } else if (type === 'library') {
      // bookshelf walls down the two long walls
      bookshelf({ x: x1, y, z: z1 + 1, facing: 'north', width: Math.max(1, z2 - z1 - 1), height: Math.min(4, yTop - y - 1), palette: P })
      bookshelf({ x: x2, y, z: z1 + 1, facing: 'north', width: Math.max(1, z2 - z1 - 1), height: Math.min(4, yTop - y - 1), palette: P })
      table({ x: cx, y, z: cz, w: 2, l: 1, palette: P }); n += 4
      chair({ x: cx - 1, y, z: cz, facing: 'east', palette: P }); chair({ x: cx + 2, y, z: cz, facing: 'west', palette: P }); n += 6
      chandelier({ x: cx, y: yTop - 1, z: cz, palette: P, drop: tall ? 2 : 1 }); n += 8
      lightAll()
    } else if (type === 'great_hall') {
      // long banquet table down the centre with benches both sides
      const tz1 = z1 + 2, tz2 = z2 - 2
      for (let z = tz1; z <= tz2; z++) { set(cx, y, z, `${wood(P)}_fence`); set(cx, y + 1, z, B(P.carpet, 'red_carpet')) ; n += 2 }
      for (let z = tz1; z <= tz2; z++) { chair({ x: cx - 1, y, z, facing: 'east', palette: P }); chair({ x: cx + 1, y, z, facing: 'west', palette: P }); n += 6 }
      fireplace({ x: cx, y, z: z1 + 1, facing: 'south', height: Math.max(4, yTop - y), palette: P }); n += 12
      // banners on the side walls
      for (let z = z1 + 2; z <= z2 - 2; z += 4) {
        set(x1, y + 3, z, `${B(P.banner, 'red')}_wall_banner[facing=east]`)
        set(x2, y + 3, z, `${B(P.banner, 'red')}_wall_banner[facing=west]`); n += 2
      }
      // chandeliers along the ridge
      for (let z = z1 + 3; z <= z2 - 3; z += 5) { chandelier({ x: cx, y: yTop - 1, z, palette: P, drop: 2 }); n += 8 }
      lightAll()
    } else { // living
      sofa({ x: x1 + 1, y, z: cz - 1, facing: 'east', length: 3, palette: P }); n += 5
      rug({ x1: cx - 1, z1: cz - 1, x2: cx + 1, z2: cz + 1, y, palette: P }); n += 9
      table({ x: cx, y, z: cz, w: 1, l: 1, palette: P }); n += 2
      fireplace({ x: x2 - 1, y, z: cz, facing: 'west', height: Math.max(4, yTop - y), palette: P }); n += 12
      bookshelf({ x: x1 + 1, y, z: z2, facing: 'east', width: Math.min(4, x2 - x1 - 2), height: 2, palette: P })
      potted_plant({ x: x1 + 1, y, z: z1 + 1, plant: 'potted_azalea_bush' }); n += 1
      lightAll()
    }
    return n
  }

  const skills = {
    chair, table, sofa, bed, bookshelf, desk, wardrobe, kitchen_counter,
    fireplace, chandelier, lamp_post, rug, shelf, potted_plant, furnishRoom
  }

  const catalog = [
    ['chair', '{x,y,z,facing,palette}', 'stair seat + trapdoor armrests'],
    ['table', '{x,y,z,w,l,palette}', 'fence legs + carpet top (w×l)'],
    ['sofa', '{x,y,z,facing,length,palette}', 'stair row + trapdoor arms'],
    ['bed', '{x,y,z,facing,color,palette}', 'vanilla colored bed (2 blocks)'],
    ['bookshelf', '{x,y,z,facing,width,height,palette}', 'wall of bookshelves'],
    ['desk', '{x,y,z,facing,palette}', 'barrel + slab top (+chair)'],
    ['wardrobe', '{x,y,z,facing,palette}', '2 barrels + trapdoor doors'],
    ['kitchen_counter', '{x,y,z,facing,length,palette}', 'appliances + cauldron sink + cabinets'],
    ['fireplace', '{x,y,z,facing,height,palette}', 'brick recess + campfire + chimney'],
    ['chandelier', '{x,y,z,drop,radius,palette}', 'hung fences + lanterns (place at ceiling y)'],
    ['lamp_post', '{x,y,z,height,palette}', 'fence post + lantern'],
    ['rug', '{x1,z1,x2,z2,y,color,border,palette}', 'carpet rectangle with optional border'],
    ['shelf', '{x,y,z,facing,length,palette}', 'trapdoor bracket shelf'],
    ['potted_plant', '{x,y,z,plant}', 'a filled flower pot (potted_*)'],
    ['furnishRoom', '{type,x1,y1,z1,x2,y2,z2,palette,facing}', 'FURNISH + LIGHT an existing interior. type: bedroom|kitchen|library|great_hall|living']
  ]

  return { skills, catalog, wood }
}
