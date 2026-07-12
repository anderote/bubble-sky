// grok/lib/build/archetypes.js — COMPOSITIONAL whole-building composers.
//
// Each archetype assembles modules.js building blocks into a genuinely different
// silhouette + interior, honouring the requested footprint (width,length,wallH
// from the design / parseDims) and adding SEEDED variation (a stable hash of the
// goal+origin → varied wing counts, L-vs-rectangle, tower placement, story count,
// roof style). The output is a full Layer-A blueprint; normalize()/anchorToSite()
// downstream refit bounds + terrain, so composers only need valid regions.
//
// Catalog: cottage · house/manor · keep · castle · tower/wizard_tower ·
//          cathedral/hall · fort. `compose(design)` dispatches by design.archetype.

const M = require('./modules')
const { resolvePalette } = require('../../palettes')
const {
  roomBox, floorSlab, interiorWall, doorRegion, staircase, squareTower, roundTower,
  curtainWall, gatehouse, courtyard, entrance, porch, chimney, spire, gableRoof,
  flatBattlementRoof, windowBand, arrowSlits, via, forum, region, box, sk, pt, woodOf, clampi
} = M

// ---- seeded RNG (stable per goal+origin, varied between builds) ----
function hashStr(s) {
  let h = 1779033703 ^ s.length
  for (let i = 0; i < s.length; i++) { h = Math.imul(h ^ s.charCodeAt(i), 3432918353); h = (h << 13) | (h >>> 19) }
  return () => { h = Math.imul(h ^ (h >>> 16), 2246822507); h = Math.imul(h ^ (h >>> 13), 3266489909); return (h ^= h >>> 16) >>> 0 }
}
function rng(seedStr) {
  const g = hashStr(String(seedStr || 'build'))
  for (let i = 0; i < 8; i++) g() // warm up: decorrelate the first draws from the seed
  return () => (g() % 100000) / 100000
}
function seedOf(o) { return String(o.seed || o.name || 'build') }

const pick = (R, arr) => arr[Math.floor(R() * arr.length) % arr.length]
// Consume the next room type from the LLM's ordered room program (opts._roomQueue,
// seeded by planBlueprint from design.roomList); fall back to a seeded pick when the
// program is exhausted or absent. Lets a designed program drive interior FUNCTION
// while the archetype keeps correct geometry.
function takeRoom(opts, fallback) {
  const q = opts && opts._roomQueue
  if (Array.isArray(q) && q.length) return q.shift()
  return fallback
}
// dim: explicit value wins (clamped); else a seeded pick in [lo,hi]; else def.
function dim(v, lo, hi, def, R) {
  const n = parseInt(v, 10)
  if (!isNaN(n)) return Math.min(Math.max(n, lo), hi)
  if (R) return lo + Math.floor(R() * (hi - lo + 1))
  return def
}
function pal(opts) {
  const P = resolvePalette(opts.palette || opts.style)
  P.__style = opts.style || 'medieval'
  return P
}

// Wrap composed regions into a normalized-ready blueprint. De-dupes region ids.
function envelope(opts, P, regions) {
  const seen = {}
  for (const r of regions) {
    if (!r || !r.id) continue
    if (seen[r.id] != null) { seen[r.id]++; r.id = `${r.id}-${seen[r.id]}` } else seen[r.id] = 0
  }
  return {
    schemaVersion: 1,
    name: opts.name || 'Build',
    style: opts.style || P.__style || 'medieval',
    palette: P,
    anchor: { origin: opts.origin || { x: 0, y: 64, z: 0 }, facing: opts.facing || 'north', terrainFit: opts.terrainFit || 'follow' },
    bounds: { min: { x: -3, y: 0, z: -3 }, max: { x: 3, y: 8, z: 3 } }, // fitBounds expands
    regions
  }
}

