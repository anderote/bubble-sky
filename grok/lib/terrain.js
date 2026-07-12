// grok/lib/terrain.js — WORLD-SHAPING / earthworks ops for Grok.
//
// Where structures.js / architect BUILD objects, this module CARVES and MOVES
// terrain: bore a tunnel into a hillside, cut a trench, dig a pit, hollow out a
// space, dig a moat. Everything routes through the SAME throttled `hands` the
// build pipeline uses (fillBox / setBlock / clearArea), so it respects godmode +
// the STOP fast-path and works on both transports.
//
// All ops are ADDITIVE carving (set cells to AIR) unless a `block` lines a floor.
// Deterministic + side-effect-free aside from the hands calls, so they unit-test
// offline against a fake `hands` that records fillBox/setBlock calls.
//
//   const terrain = makeTerrain({ hands, fillBox, clearArea, setBlock, B, clamp })
//   terrain.tunnel({ ox, oy, oz, dx, dz, length, width, height, shape })
//
// DIRECTION: (dx,dz) is the horizontal travel unit. Callers derive it from the
// vector (lookedAtBlock − speakerPosition) so "tunnel into the mountain" bores
// the way the player faces; tunnel/trench/moat snap it to the dominant axis for
// clean, grid-aligned cuts.

module.exports = function makeTerrain(ctx) {
  const hands = ctx.hands || {}
  const fillBox = ctx.fillBox || (hands.fillBox && hands.fillBox.bind(hands))
  const setBlock = ctx.setBlock || (hands.setBlock && ((x, y, z, b) => hands.setBlock(x, y, z, b)))
  const B = ctx.B || (s => String(s || '').toLowerCase().replace(/^minecraft:/, '').replace(/[^a-z0-9_]/g, ''))
  const clamp = ctx.clamp || ((v, lo, hi, def) => { const n = parseInt(v, 10); return isNaN(n) ? def : Math.min(Math.max(n, lo), hi) })

  const air = (x1, y1, z1, x2, y2, z2) => fillBox(x1, y1, z1, x2, y2, z2, 'air')

  // Snap an arbitrary horizontal vector to a unit along the dominant axis so cuts
  // are grid-aligned. (0,0) → +x default.
  function dominant(dx, dz) {
    dx = +dx || 0; dz = +dz || 0
    if (Math.abs(dx) >= Math.abs(dz)) { dx = dx < 0 ? -1 : 1; dz = 0 }
    else { dz = dz < 0 ? -1 : 1; dx = 0 }
    return { dx, dz }
  }

  // ---- tunnel: bore a horizontal shaft from origin along (dx,dz) ----------------
  // shape:'round' → circular cross-section of diameter = width, floor at oy.
  // shape:'square' → width×height rectangle, floor at oy.
  function tunnel({ ox, oy, oz, dx, dz, length, width, height, shape } = {}) {
    ;({ dx, dz } = dominant(dx, dz))
    const W = clamp(width, 1, 16, 5)
    const H = clamp(height, 1, 16, W)
    const L = clamp(length, 1, 64, 16)
    const round = !/^sq/i.test(String(shape || 'round')) && String(shape || 'round') !== 'box'
    // perpendicular horizontal axis (axis-aligned since dx,dz snapped to an axis)
    const px = dz, pz = -dx
    let cells = 0, lights = 0
    for (let i = 0; i < L; i++) {
      const cx = ox + dx * i, cz = oz + dz * i
      if (round) {
        // diameter W circle in the (perp, vertical) plane, floor at oy
        const c = (W - 1) / 2, R2 = (W / 2) * (W / 2)
        for (let a = 0; a < W; a++) {
          const off = a - c
          for (let j = 0; j < W; j++) {
            if (off * off + (j - c) * (j - c) > R2) continue
            setBlock(cx + px * off, oy + j, cz + pz * off, 'air'); cells++
          }
        }
      } else {
        const half = Math.floor((W - 1) / 2)
        // one box across the full perp width × height for this slice
        air(cx + px * -half, oy, cz + pz * -half, cx + px * (W - 1 - half), oy + H - 1, cz + pz * (W - 1 - half))
        cells += W * H
      }
      // light the floor every ~6 blocks (torch sits on the un-carved block at oy-1)
      if (i % 6 === 0 && i > 0) { setBlock(cx, oy, cz, 'torch'); lights++ }
    }
    return { op: 'tunnel', dx, dz, length: L, width: W, height: round ? W : H, shape: round ? 'round' : 'square', cells, lights }
  }

  // ---- trench: a linear cut into the ground along (dx,dz) ------------------------
  // Removes a width×depth slot downward from the surface at y, `length` long.
  // Optional `block` lines the trench floor.
  function trench({ x, y, z, dx, dz, length, width, depth, block } = {}) {
    ;({ dx, dz } = dominant(dx, dz))
    const W = clamp(width, 1, 16, 3)
    const D = clamp(depth, 1, 32, 3)
    const L = clamp(length, 1, 64, 12)
    const px = dz, pz = -dx
    const half = Math.floor((W - 1) / 2)
    let cells = 0
    for (let i = 0; i < L; i++) {
      const cx = x + dx * i, cz = z + dz * i
      air(cx + px * -half, y - D + 1, cz + pz * -half, cx + px * (W - 1 - half), y, cz + pz * (W - 1 - half))
      cells += W * D
      if (block) {
        const b = B(block, 'stone')
        fillBox(cx + px * -half, y - D, cz + pz * -half, cx + px * (W - 1 - half), y - D, cz + pz * (W - 1 - half), b)
      }
    }
    return { op: 'trench', dx, dz, length: L, width: W, depth: D, cells, lined: !!block }
  }

  // ---- pit: dig a rectangular or round hole straight down to `depth` -------------
  function pit({ x, y, z, radius, width, length, depth } = {}) {
    const D = clamp(depth, 1, 32, 5)
    const y1 = y - D + 1
    if (radius != null) {
      const R = clamp(radius, 1, 16, 4)
      const R2 = R * R
      let cells = 0
      for (let a = -R; a <= R; a++) for (let b = -R; b <= R; b++) {
        if (a * a + b * b > R2) continue
        air(x + a, y1, z + b, x + a, y, z + b); cells += D
      }
      return { op: 'pit', shape: 'round', radius: R, depth: D, cells }
    }
    const W = clamp(width, 1, 32, 6), Ln = clamp(length, 1, 32, W)
    const hw = Math.floor((W - 1) / 2), hl = Math.floor((Ln - 1) / 2)
    air(x - hw, y1, z - hl, x + (W - 1 - hw), y, z + (Ln - 1 - hl))
    return { op: 'pit', shape: 'rect', width: W, length: Ln, depth: D, cells: W * Ln * D }
  }

  // ---- hollow: empty the interior of a box, leaving a 1-block shell --------------
  function hollow({ x1, y1, z1, x2, y2, z2 } = {}) {
    const xa = Math.min(x1, x2), xb = Math.max(x1, x2)
    const ya = Math.min(y1, y2), yb = Math.max(y1, y2)
    const za = Math.min(z1, z2), zb = Math.max(z1, z2)
    // interior only (leave a 1-block shell); if too thin, clear the whole volume
    const ix1 = xa + 1, ix2 = xb - 1, iy1 = ya + 1, iy2 = yb - 1, iz1 = za + 1, iz2 = zb - 1
    if (ix1 > ix2 || iy1 > iy2 || iz1 > iz2) { air(xa, ya, za, xb, yb, zb); return { op: 'hollow', shell: false, cells: (xb - xa + 1) * (yb - ya + 1) * (zb - za + 1) } }
    air(ix1, iy1, iz1, ix2, iy2, iz2)
    return { op: 'hollow', shell: true, cells: (ix2 - ix1 + 1) * (iy2 - iy1 + 1) * (iz2 - iz1 + 1) }
  }

  // ---- moat: a trench that (optionally) fills its bottom layer with water --------
  function moat({ x, y, z, dx, dz, length, width, depth, water } = {}) {
    ;({ dx, dz } = dominant(dx, dz))
    const D = clamp(depth, 1, 32, 3)
    const r = trench({ x, y, z, dx, dz, length, width, depth: D })
    if (water !== false) {
      const px = dz, pz = -dx, half = Math.floor((r.width - 1) / 2)
      for (let i = 0; i < r.length; i++) {
        const cx = x + dx * i, cz = z + dz * i
        fillBox(cx + px * -half, y - D + 1, cz + pz * -half, cx + px * (r.width - 1 - half), y - D + 1, cz + pz * (r.width - 1 - half), 'water')
      }
    }
    return { op: 'moat', dx, dz, length: r.length, width: r.width, depth: D, water: water !== false, cells: r.cells }
  }

  // ---- ramp: a sloped cut descending along (dx,dz) (optional) --------------------
  function ramp({ x, y, z, dx, dz, length, width, drop } = {}) {
    ;({ dx, dz } = dominant(dx, dz))
    const W = clamp(width, 1, 16, 3)
    const L = clamp(length, 1, 64, 12)
    const Dr = clamp(drop, 1, 32, L)
    const px = dz, pz = -dx, half = Math.floor((W - 1) / 2)
    let cells = 0
    for (let i = 0; i < L; i++) {
      const cx = x + dx * i, cz = z + dz * i
      const floorY = y - Math.round((Dr * i) / L)      // step the floor down along travel
      air(cx + px * -half, floorY, cz + pz * -half, cx + px * (W - 1 - half), y + 2, cz + pz * (W - 1 - half))
      cells += W
    }
    return { op: 'ramp', dx, dz, length: L, width: W, drop: Dr, cells }
  }

  return { tunnel, trench, pit, hollow, moat, ramp, dominant }
}
