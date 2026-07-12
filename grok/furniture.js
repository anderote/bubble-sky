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

  // ---- MODDED FURNITURE helpers (mcwfurnitures / farmersdelight / blockus / vanilla depth) ----
  // mcwfurnitures ship a piece per wood type; keep to the safe/known wood set.
  const MCW_WOODS = new Set(['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry', 'bamboo', 'crimson', 'warped'])
  function furnWood(P) { const wd = wood(P); return MCW_WOODS.has(wd) ? wd : 'oak' }
  const mcw = (wd, piece, state) => `mcwfurnitures:${wd}_${piece}${state ? `[${state}]` : ''}`
  // Canonicalise a requested room type to one furnishRoom knows how to dress.
  function normType(t) {
    const k = String(t == null ? 'living' : t).toLowerCase()
    const map = {
      quarters: 'bedroom', bed: 'bedroom', dormitory: 'barracks', bunk: 'barracks',
      principia: 'great_hall', hq: 'great_hall', hall: 'great_hall', dining: 'great_hall',
      study: 'library', church: 'chapel', shrine: 'chapel', canteen: 'mess', refectory: 'mess',
      armoury: 'armory', arsenal: 'armory', barn: 'granary', silo: 'granary',
      storage: 'storeroom', pantry: 'storeroom', cellar: 'storeroom',
      smithy: 'forge', craft: 'workshop', stables: 'stable', lounge: 'living'
    }
    const known = ['bedroom', 'kitchen', 'library', 'great_hall', 'living', 'mess', 'armory', 'granary', 'storeroom', 'barracks', 'chapel', 'forge', 'workshop', 'stable']
    return map[k] || (known.includes(k) ? k : 'living')
  }

  // ---- FURNISHED ROOMS: place DENSE, CHARACTERFUL furniture + light an interior ----
  // Coords are the interior floor rectangle (x1,z1)-(x2,z2) at y1; ceiling ~ y2.
  // Every room type lays out REAL furniture (mcwfurnitures/farmersdelight/blockus
  // + vanilla depth) by function, plus decor accents and full lighting.
  function furnishRoom(a) {
    const P = pal(a.palette || a.style)
    let x1 = Math.min(a.x1, a.x2), x2 = Math.max(a.x1, a.x2)
    let z1 = Math.min(a.z1, a.z2), z2 = Math.max(a.z1, a.z2)
    const y = Math.round(a.y1 ?? a.y), yTop = Math.round(a.y2 ?? (y + 4))
    const type = normType(a.type)
    const wd = furnWood(P)
    const cx = (x1 + x2) >> 1, cz = (z1 + z2) >> 1
    const iw = x2 - x1, il = z2 - z1
    const tall = (yTop - y) >= 6
    let n = 0
    const put = (x, yy, z, b) => { set(x, yy, z, b); n++ }
    const litB = B(P.light, 'lantern'); const isRod = /end_rod/.test(litB)
    const hang = () => (isRod ? 'end_rod' : `${litB}[hanging=true]`)
    const bannerC = B(P.banner, 'red')
    const candleC = ['white', 'orange', 'yellow', 'brown']
    // a lit candle cluster resting on top of a surface
    const candle = (x, yy, z) => put(x, yy, z, `${candleC[(x + z) % candleC.length]}_candle[candles=${1 + Math.abs(x + z) % 3},lit=true]`)
    // a wall banner facing into the room
    const wallBanner = (x, z, facing) => put(x, y + Math.min(3, yTop - y - 1), z, `${bannerC}_wall_banner[facing=${facing}]`)
    // ceiling lighting cadence so no interior spot is dark
    const lightAll = (every = 5) => {
      const e = Math.max(3, every)
      for (let x = x1 + 1; x < x2; x += e) for (let z = z1 + 1; z < z2; z += e) put(x, yTop - 1, z, hang())
    }
    // guard for very small rooms
    if (iw < 2 || il < 2) { lightAll(); return n }

    if (type === 'bedroom') {
      bed({ x: x1 + 1, y, z: z1 + 2, facing: 'south', palette: P }); n += 2
      put(x1 + 1, y, z1 + 1, mcw(wd, 'drawer', 'facing=south')); candle(x1 + 1, y + 1, z1 + 1) // nightstand + candle
      put(x2 - 1, y, z1 + 1, mcw(wd, 'wardrobe', 'facing=west'))
      put(x2 - 1, y, z2 - 1, mcw(wd, 'desk', 'facing=west')); put(x2 - 2, y, z2 - 1, mcw(wd, 'chair', 'facing=east'))
      put(x1 + 1, y, cz + 1, mcw(wd, 'bookshelf', 'facing=south'))
      rug({ x1: cx - 1, z1: cz - 1, x2: cx + 1, z2: cz + 1, y, palette: P }); n += 9
      potted_plant({ x: x2 - 1, y, z: z2 - 2, plant: 'potted_fern' }); n += 1
      wallBanner(cx, z1, 'south')
      lightAll()
    } else if (type === 'kitchen') {
      // counter + sink run along the back wall, appliances interspersed
      const runL = Math.max(2, iw - 1)
      for (let i = 0; i < runL; i++) {
        const x = x1 + 1 + i
        const top = i === 0 ? mcw(wd, 'kitchen_sink', 'facing=south')
          : i === 1 ? `farmersdelight:stove[facing=south]`
            : i === 2 ? mcw(wd, 'kitchen_counter', 'facing=south')
              : (i % 2 ? mcw(wd, 'kitchen_counter', 'facing=south') : mcw(wd, 'cupboard', 'facing=south'))
        put(x, y, z1 + 1, top)
      }
      put(x1 + 1, y + 1, z1 + 1, 'farmersdelight:cooking_pot'); n += 0
      put(x2 - 2, y, z1 + 2, 'farmersdelight:cutting_board')
      // dining island
      put(cx, y, cz, mcw(wd, 'table', 'facing=north')); put(cx + 1, y, cz, mcw(wd, 'table', 'facing=north'))
      put(cx - 1, y, cz, mcw(wd, 'chair', 'facing=east')); put(cx + 2, y, cz, mcw(wd, 'chair', 'facing=west'))
      put(cx, y, cz - 1, mcw(wd, 'stool', 'facing=north')); put(cx + 1, y, cz + 1, mcw(wd, 'stool', 'facing=south'))
      put(x2 - 1, y, z2 - 1, 'barrel[facing=up]'); put(x2 - 1, y + 1, z2 - 1, 'barrel[facing=up]') // pantry stack
      put(x1 + 1, y, z2 - 1, 'farmersdelight:rice_bale')
      lightAll()
    } else if (type === 'library') {
      const h = Math.min(4, Math.max(2, yTop - y - 1))
      // bookshelf walls: mcwfurnitures bookshelves + vanilla chiseled ones, both long walls
      for (let z = z1 + 1; z <= z2 - 1; z++) {
        for (let dy = 0; dy < h; dy++) {
          put(x1, y + dy, z, dy === 1 ? 'chiseled_bookshelf[facing=east]' : mcw(wd, 'bookshelf', 'facing=east'))
          put(x2, y + dy, z, dy === 1 ? 'chiseled_bookshelf[facing=west]' : mcw(wd, 'bookshelf', 'facing=west'))
        }
      }
      // reading tables down the centre with chairs
      for (let z = z1 + 2; z <= z2 - 2; z += 3) {
        put(cx, y, z, mcw(wd, 'table', 'facing=north')); candle(cx, y + 1, z)
        put(cx - 1, y, z, mcw(wd, 'chair', 'facing=east')); put(cx + 1, y, z, mcw(wd, 'chair', 'facing=west'))
      }
      put(cx, y, z1 + 1, 'lectern[facing=south]')
      chandelier({ x: cx, y: yTop - 1, z: cz, palette: P, drop: tall ? 2 : 1 }); n += 8
      lightAll(6)
    } else if (type === 'great_hall') {
      // long banquet table down the centre (mcwfurnitures tables) + chairs both sides
      const tz1 = z1 + 2, tz2 = z2 - 2
      for (let z = tz1; z <= tz2; z++) {
        put(cx, y, z, mcw(wd, 'table', 'facing=north'))
        if ((z - tz1) % 3 === 1) candle(cx, y + 1, z)
        put(cx - 1, y, z, mcw(wd, 'chair', 'facing=east')); put(cx + 1, y, z, mcw(wd, 'chair', 'facing=west'))
      }
      // head table on a dais + a throne
      put(cx, y, tz2 + 1, mcw(wd, 'chair', 'facing=north'))
      fireplace({ x: cx, y, z: z1 + 1, facing: 'south', height: Math.max(4, yTop - y), palette: P }); n += 12
      // heraldry banners + wall sconces down both side walls
      for (let z = z1 + 2; z <= z2 - 2; z += 3) { wallBanner(x1, z, 'east'); wallBanner(x2, z, 'west') }
      for (let z = z1 + 3; z <= z2 - 3; z += 4) put(x1 + 1, y + 2, z, `${litB}`), put(x2 - 1, y + 2, z, `${litB}`)
      // barrels of ale in the corners
      put(x2 - 1, y, z1 + 1, 'barrel[facing=up]'); put(x1 + 1, y, z1 + 1, 'barrel[facing=up]')
      // chandeliers along the ridge
      for (let z = z1 + 3; z <= z2 - 3; z += 5) { chandelier({ x: cx, y: yTop - 1, z, palette: P, drop: 2 }); n += 8 }
      lightAll()
    } else if (type === 'mess') {
      // soldiers' mess: parallel trestle tables with benches + kitchen end
      for (let x = x1 + 2; x <= x2 - 2; x += 3) {
        for (let z = z1 + 2; z <= z2 - 2; z++) {
          put(x, y, z, mcw(wd, 'table', 'facing=north'))
          put(x - 1, y, z, mcw(wd, 'stool', 'facing=east')); put(x + 1, y, z, mcw(wd, 'stool', 'facing=west'))
        }
      }
      put(x1 + 1, y, z1 + 1, `farmersdelight:stove[facing=east]`); put(x1 + 1, y + 1, z1 + 1, 'farmersdelight:cooking_pot')
      put(x2 - 1, y, z1 + 1, mcw(wd, 'kitchen_counter', 'facing=west'))
      put(x1 + 1, y, z2 - 1, 'barrel[facing=up]'); put(x2 - 1, y, z2 - 1, 'barrel[facing=up]')
      wallBanner(cx, z1, 'south')
      lightAll()
    } else if (type === 'armory') {
      // weapon/tool workshop feel: anvil, grindstone, smithing table, racks of drawers
      put(x1 + 1, y, z1 + 1, 'anvil'); put(x1 + 2, y, z1 + 1, 'smithing_table'); put(x1 + 3, y, z1 + 1, 'grindstone[face=floor,facing=south]')
      // drawer "racks" along both side walls with soul-lit sconces above
      for (let z = z1 + 1; z <= z2 - 1; z += 2) {
        put(x1, y, z, mcw(wd, 'double_drawer', 'facing=east')); put(x2, y, z, mcw(wd, 'double_drawer', 'facing=west'))
        put(x1, y + 2, z, 'soul_lantern[hanging=false]'); put(x2, y + 2, z, 'soul_lantern[hanging=false]')
      }
      // barrels of supplies + a display stand of blockus stone in the centre
      put(cx, y, cz, 'blockus:chiseled_diorite'); put(cx, y + 1, cz, 'lodestone')
      put(x2 - 1, y, z2 - 1, 'barrel[facing=up]'); put(x2 - 2, y, z2 - 1, 'barrel[facing=up]')
      put(x1 + 1, y, z2 - 1, 'chain'); put(x1 + 1, y + 1, z2 - 1, 'chain')
      wallBanner(cx, z1, 'south')
      lightAll()
    } else if (type === 'granary') {
      // grain store: stacked hay + straw/rice bales + crop crates + barrels
      for (let x = x1 + 1; x <= x2 - 1; x += 2) for (let z = z1 + 1; z <= z2 - 1; z += 2) {
        const stack = 1 + (Math.abs(x * 7 + z * 3) % Math.max(1, Math.min(3, yTop - y - 1)))
        for (let dy = 0; dy < stack; dy++) put(x, y + dy, z, 'hay_block')
      }
      for (let z = z1 + 1; z <= z2 - 1; z += 3) { put(x1 + 1, y, z, 'farmersdelight:straw_bale'); put(x2 - 1, y, z, 'farmersdelight:rice_bale') }
      put(x1 + 1, y, z1 + 1, 'barrel[facing=up]'); put(x2 - 1, y, z2 - 1, 'barrel[facing=up]')
      lightAll()
    } else if (type === 'storeroom') {
      // barrels + shelves + crates
      for (let z = z1 + 1; z <= z2 - 1; z++) {
        put(x1, y, z, 'barrel[facing=up]'); put(x1, y + 1, z, 'barrel[facing=up]')
        put(x2, y, z, mcw(wd, 'cupboard', 'facing=west'))
      }
      for (let x = x1 + 2; x <= x2 - 2; x += 2) { put(x, y, z1 + 1, 'barrel[facing=up]'); put(x, y, z2 - 1, 'farmersdelight:straw_bale') }
      shelf({ x: x1 + 1, y: y + 2, z: z1, facing: 'south', length: Math.max(1, iw - 2), palette: P })
      lightAll()
    } else if (type === 'barracks') {
      // rows of bunk beds along both long walls, footlocker + wall torch each
      const beds = []
      for (let z = z1 + 1; z + 1 <= z2 - 1; z += 3) beds.push(z)
      for (const z of beds) {
        bed({ x: x1 + 1, y, z, facing: 'south', palette: P }); n += 2
        put(x1 + 1, y, z + 1, 'barrel[facing=up]')                    // footlocker
        put(x1, y + 2, z, 'torch')
        bed({ x: x2 - 1, y, z, facing: 'south', palette: P }); n += 2
        put(x2 - 1, y, z + 1, mcw(wd, 'drawer', 'facing=west'))       // footlocker
        put(x2, y + 2, z, 'torch')
      }
      // a weapons rack + banner at the head of the room
      put(cx, y, z1 + 1, mcw(wd, 'double_drawer', 'facing=south')); wallBanner(cx, z1, 'south')
      lightAll()
    } else if (type === 'chapel') {
      // pews facing an altar of blockus marble with candles + a lectern
      for (let z = z1 + 2; z <= z2 - 3; z += 2) {
        for (let x = x1 + 1; x <= cx - 1; x++) put(x, y, z, `${wd}_stairs[facing=north,half=bottom]`)
        for (let x = cx + 1; x <= x2 - 1; x++) put(x, y, z, `${wd}_stairs[facing=north,half=bottom]`)
      }
      // altar
      put(cx, y, z2 - 1, 'blockus:polished_marble'); put(cx - 1, y, z2 - 1, 'blockus:marble'); put(cx + 1, y, z2 - 1, 'blockus:marble')
      candle(cx - 1, y + 1, z2 - 1); candle(cx + 1, y + 1, z2 - 1)
      put(cx, y, z2 - 2, 'lectern[facing=north]')
      // aisle carpet + tall windows dressing via banners
      for (let z = z1 + 1; z <= z2 - 1; z++) put(cx, y, z, B(P.carpet, 'red_carpet'))
      wallBanner(x1, cz, 'east'); wallBanner(x2, cz, 'west')
      if (tall) { chandelier({ x: cx, y: yTop - 1, z: cz, palette: P, drop: 3 }); n += 8 }
      lightAll()
    } else if (type === 'forge') {
      put(x1 + 1, y, z1 + 1, 'blast_furnace[facing=south]'); put(x1 + 2, y, z1 + 1, 'furnace[facing=south]')
      put(x1 + 3, y, z1 + 1, 'smithing_table'); put(cx, y, cz, 'anvil'); put(cx + 1, y, cz, 'grindstone[face=floor,facing=south]')
      put(x2 - 1, y, z1 + 1, 'blockus:limestone'); put(x2 - 1, y + 1, z1 + 1, 'magma_block') // forge hearth
      put(x2 - 1, y, z2 - 1, 'barrel[facing=up]'); put(x1 + 1, y, z2 - 1, 'water_cauldron[level=3]') // quench
      put(cx, y, z2 - 1, mcw(wd, 'double_drawer', 'facing=north'))
      lightAll()
    } else if (type === 'workshop') {
      put(x1 + 1, y, z1 + 1, 'crafting_table'); put(x1 + 2, y, z1 + 1, 'fletching_table'); put(x1 + 3, y, z1 + 1, 'cartography_table')
      put(cx, y, cz, mcw(wd, 'desk', 'facing=south')); put(cx, y, cz - 1, mcw(wd, 'chair', 'facing=north'))
      for (let z = z1 + 1; z <= z2 - 1; z += 2) put(x2, y, z, mcw(wd, 'cupboard', 'facing=west'))
      put(x1 + 1, y, z2 - 1, 'barrel[facing=up]'); put(x1 + 2, y, z2 - 1, 'loom[facing=north]')
      lightAll()
    } else if (type === 'stable') {
      // stalls partitioned by fences, hay + water + feed
      for (let x = x1 + 2; x <= x2 - 1; x += 3) for (let dy = 0; dy < Math.min(2, yTop - y - 1); dy++) put(x, y + dy, cz, `${wd}_fence`)
      for (let x = x1 + 1; x <= x2 - 1; x += 3) {
        put(x, y, z1 + 1, 'hay_block'); put(x, y, z2 - 1, 'water_cauldron[level=3]')
        put(x + 1, y, z2 - 1, 'farmersdelight:rice_bale')
      }
      put(x1 + 1, y, cz, mcw(wd, 'double_drawer', 'facing=east'))
      lightAll()
    } else { // living
      // couch (mcwfurnitures modular sofa) facing a coffee table + fireplace + shelves
      put(x1 + 1, y, cz - 1, 'mcwfurnitures:white_modern_couch[facing=east,type=left]')
      put(x1 + 1, y, cz, 'mcwfurnitures:white_modern_couch[facing=east,type=middle]')
      put(x1 + 1, y, cz + 1, 'mcwfurnitures:white_modern_couch[facing=east,type=right]')
      put(cx, y, cz, mcw(wd, 'coffee_table', 'facing=north'))
      rug({ x1: cx - 1, z1: cz - 1, x2: cx + 1, z2: cz + 1, y, palette: P }); n += 9
      fireplace({ x: x2 - 1, y, z: cz, facing: 'west', height: Math.max(4, yTop - y), palette: P }); n += 12
      for (let dy = 0; dy < Math.min(3, yTop - y - 1); dy++) { put(x1, y + dy, z2 - 1, mcw(wd, 'bookshelf', 'facing=east')); put(x1, y + dy, z2 - 2, mcw(wd, 'bookshelf', 'facing=east')) }
      put(cx + 1, y, z1 + 1, mcw(wd, 'drawer', 'facing=south')); candle(cx + 1, y + 1, z1 + 1)
      potted_plant({ x: x2 - 1, y, z: z1 + 1, plant: 'potted_azalea_bush' }); n += 1
      wallBanner(cx, z1, 'south')
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
    ['furnishRoom', '{type,x1,y1,z1,x2,y2,z2,palette}', 'FURNISH + LIGHT an interior densely with real furniture (mcwfurnitures/farmersdelight/blockus + vanilla). type: bedroom|quarters|kitchen|library|great_hall|mess|armory|granary|storeroom|barracks|chapel|forge|workshop|stable|living']
  ]

  return { skills, catalog, wood }
}