// Furnish + light a rectangular interior of one story.
function furnish(tag, type, x, z, w, l, fy, ceilTop, P) {
  return region(`${tag}-furnish`, `${tag} ${type}`, 8, [
    sk('furnishRoom', { type, x1: x + 1, y1: fy + 1, z1: z + 1, x2: x + w - 1, y2: ceilTop - 1, z2: z + l - 1, palette: P }),
    sk('lightingCadence', { x1: x + 1, z1: z + 1, x2: x + w - 1, z2: z + l - 1, y0: fy + 1, yTop: ceilTop - 1, every: 5, block: P.light })
  ])
}

// Standard exterior detailing pass (depth + exterior lighting + plinth).
function exteriorDetail(tag, x, z, w, l, baseY, topY, P) {
  return region(`${tag}-ext`, `${tag} Exterior`, 9, [
    sk('wallGreeble', { x1: x, z1: z, x2: x + w, z2: z + l, y0: baseY, y1: topY, wall: P.wall, accent: P.accent }),
    sk('torchCadence', { x1: x, z1: z, x2: x + w, z2: z + l, y: baseY + 2, every: 5, block: P.light }),
    sk('plinth', { x1: x, z1: z, x2: x + w, z2: z + l, y: 0, out: 1, block: P.foundation })
  ])
}

// Shift a composed region (all local coords) by (dx,dy,dz) and re-tag its id.
function offsetComp(c, dx, dy, dz) {
  const o = Object.assign({}, c)
  if (c.a) o.a = { x: c.a.x + dx, y: c.a.y + dy, z: c.a.z + dz }
  if (c.b) o.b = { x: c.b.x + dx, y: c.b.y + dy, z: c.b.z + dz }
  if (c.at) o.at = { x: c.at.x + dx, y: c.at.y + dy, z: c.at.z + dz }
  if (c.kind === 'skill') {
    const a = Object.assign({}, c.args || {})
    for (const k of ['x', 'x1', 'x2']) if (a[k] != null) a[k] = +a[k] + dx
    for (const k of ['y', 'y1', 'y2', 'y0', 'yTop']) if (a[k] != null) a[k] = +a[k] + dy
    for (const k of ['z', 'z1', 'z2']) if (a[k] != null) a[k] = +a[k] + dz
    o.args = a
  }
  return o
}
function shiftRegions(regions, dx, dy, dz, prefix) {
  return regions.map(r => ({ id: (prefix || '') + r.id, name: r.name, phase: r.phase, components: r.components.map(c => offsetComp(c, dx, dy, dz)) }))
}

// ============================ COTTAGE ============================
function cottage(opts) {
  const P = pal(opts), R = rng(seedOf(opts))
  const W = dim(opts.width, 8, 14, 10, R), L = dim(opts.length, 8, 14, 10, R), storyH = dim(opts.wallH, 4, 6, 5)
  const baseY = 1, topY = baseY + storyH
  const regs = []
  regs.push(...roomBox({ tag: 'main', x: 0, z: 0, w: W, l: L, baseY, storyH, stories: 1, P }))

  // Seeded L-shaped wing off the +x back corner.
  const lShape = R() < 0.5
  let wing = null
  if (lShape) {
    const ww = dim(null, 5, 7, 6, R), wl = dim(null, 5, 7, 6, R)
    wing = { x: W - 1, z: L - wl, w: ww, l: wl }
    regs.push(...roomBox({ tag: 'wing', x: wing.x, z: wing.z, w: ww, l: wl, baseY, storyH, stories: 1, P }))
    regs.push(...gableRoof({ tag: 'wroof', x: wing.x, z: wing.z, w: ww, l: wl, y: topY + 1, P }))
    regs.push(doorRegion('wingdoor', wing.x, baseY + 1, wing.z + Math.floor(wl / 2), 'east', P))
    regs.push(furnish('wing', pick(R, ['bedroom', 'kitchen']), wing.x, wing.z, ww, wl, baseY, topY, P))
  }

  // Split the main block into two rooms when long enough.
  if (L >= 10) {
    const mz = Math.floor(L / 2)
    regs.push(...interiorWall({ tag: 'part', x1: 1, z1: mz, x2: W - 1, z2: mz, y0: baseY, y1: topY, P }))
    regs.push(doorRegion('innerdoor', Math.floor(W / 2), baseY + 1, mz, 'south', P))
    regs.push(furnish('r1', takeRoom(opts, pick(R, ['living', 'kitchen'])), 0, 0, W, mz, baseY, topY, P))
    regs.push(furnish('r2', takeRoom(opts, pick(R, ['bedroom', 'library', 'living'])), 0, mz, W, L - mz, baseY, topY, P))
  } else {
    regs.push(furnish('r1', takeRoom(opts, 'living'), 0, 0, W, L, baseY, topY, P))
  }

  regs.push(...gableRoof({ tag: 'roof', x: 0, z: 0, w: W, l: L, y: topY + 1, P }))
  regs.push(...chimney({ tag: 'chim', x: R() < 0.5 ? 1 : W - 1, y: baseY, z: Math.floor(L / 2), top: topY + dim(null, 3, 5, 4, R), P }))
  const doorX = Math.floor(W / 2)
  regs.push(...entrance({ tag: 'ent', x: doorX, y: baseY, z: 0, facing: 'south', P }))
  regs.push(...porch({ tag: 'porch', x: doorX, y: baseY, z: 0, facing: 'north', w: 3, P }))
  regs.push(...windowBand({ tag: 'win', x: 0, z: 0, w: W, l: L, y: baseY + 2, P, spacing: 3, height: 1 }))
  regs.push(exteriorDetail('cot', 0, 0, W, L, baseY, topY, P))
  return envelope(opts, P, regs)
}

