// grok/lib/build/compile.js — Layer-A blueprint -> Layer-B voxel target.
//
// Deterministic. Walks regions (sorted by phase) → components, rasterising each
// into the grid in LOCAL space (world = anchor.origin + local). Component kinds:
//   structural (box|wall|cylinder|dome|prism|line|slab) — rasterised here,
//        honouring `hollow` (shell only; interior stays UNSET = preserve world);
//   point      — one cell with an explicit block[state];
//   carve      — the ONLY producer of air besides a point whose block is "air";
//   skill      — invokes an existing generator/detailer/furnisher through an
//        adapter whose set/fillBox write into the TARGET GRID, not the world.
//        The adapter DROPS air writes, so a skill can never blank-clear — only
//        carve/point-air delete. This is the additive invariant in code.
//
// The compiler builds FRESH structures/detail/furniture instances bound to the
// grid ctx, so it reuses every bit of Grok's building intelligence without ever
// touching the live server.

const voxel = require('./voxel')
const { phaseNameFor } = require('./blueprint')
const { B, clamp, isAir, cleanBlock } = require('./util')

function compile(bp) {
  const min = bp.bounds.min, max = bp.bounds.max
  const size = { x: max.x - min.x + 1, y: max.y - min.y + 1, z: max.z - min.z + 1 }
  const originWorld = { x: bp.anchor.origin.x + min.x, y: bp.anchor.origin.y + min.y, z: bp.anchor.origin.z + min.z }
  const t = voxel.createTarget(originWorld, size)

  // current-component metadata for put() to attach to each cell
  let cur = { region: '', phase: 'walls', phaseNum: 1 }
  const put = (cx, cy, cz, block) => {
    const i = voxel.set(t, cx - min.x, cy - min.y, cz - min.z, block)
    if (i >= 0) t.meta.set(i, { region: cur.region, phase: cur.phase, phaseNum: cur.phaseNum })
    return i
  }

  // Grid-writing ctx for the reused generators. Air is DROPPED (additive).
  const gset = (x, y, z, b) => { if (isAir(b)) return; put(x, y, z, cleanBlock(b)) }
  const gfill = (x1, y1, z1, x2, y2, z2, b) => {
    if (isAir(b)) return
    const block = cleanBlock(b)
    const [ax, bx] = x1 <= x2 ? [x1, x2] : [x2, x1]
    const [ay, by] = y1 <= y2 ? [y1, y2] : [y2, y1]
    const [az, bz] = z1 <= z2 ? [z1, z2] : [z2, z1]
    for (let y = ay; y <= by; y++) for (let z = az; z <= bz; z++) for (let x = ax; x <= bx; x++) put(x, y, z, block)
  }
  const gctx = { enqueue: () => {}, set: gset, fillBox: gfill, B, clamp }
  const structures = require('../../structures')(gctx)
  const details = require('../../detail')(gctx)
  const furniture = require('../../furniture')(gctx)
  const reg = buildRegistry(structures, details, furniture)

  // Sort regions by phase then declaration order; deterministic.
  const regions = bp.regions.map((r, i) => ({ r, i })).sort((a, b) => (a.r.phase - b.r.phase) || (a.i - b.i)).map(x => x.r)
  for (const region of regions) {
    for (const comp of region.components) {
      cur = { region: region.id, phase: phaseNameFor(comp, region), phaseNum: region.phase }
      rasterize(comp, put, gfill, reg, bp.palette)
    }
  }
  t.name = bp.name
  t.palette_roles = bp.palette
  return t
}

function buildRegistry(structures, details, furniture) {
  const reg = {}
  const originOf = (a) => ({ x: Math.round(a.x != null ? +a.x : 0), y: Math.round(a.y != null ? +a.y : 0), z: Math.round(a.z != null ? +a.z : 0) })
  for (const [name, gen] of Object.entries(structures.gens)) reg[name] = (a) => gen(originOf(a), a)
  for (const [a, real] of Object.entries(structures.alias)) if (!reg[a] && reg[real]) reg[a] = reg[real]
  for (const [name, fn] of Object.entries(details.skills)) reg[name] = (a) => fn(a)
  for (const [name, fn] of Object.entries(furniture.skills)) reg[name] = (a) => fn(a)
  return reg
}

function rasterize(comp, put, gfill, reg, palette) {
  const kind = comp.kind
  if (kind === 'point') { if (comp.at) put(comp.at.x, comp.at.y, comp.at.z, comp.block); return }
  if (kind === 'carve') { fillBox(comp.a, comp.b, 'air', put, false); return }
  if (kind === 'skill') { runSkill(comp, reg, palette); return }
  const block = comp.block
  switch (kind) {
    case 'box': case 'slab': fillBox(comp.a, comp.b, block, put, comp.hollow); break
    case 'wall': fillBox(comp.a, comp.b, block, put, false); break
    case 'line': line3(comp.a, comp.b, block, put); break
    case 'cylinder': cylinder(comp.a, comp.b, block, put, comp.hollow); break
    case 'dome': dome(comp.a, comp.b, block, put); break
    case 'prism': prism(comp.a, comp.b, block, put); break
    default: fillBox(comp.a, comp.b, block, put, comp.hollow)
  }
}

