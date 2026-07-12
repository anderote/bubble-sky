// grok/structures.js — parametric structure generators.
// Each generator takes (origin{x,y,z}, args) and emits /fill + /setblock via the
// throttled command queue passed in ctx. Everything is instant godmode building.
module.exports = function makeStructures(ctx) {
  const { enqueue, fillBox, B, clamp, set } = ctx
  const air = (x1, y1, z1, x2, y2, z2) => fillBox(x1, y1, z1, x2, y2, z2, 'air')
  const centre = (c, n) => [c - ((n - 1) >> 1), c - ((n - 1) >> 1) + n - 1] // -> [lo, hi]

  function house(o, a) {
    const w = clamp(a.width || a.size, 5, 40, 7), l = clamp(a.length || a.size, 5, 40, 9), h = clamp(a.height, 3, 12, 5)
    const mat = B(a.material, 'oak_planks'), floor = B(a.floor, 'spruce_planks'), rf = B(a.roof_material || a.material, 'oak')
    const [x1, x2] = centre(o.x, w), [z1, z2] = centre(o.z, l), y0 = o.y, y1 = o.y + h - 1
    const half = w >> 1
    air(x1, y0, z1, x2, y1 + half + 2, z2)
    fillBox(x1, y0, z1, x2, y1, z2, mat)                 // solid shell
    air(x1 + 1, y0 + 1, z1 + 1, x2 - 1, y1, z2 - 1)      // hollow (top open for roof)
    fillBox(x1 + 1, y0, z1 + 1, x2 - 1, y0, z2 - 1, floor) // floor
    // door on the -z wall, centred
    set(o.x, y0 + 1, z1, 'air'); set(o.x, y0 + 2, z1, 'air')
    set(o.x, y0 + 1, z1, `${rf}_door[facing=north,half=lower]`); set(o.x, y0 + 2, z1, `${rf}_door[facing=north,half=upper]`)
    // windows
    for (const wz of [z1 + Math.max(1, (l / 3) | 0), z2 - Math.max(1, (l / 3) | 0)]) { set(x1, y0 + 2, wz, 'glass_pane'); set(x2, y0 + 2, wz, 'glass_pane') }
    // gable roof: ridge along z, sloping on x
    for (let i = 0; i <= half; i++) {
      const yy = y1 + 1 + i, xl = x1 + i, xr = x2 - i
      if (xl > xr) break
      fillBox(xl, yy, z1 - 1, xl, yy, z2 + 1, `${rf}_stairs[facing=east]`)
      fillBox(xr, yy, z1 - 1, xr, yy, z2 + 1, `${rf}_stairs[facing=west]`)
      if (xl === xr) fillBox(xl, yy, z1 - 1, xl, yy, z2 + 1, `${rf}_slab`)
    }
    set(o.x, y0 + 1, o.z, 'lantern')
  }

  function tower(o, a) {
    const s = clamp(a.size || a.width, 3, 15, 5), h = clamp(a.height, 5, 60, 14), mat = B(a.material, 'stone_bricks')
    const [x1, x2] = centre(o.x, s), [z1, z2] = centre(o.z, s), y0 = o.y, y1 = o.y + h - 1
    air(x1, y0, z1, x2, y1 + 1, z2)
    fillBox(x1, y0, z1, x2, y1, z2, mat)
    air(x1 + 1, y0 + 1, z1 + 1, x2 - 1, y1 - 1, z2 - 1)
    for (let yy = y0; yy <= y1; yy += 4) fillBox(x1 + 1, yy, z1 + 1, x2 - 1, yy, z2 - 1, mat) // floors + ladder-less levels
    set(o.x, y0 + 1, z1, 'air'); set(o.x, y0 + 2, z1, 'air')                                 // door
    crenellate(x1, x2, z1, z2, y1 + 1, mat)
    set(o.x, y0 + 1, o.z, 'lantern')
  }

  function round_tower(o, a) {
    const r = clamp(a.radius, 2, 12, 3), h = clamp(a.height, 5, 60, 14), mat = B(a.material, 'stone_bricks')
    const y0 = o.y, y1 = o.y + h - 1
    air(o.x - r, y0, o.z - r, o.x + r, y1 + 1, o.z + r)
    let i = 0
    for (let x = -r; x <= r; x++) for (let z = -r; z <= r; z++) {
      const d = Math.hypot(x, z)
      if (d > r - 0.5 && d <= r + 0.5) { fillBox(o.x + x, y0, o.z + z, o.x + x, y1, o.z + z, mat); if (i++ % 2) set(o.x + x, y1 + 1, o.z + z, mat) }
    }
    set(o.x, y0 + 1, o.z, 'lantern')
  }

  function wall(o, a) {
    const len = clamp(a.length, 3, 200, 16), h = clamp(a.height, 2, 24, 5), mat = B(a.material, 'stone_bricks'), ax = a.axis === 'z' ? 'z' : 'x'
    const x2 = ax === 'x' ? o.x + len - 1 : o.x, z2 = ax === 'z' ? o.z + len - 1 : o.z
    fillBox(o.x, o.y, o.z, x2, o.y + h - 1, z2, mat)
    if (ax === 'x') { for (let x = o.x; x <= x2; x++) if ((x - o.x) % 2 === 0) set(x, o.y + h, o.z, mat) }
    else { for (let z = o.z; z <= z2; z++) if ((z - o.z) % 2 === 0) set(o.x, o.y + h, z, mat) }
  }

  function bridge(o, a) {
    const len = clamp(a.length, 3, 200, 20), w = clamp(a.width, 1, 9, 3), mat = B(a.material, 'stone_bricks'), ax = a.axis === 'z' ? 'z' : 'x'
    const hw = (w - 1) >> 1
    if (ax === 'x') {
      fillBox(o.x, o.y, o.z - hw, o.x + len - 1, o.y, o.z + hw, mat)
      fillBox(o.x, o.y + 1, o.z - hw, o.x + len - 1, o.y + 1, o.z - hw, 'oak_fence')
      fillBox(o.x, o.y + 1, o.z + hw, o.x + len - 1, o.y + 1, o.z + hw, 'oak_fence')
    } else {
      fillBox(o.x - hw, o.y, o.z, o.x + hw, o.y, o.z + len - 1, mat)
      fillBox(o.x - hw, o.y + 1, o.z, o.x - hw, o.y + 1, o.z + len - 1, 'oak_fence')
      fillBox(o.x + hw, o.y + 1, o.z, o.x + hw, o.y + 1, o.z + len - 1, 'oak_fence')
    }
  }

  function road(o, a) {
    const len = clamp(a.length, 3, 300, 40), w = clamp(a.width, 1, 9, 3), mat = B(a.material, 'dirt_path'), ax = a.axis === 'z' ? 'z' : 'x'
    const hw = (w - 1) >> 1
    if (ax === 'x') { air(o.x, o.y + 1, o.z - hw, o.x + len - 1, o.y + 3, o.z + hw); fillBox(o.x, o.y, o.z - hw, o.x + len - 1, o.y, o.z + hw, mat) }
    else { air(o.x - hw, o.y + 1, o.z, o.x + hw, o.y + 3, o.z + len - 1); fillBox(o.x - hw, o.y, o.z, o.x + hw, o.y, o.z + len - 1, mat) }
  }

  function pyramid(o, a) {
    const base = clamp(a.base || a.size, 3, 63, 15), mat = B(a.material, 'sandstone')
    const half = (base - 1) >> 1
    for (let i = 0; i <= half; i++) fillBox(o.x - half + i, o.y + i, o.z - half + i, o.x + half - i, o.y + i, o.z + half - i, mat)
  }

  function dome(o, a) {
    const r = clamp(a.radius, 3, 18, 6), mat = B(a.material, 'quartz_block')
    for (let x = -r; x <= r; x++) for (let y = 0; y <= r; y++) for (let z = -r; z <= r; z++) {
      const d = Math.sqrt(x * x + y * y + z * z)
      if (d > r - 0.6 && d <= r + 0.4) set(o.x + x, o.y + y, o.z + z, mat)
    }
    set(o.x, o.y + 1, o.z - r, 'air'); set(o.x, o.y + 2, o.z - r, 'air')
  }

  function platform(o, a) {
    const r = clamp(a.radius, 1, 60, 8), mat = B(a.material, 'stone')
    fillBox(o.x - r, o.y - 1, o.z - r, o.x + r, o.y - 1, o.z + r, mat)
  }

  function castle(o, a) {
    const size = clamp(a.size, 15, 80, 25), h = clamp(a.height, 4, 16, 7), mat = B(a.material, 'stone_bricks')
    const half = (size - 1) >> 1, x1 = o.x - half, x2 = o.x + half, z1 = o.z - half, z2 = o.z + half
    air(x1, o.y, z1, x2, o.y + h + 8, z2)
    fillBox(x1, o.y - 1, z1, x2, o.y - 1, z2, 'stone_bricks')                     // courtyard floor
    wall({ x: x1, y: o.y, z: z1 }, { length: size, height: h, axis: 'x', material: mat })
    wall({ x: x1, y: o.y, z: z2 }, { length: size, height: h, axis: 'x', material: mat })
    wall({ x: x1, y: o.y, z: z1 }, { length: size, height: h, axis: 'z', material: mat })
    wall({ x: x2, y: o.y, z: z1 }, { length: size, height: h, axis: 'z', material: mat })
    for (const [cx, cz] of [[x1, z1], [x1, z2], [x2, z1], [x2, z2]]) round_tower({ x: cx, y: o.y, z: cz }, { radius: 3, height: h + 4, material: mat })
    set(o.x, o.y + 1, z1, 'air'); set(o.x, o.y + 2, z1, 'air'); set(o.x, o.y + 3, z1, 'air') // gate
  }

  function crenellate(x1, x2, z1, z2, y, mat) {
    for (let x = x1; x <= x2; x++) { if ((x - x1) % 2 === 0) { set(x, y, z1, mat); set(x, y, z2, mat) } }
    for (let z = z1; z <= z2; z++) { if ((z - z1) % 2 === 0) { set(x1, y, z, mat); set(x2, y, z, mat) } }
  }

  function room(o, a) {
    const w = clamp(a.width || a.size, 3, 40, 7), l = clamp(a.length || a.size, 3, 40, 7), h = clamp(a.height, 3, 20, 5)
    const mat = B(a.material || a.block, 'oak_planks'), floor = B(a.floor, mat)
    const [x1, x2] = centre(o.x, w), [z1, z2] = centre(o.z, l), y0 = o.y, y1 = o.y + h
    fillBox(x1, y0, z1, x2, y1, z2, mat)                       // solid shell
    air(x1 + 1, y0 + 1, z1 + 1, x2 - 1, y1 - 1, z2 - 1)        // hollow interior
    if (floor !== mat) fillBox(x1 + 1, y0, z1 + 1, x2 - 1, y0, z2 - 1, floor)
    set(o.x, y0 + 1, z2, 'air'); set(o.x, y0 + 2, z2, 'air')   // doorway
    set(o.x, y0 + 1, z2, 'oak_door[half=lower,facing=north]'); set(o.x, y0 + 2, z2, 'oak_door[half=upper,facing=north]')
    set(x1, y0 + 2, o.z, 'glass_pane'); set(x2, y0 + 2, o.z, 'glass_pane'); set(o.x, y0 + 2, z1, 'glass_pane') // windows
    set(o.x, y0 + 1, o.z, 'lantern')                           // light
  }

  const gens = { house, tower, round_tower, wall, bridge, road, pyramid, dome, platform, castle, room }
  const alias = { home: 'house', hut: 'house', cabin: 'house', cottage: 'house', watchtower: 'tower', keep: 'tower', circle_tower: 'round_tower', rampart: 'wall', fort: 'castle', fortress: 'castle', path: 'road', igloo: 'dome' }

  // Catalog for the Architect prompt: skill name, arg hints, one-line purpose.
  // Every structure takes an origin {x,y,z} (bottom-centre) inside its args.
  const catalog = [
    ['house', '{x,y,z,width,length,height,material,floor,roof_material}', 'cottage: shell, door, windows, gable roof, lantern'],
    ['tower', '{x,y,z,size,height,material}', 'square keep with floors + crenellations'],
    ['round_tower', '{x,y,z,radius,height,material}', 'cylindrical tower with merlons'],
    ['wall', '{x,y,z,length,height,material,axis}', 'straight crenellated wall along axis x|z'],
    ['bridge', '{x,y,z,length,width,material,axis}', 'flat span with fence rails'],
    ['road', '{x,y,z,length,width,material,axis}', 'cleared path strip'],
    ['pyramid', '{x,y,z,base,material}', 'stepped solid pyramid'],
    ['dome', '{x,y,z,radius,material}', 'hemispherical dome with doorway'],
    ['platform', '{x,y,z,radius,material}', 'flat foundation slab one below origin'],
    ['castle', '{x,y,z,size,height,material}', 'walls + 4 corner towers + gate (a whole compound)'],
    ['room', '{x,y,z,width,length,height,material,floor}', 'single hollow room with door/windows/light']
  ]
  return { gens, alias, catalog }
}