// ======================= HOUSE / MANOR =======================
function house(opts) {
  const P = pal(opts), R = rng(seedOf(opts))
  const manor = /manor|mansion|estate|villa/.test(String(opts.archetype || '')) || (opts.wings != null && +opts.wings >= 2)
  const W = dim(opts.width, manor ? 16 : 12, manor ? 28 : 18, manor ? 22 : 14, R)
  const L = dim(opts.length, manor ? 12 : 10, manor ? 20 : 16, manor ? 16 : 12, R)
  const storyH = dim(opts.wallH, 4, 6, 5)
  const stories = clampi(opts.stories, 1, 3, 2)
  const baseY = 1, topY = baseY + stories * storyH
  const regs = []
  regs.push(...roomBox({ tag: 'hall', x: 0, z: 0, w: W, l: L, baseY, storyH, stories, P }))

  // Wings: manor gets 2, a house gets 0-1 (seeded).
  const wingCount = manor ? 2 : (opts.wings != null ? clampi(opts.wings, 0, 2, 0) : (R() < 0.5 ? 1 : 0))
  const wingList = []
  if (wingCount >= 1) { const ww = dim(null, 6, 9, 7, R), wl = Math.max(6, Math.round(L * 0.7)); wingList.push({ tag: 'wingW', x: -ww + 1, z: Math.round((L - wl) / 2), w: ww, l: wl }) }
  if (wingCount >= 2) { const ww = dim(null, 6, 9, 7, R), wl = Math.max(6, Math.round(L * 0.7)); wingList.push({ tag: 'wingE', x: W - 1, z: Math.round((L - wl) / 2), w: ww, l: wl }) }
  for (const wg of wingList) {
    regs.push(...roomBox({ tag: wg.tag, x: wg.x, z: wg.z, w: wg.w, l: wg.l, baseY, storyH, stories, P }))
    regs.push(...gableRoof({ tag: `${wg.tag}-roof`, x: wg.x, z: wg.z, w: wg.w, l: wg.l, y: topY + 1, P }))
    const cxDoor = wg.x < 0 ? wg.x + wg.w : wg.x
    regs.push(doorRegion(`${wg.tag}-door`, cxDoor, baseY + 1, wg.z + Math.floor(wg.l / 2), 'east', P))
    regs.push(furnish(`${wg.tag}-f`, takeRoom(opts, pick(R, ['bedroom', 'library', 'living'])), wg.x, wg.z, wg.w, wg.l, baseY, baseY + storyH, P))
  }

  if (stories >= 2) regs.push(...staircase({ tag: 'stair', x: 2, y: baseY, z: 2, facing: 'east', storyH, P }))

  // Ground floor split into two rooms; upper floor into two bedrooms/library.
  const mz = Math.floor(L / 2)
  regs.push(...interiorWall({ tag: 'gpart', x1: 1, z1: mz, x2: W - 1, z2: mz, y0: baseY, y1: baseY + storyH, P }))
  regs.push(doorRegion('gpartdoor', Math.floor(W / 2), baseY + 1, mz, 'south', P))
  regs.push(furnish('g1', takeRoom(opts, pick(R, ['great_hall', 'living', 'kitchen'])), 0, 0, W, mz, baseY, baseY + storyH, P))
  regs.push(furnish('g2', takeRoom(opts, pick(R, ['kitchen', 'living'])), 0, mz, W, L - mz, baseY, baseY + storyH, P))
  if (stories >= 2) {
    const uy = baseY + storyH
    regs.push(...interiorWall({ tag: 'upart', x1: 1, z1: mz, x2: W - 1, z2: mz, y0: uy, y1: uy + storyH, P }))
    regs.push(doorRegion('upartdoor', Math.floor(W / 2), uy + 1, mz, 'south', P))
    regs.push(furnish('u1', takeRoom(opts, pick(R, ['bedroom', 'library'])), 0, 0, W, mz, uy, uy + storyH, P))
    regs.push(furnish('u2', takeRoom(opts, pick(R, ['bedroom', 'living'])), 0, mz, W, L - mz, uy, uy + storyH, P))
    regs.push(...windowBand({ tag: 'winU', x: 0, z: 0, w: W, l: L, y: uy + 2, P, spacing: 3, height: 2 }))
  }

  regs.push(...gableRoof({ tag: 'roof', x: 0, z: 0, w: W, l: L, y: topY + 1, P }))
  const doorX = Math.floor(W / 2)
  regs.push(...entrance({ tag: 'ent', x: doorX, y: baseY, z: 0, facing: 'south', P }))
  regs.push(...porch({ tag: 'porch', x: doorX, y: baseY, z: 0, facing: 'north', w: 3, P }))
  regs.push(...windowBand({ tag: 'winG', x: 0, z: 0, w: W, l: L, y: baseY + 2, P, spacing: 3, height: 2 }))
  regs.push(exteriorDetail('hs', 0, 0, W, L, baseY, topY, P))
  if (manor) regs.push(...chimney({ tag: 'chim', x: W - 2, y: baseY, z: 2, top: topY + 3, P }))
  return envelope(opts, P, regs)
}