function runSkill(comp, reg, palette) {
  const fn = reg[comp.skill]
  if (!fn) return
  const args = Object.assign({}, comp.args || {})
  // {a,b} → x1..z2 sugar for skills that take a rectangle.
  if (args.a && args.b) {
    args.x1 = args.a.x; args.y1 = args.a.y; args.z1 = args.a.z
    args.x2 = args.b.x; args.y2 = args.b.y; args.z2 = args.b.z
  }
  if (args.palette == null) args.palette = palette
  try { fn(args) } catch { /* a bad skill step never aborts the compile */ }
}

// ---- structural rasterizers (LOCAL coords) ----
function bounds3(a, b) {
  return [
    Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z),
    Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z)
  ]
}

function fillBox(a, b, block, put, hollow) {
  const [x1, y1, z1, x2, y2, z2] = bounds3(a, b)
  for (let y = y1; y <= y2; y++) for (let z = z1; z <= z2; z++) for (let x = x1; x <= x2; x++) {
    if (hollow) {
      const shell = x === x1 || x === x2 || y === y1 || y === y2 || z === z1 || z === z2
      if (!shell) continue
    }
    put(x, y, z, block)
  }
}

function line3(a, b, block, put) {
  let x = a.x, y = a.y, z = a.z
  const dx = Math.abs(b.x - a.x), dy = Math.abs(b.y - a.y), dz = Math.abs(b.z - a.z)
  const sx = a.x < b.x ? 1 : -1, sy = a.y < b.y ? 1 : -1, sz = a.z < b.z ? 1 : -1
  const n = Math.max(dx, dy, dz)
  if (n === 0) { put(x, y, z, block); return }
  let ex = dx - n / 2, ey = dy - n / 2, ez = dz - n / 2 // not used; simple 3D DDA below
  // Simple parametric stepping to guarantee a continuous line.
  for (let i = 0; i <= n; i++) {
    put(Math.round(a.x + (b.x - a.x) * i / n), Math.round(a.y + (b.y - a.y) * i / n), Math.round(a.z + (b.z - a.z) * i / n), block)
  }
}

// Vertical (y-axis) cylinder inscribed in the a..b box footprint.
function cylinder(a, b, block, put, hollow) {
  const [x1, y1, z1, x2, y2, z2] = bounds3(a, b)
  const cx = (x1 + x2) / 2, cz = (z1 + z2) / 2
  const rx = Math.max(0.5, (x2 - x1) / 2), rz = Math.max(0.5, (z2 - z1) / 2)
  for (let y = y1; y <= y2; y++) for (let z = z1; z <= z2; z++) for (let x = x1; x <= x2; x++) {
    const d = Math.hypot((x - cx) / rx, (z - cz) / rz)
    if (d > 1.05) continue
    if (hollow && d < 1 - 1.4 / Math.max(rx, rz)) continue // interior stays UNSET
    put(x, y, z, block)
  }
}

// Hemispherical dome: surface shell over the a..b footprint, apex up.
function dome(a, b, block, put) {
  const [x1, y1, z1, x2, y2, z2] = bounds3(a, b)
  const cx = (x1 + x2) / 2, cz = (z1 + z2) / 2
  const r = Math.max((x2 - x1) / 2, (z2 - z1) / 2)
  for (let x = x1; x <= x2; x++) for (let z = z1; z <= z2; z++) for (let dy = 0; dy <= r; dy++) {
    const d = Math.sqrt((x - cx) ** 2 + dy ** 2 + (z - cz) ** 2)
    if (d > r - 0.6 && d <= r + 0.4) put(x, y1 + dy, z, block)
  }
}

// Triangular gable prism over the a..b footprint (ridge along the longer axis).
function prism(a, b, block, put) {
  const [x1, y1, z1, x2, y2, z2] = bounds3(a, b)
  const spanX = x2 - x1, spanZ = z2 - z1
  if (spanX <= spanZ) { // ridge along z, slope on x
    const half = Math.floor(spanX / 2)
    for (let i = 0; i <= half; i++) {
      const yy = y1 + i, xl = x1 + i, xr = x2 - i
      if (xl > xr) break
      for (let z = z1; z <= z2; z++) { put(xl, yy, z, block); put(xr, yy, z, block) }
    }
  } else { // ridge along x, slope on z
    const half = Math.floor(spanZ / 2)
    for (let i = 0; i <= half; i++) {
      const yy = y1 + i, zl = z1 + i, zr = z2 - i
      if (zl > zr) break
      for (let x = x1; x <= x2; x++) { put(x, yy, zl, block); put(x, yy, zr, block) }
    }
  }
}

module.exports = { compile }