// ============================= KEEP =============================
function keep(opts) {
  const P = pal(opts), R = rng(seedOf(opts))
  const W = dim(opts.width, 10, 22, 14, R), L = dim(opts.length, 10, 22, 14, R), storyH = 5
  const stories = clampi(opts.stories, 2, 4, 3)
  const baseY = 1, topY = baseY + stories * storyH
  const regs = []
  regs.push(...roomBox({ tag: 'keep', x: 0, z: 0, w: W, l: L, baseY, storyH, stories, P, pillarEvery: 3 }))
  regs.push(...staircase({ tag: 'stairA', x: 2, y: baseY, z: 2, facing: 'east', storyH, P }))
  if (stories >= 3) regs.push(...staircase({ tag: 'stairB', x: 2, y: baseY + storyH, z: L - 3, facing: 'east', storyH, P }))

  const floorTypes = ['great_hall', 'bedroom', 'library', 'living', 'armory', 'kitchen']
  for (let s = 0; s < stories; s++) {
    const fy = baseY + s * storyH
    regs.push(furnish(`lvl${s}`, takeRoom(opts, s === 0 ? 'great_hall' : pick(R, floorTypes)), 0, 0, W, L, fy, fy + storyH, P))
    regs.push(...windowBand({ tag: `slit${s}`, x: 0, z: 0, w: W, l: L, y: fy + 2, P, spacing: 5, height: 1 }))
  }

  regs.push(flatBattlementRoof({ tag: 'roof', x: 0, z: 0, w: W, l: L, y: topY, P })[0])
  // Seeded corner turrets rising above the battlements.
  if (R() < 0.75) for (const [cx, cz] of [[0, 0], [0, L], [W, 0], [W, L]]) regs.push(...squareTower({ tag: `turret-${cx}-${cz}`, cx, cz, r: 1, y0: baseY, h: (topY - baseY) + dim(null, 2, 4, 3, R), P, roof: 'battlement', stories: 1, storyH: topY }))

  regs.push(...entrance({ tag: 'ent', x: Math.floor(W / 2), y: baseY, z: 0, facing: 'south', P }))
  regs.push(...arrowSlits({ tag: 'keep', x1: 0, z1: 0, x2: W, z2: L, y: baseY + Math.floor(storyH * stories / 2), P, every: 6 }))
  regs.push(exteriorDetail('kp', 0, 0, W, L, baseY, topY, P))
  return envelope(opts, P, regs)
}

// ============================ CASTLE ============================
function castle(opts) {
  const P = pal(opts), R = rng(seedOf(opts))
  const W = dim(opts.width, 30, 128, 60, R), L = dim(opts.length, 30, 128, 60, R)
  const wallH = dim(opts.wallH, 6, 24, 10)
  const spacing = clampi(opts.towerSpacing, 8, 80, 25)
  const baseY = 1, th = 2
  const x1 = 0, z1 = 0, x2 = W, z2 = L
  const regs = []
  regs.push(...curtainWall({ tag: 'wallN', x1, z1, x2, z2: z1 + th, y0: baseY, h: wallH, P }))
  regs.push(...curtainWall({ tag: 'wallS', x1, z1: z2 - th, x2, z2, y0: baseY, h: wallH, P }))
  regs.push(...curtainWall({ tag: 'wallW', x1, z1, x2: x1 + th, z2, y0: baseY, h: wallH, P }))
  regs.push(...curtainWall({ tag: 'wallE', x1: x2 - th, z1, x2, z2, y0: baseY, h: wallH, P }))
  regs.push(...courtyard({ tag: 'court', x: x1 + th, z: z1 + th, w: W - 2 * th, l: L - 2 * th, y: baseY - 1, P }))
  regs.push(...arrowSlits({ tag: 'curtain', x1, z1, x2, z2, y: baseY + Math.floor(wallH / 2), P, every: 8 }))

  const tr = Math.max(3, Math.round(wallH / 3))
  const th2 = wallH + dim(null, 4, 8, 6, R)
  const tst = Math.max(2, Math.round(th2 / 5))
  const gateX = Math.floor(W / 2)
  for (const [cx, cz] of [[x1, z1], [x1, z2], [x2, z1], [x2, z2]]) regs.push(...roundTower({ tag: `corner-${cx}-${cz}`, cx, cz, r: tr, y0: baseY, h: th2, P, cone: true, stories: tst }))
  const along = (span, fn) => { for (let d = spacing; d < span; d += spacing) { if (Math.min(d, span - d) < spacing * 0.4) continue; fn(d) } }
  along(W, (x) => {
    if (Math.abs(x - gateX) >= 5) regs.push(...roundTower({ tag: `tN-${x}`, cx: x, cz: z1, r: tr, y0: baseY, h: th2, P, stories: tst }))
    regs.push(...roundTower({ tag: `tS-${x}`, cx: x, cz: z2, r: tr, y0: baseY, h: th2, P, stories: tst }))
  })
  along(L, (z) => {
    regs.push(...roundTower({ tag: `tW-${z}`, cx: x1, cz: z, r: tr, y0: baseY, h: th2, P, stories: tst }))
    regs.push(...roundTower({ tag: `tE-${z}`, cx: x2, cz: z, r: tr, y0: baseY, h: th2, P, stories: tst }))
  })

  regs.push(...gatehouse({ tag: 'gate', cx: gateX, cz: z1 + 1, axis: 'x', y0: baseY, h: wallH + 3, gateW: 4, P }))

  // Inner keep in the courtyard centre (a full keep, shifted into place).
  const kW = Math.max(10, Math.round(W * 0.3)), kL = Math.max(10, Math.round(L * 0.3))
  const kx = Math.floor((W - kW) / 2), kz = Math.floor((L - kL) / 2)
  const inner = keep(Object.assign({}, opts, { archetype: 'keep', width: kW, length: kL, wallH: Math.max(12, wallH + 5), name: (opts.name || 'Castle') + ' Keep' }))
  regs.push(...shiftRegions(inner.regions, kx, 0, kz, 'inner_'))
  return envelope(opts, P, regs)
}

// ===================== TOWER / WIZARD TOWER =====================
function tower(opts) {
  const P = pal(opts), R = rng(seedOf(opts))
  const wizard = /wizard|mage|sorc|arcane|magic/.test(String(opts.archetype || opts.name || ''))
  const r = Math.max(3, Math.floor(dim(opts.width, 7, 15, 9, R) / 2))
  const h = dim(opts.wallH, 14, 44, wizard ? 26 : 22)
  const storyH = 5, stories = Math.max(3, Math.round(h / storyH))
  const baseY = 1
  const cx = r + 1, cz = r + 1
  const regs = []
  regs.push(...roundTower({ tag: 'tower', cx, cz, r, y0: baseY, h, P, cone: true, balcony: true, stories, storyH }))
  for (let s = 0; s < stories - 1; s++) regs.push(...staircase({ tag: `stair${s}`, x: cx - r + 1, y: baseY + s * storyH, z: cz, facing: 'east', storyH, P }))
  const types = ['library', 'bedroom', 'living', 'great_hall', 'workshop']
  for (let s = 0; s < stories; s++) { const fy = baseY + s * storyH; regs.push(furnish(`lvl${s}`, takeRoom(opts, s === 0 ? 'living' : pick(R, types)), cx - r, cz - r, 2 * r, 2 * r, fy, fy + storyH, P)) }
  regs.push(...entrance({ tag: 'ent', x: cx, y: baseY, z: cz - r, facing: 'south', P }))
  return envelope(opts, P, regs)
}

// ======================= CATHEDRAL / HALL =======================
function cathedral(opts) {
  const P = pal(opts), R = rng(seedOf(opts))
  const W = dim(opts.width, 14, 30, 20, R), L = dim(opts.length, 28, 64, 40, R)
  const wallH = dim(opts.wallH, 10, 26, 16)
  const baseY = 1, topY = baseY + wallH
  const regs = []
  regs.push(...roomBox({ tag: 'nave', x: 0, z: 0, w: W, l: L, baseY, storyH: wallH, stories: 1, P, pillarEvery: 4 }))

  // Transept crossing (perpendicular arms) at ~30% down the nave.
  const tW = dim(null, 8, 14, 10, R), tL = Math.round(W * 1.6)
  const tx = -Math.round((tL - W) / 2), tz = Math.round(L * 0.3)
  const tTop = baseY + Math.round(wallH * 0.8)
  regs.push(...roomBox({ tag: 'transept', x: tx, z: tz, w: tL, l: tW, baseY, storyH: Math.round(wallH * 0.8), stories: 1, P }))

  regs.push(...windowBand({ tag: 'winL', x: 0, z: 0, w: W, l: L, y: baseY + 3, P, spacing: 4, height: Math.min(6, wallH - 5) }))
  regs.push(region('buttress', 'Buttresses', 9, [
    sk('wallGreeble', { x1: 0, z1: 0, x2: W, z2: L, y0: baseY, y1: topY, wall: P.wall, accent: P.accent }),
    sk('pillarGrid', { x1: 0, z1: 0, x2: W, z2: L, y0: baseY, y1: topY, every: 4, block: P.pillar }),
    sk('trimCourse', { x1: 0, z1: 0, x2: W, z2: L, y: topY, block: P.trim })
  ]))

  regs.push(...gableRoof({ tag: 'roof', x: 0, z: 0, w: W, l: L, y: topY + 1, P }))
  regs.push(...gableRoof({ tag: 'troof', x: tx, z: tz, w: tL, l: tW, y: tTop + 1, P }))

  // Bell tower at the front (one side) + a spire over the crossing.
  const btW = Math.max(6, Math.round(W * 0.45))
  regs.push(...squareTower({ tag: 'bell', cx: Math.floor(btW / 2), cz: -Math.floor(btW / 2), r: Math.floor(btW / 2), y0: baseY, h: wallH + dim(null, 6, 14, 10, R), P, roof: 'spire', stories: Math.max(2, Math.round(wallH / 5)) }))
  regs.push(...spire({ tag: 'crossspire', cx: Math.floor(W / 2), cz: tz + Math.floor(tW / 2), y: topY + Math.round(W / 2), r: Math.max(3, Math.floor(W / 2)), h: Math.round(W * 0.8), P }))

  regs.push(...entrance({ tag: 'ent', x: Math.floor(W / 2), y: baseY, z: 0, facing: 'south', P }))
  regs.push(region('portal', 'Portal', 6, [sk('archway', { x: Math.floor(W / 2), y: baseY, z: 0, width: 4, height: 5, axis: 'x', block: P.wall })]))
  const naveType = takeRoom(opts, /librar/.test(String(opts.roomType)) ? 'library' : /chapel|church|temple/.test(String(opts.archetype || opts.name || '')) ? 'chapel' : 'great_hall')
  regs.push(furnish('nave', naveType, 0, 0, W, L, baseY, topY, P))
  regs.push(exteriorDetail('cat', 0, 0, W, L, baseY, topY, P))
  return envelope(opts, P, regs)
}

// ============================= FORT (Roman castrum) =============================
// A rampart + corner towers + N/S gatehouses around a paved parade, split by a
// central VIA (cross street) with a FORUM (well) at the crossing. Interior
// buildings are ACTUAL furnished rooms arranged in bands around the via —
// barracks (bunk rows) up front, an armory + granary flanking the forum, and a
// principia (HQ great hall) at the rear — with seeded variety so no two forts match.
function fort(opts) {
  const P = pal(opts), R = rng(seedOf(opts))
  const W = dim(opts.width, 30, 80, 44, R), L = dim(opts.length, 26, 64, 36, R)
  const wallH = dim(opts.wallH, 5, 12, 7)
  const baseY = 1, th = 2, storyH = 5
  const x1 = 0, z1 = 0, x2 = W, z2 = L
  const cx = Math.floor(W / 2), cz = Math.floor(L / 2), viaHW = 2
  const regs = []

  // --- rampart, corner towers, gatehouses, arrow slits ---
  regs.push(...curtainWall({ tag: 'rN', x1, z1, x2, z2: z1 + th, y0: baseY, h: wallH, P }))
  regs.push(...curtainWall({ tag: 'rS', x1, z1: z2 - th, x2, z2, y0: baseY, h: wallH, P }))
  regs.push(...curtainWall({ tag: 'rW', x1, z1, x2: x1 + th, z2, y0: baseY, h: wallH, P }))
  regs.push(...curtainWall({ tag: 'rE', x1: x2 - th, z1, x2, z2, y0: baseY, h: wallH, P }))
  regs.push(...courtyard({ tag: 'parade', x: x1 + th, z: z1 + th, w: W - 2 * th, l: L - 2 * th, y: baseY - 1, P }))
  for (const [tx, tz] of [[x1, z1], [x1, z2], [x2, z1], [x2, z2]]) regs.push(...squareTower({ tag: `c-${tx}-${tz}`, cx: tx, cz: tz, r: 2, y0: baseY, h: wallH + 4, P, roof: 'battlement', stories: 1, storyH: wallH + 4 }))
  regs.push(...gatehouse({ tag: 'gN', cx, cz: z1 + 1, axis: 'x', y0: baseY, h: wallH + 3, gateW: 4, P }))
  regs.push(...gatehouse({ tag: 'gS', cx, cz: z2 - 1, axis: 'x', y0: baseY, h: wallH + 3, gateW: 4, P }))
  regs.push(...arrowSlits({ tag: 'rampart', x1, z1, x2, z2, y: baseY + Math.floor(wallH / 2), P, every: 7 }))

  // --- central via + forum ---
  regs.push(...via({ tag: 'via', cx, cz, x1: th, x2: W - th, z1: th, z2: L - th, y: baseY - 1, hw: viaHW, block: P.trim }))
  regs.push(...forum({ tag: 'forum', cx, cz, y: baseY - 1, r: 2, P }))

  // interior usable rect + x-lanes flanking the via
  const inx1 = th + 1, inx2 = W - th - 1, inz1 = th + 1, inz2 = L - th - 1
  const leftX = inx1, leftW = (cx - viaHW - 1) - inx1
  const rightX = cx + viaHW + 1, rightW = inx2 - rightX

  // A furnished interior building fronting the via: shell + roof + door + windows.
  const bld = (tag, bx, bz, bw, bl, type) => {
    if (bw < 4 || bl < 4) return false
    regs.push(...roomBox({ tag, x: bx, z: bz, w: bw, l: bl, baseY, storyH, stories: 1, P }))
    if (pick(R, ['gable', 'gable', 'flat']) === 'flat') regs.push(flatBattlementRoof({ tag: `${tag}-roof`, x: bx, z: bz, w: bw, l: bl, y: baseY + storyH, P })[0])
    else regs.push(...gableRoof({ tag: `${tag}-roof`, x: bx, z: bz, w: bw, l: bl, y: baseY + storyH + 1, P }))
    const bcz = bz + Math.floor(bl / 2), nearSouth = bcz <= cz
    regs.push(...entrance({ tag: `${tag}-door`, x: bx + Math.floor(bw / 2), y: baseY, z: nearSouth ? bz + bl : bz, facing: nearSouth ? 'north' : 'south', P }))
    regs.push(...windowBand({ tag: `${tag}-win`, x: bx, z: bz, w: bw, l: bl, y: baseY + 2, P, spacing: 3, height: 1 }))
    regs.push(furnish(tag, type, bx, bz, bw, bl, baseY, baseY + storyH, P))
    return true
  }

  // z-bands: front (barracks) | forum (armory/granary flank) | rear (principia + extras)
  const frontZ = inz1, frontDepth = (cz - 4) - frontZ
  const midZ = cz - 3, midDepth = 6
  const rearZ = cz + 4, rearDepth = inz2 - rearZ

  // FRONT: barracks each side of the via (bunk rows)
  bld('barrackW', leftX, frontZ, Math.min(9, leftW), frontDepth, 'barracks')
  bld('barrackE', rightX, frontZ, Math.min(9, rightW), frontDepth, 'barracks')
  // FORUM band: armory (W) + granary (E) flank the well
  bld('armory', leftX, midZ, Math.min(7, leftW), midDepth, 'armory')
  bld('granary', inx2 - Math.min(7, rightW), midZ, Math.min(7, rightW), midDepth, 'granary')
  // REAR: principia (HQ) centered, spanning the via + seeded corner buildings
  const pW = Math.min(12, inx2 - inx1)
  bld('principia', cx - Math.floor(pW / 2), rearZ, pW, rearDepth, 'great_hall')
  const rearLeftW = (cx - Math.floor(pW / 2) - 1) - leftX
  const rearRightW = inx2 - (cx + Math.ceil(pW / 2) + 1)
  if (R() < 0.7) bld('mess', leftX, rearZ, Math.min(7, rearLeftW), rearDepth, pick(R, ['mess', 'workshop', 'forge']))
  if (R() < 0.7) bld('store', inx2 - Math.min(7, rearRightW), rearZ, Math.min(7, rearRightW), rearDepth, pick(R, ['storeroom', 'stable']))
  return envelope(opts, P, regs)
}

// ---- dispatch ----
const ARCHETYPES = {
  cottage, house, manor: house, keep, castle, tower, wizard_tower: tower,
  cathedral, hall: cathedral, church: cathedral, fort
}

function compose(design) {
  const fn = ARCHETYPES[String(design.archetype || '').toLowerCase()] || house
  // Seed a mutable room-program queue the composers pop interior functions from.
  design._roomQueue = Array.isArray(design.roomList) ? design.roomList.slice() : []
  return fn(design)
}

module.exports = { compose, ARCHETYPES, cottage, house, keep, castle, tower, cathedral, fort }
