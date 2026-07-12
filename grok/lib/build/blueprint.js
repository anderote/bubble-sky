// grok/lib/build/blueprint.js — Layer-A SEMANTIC BLUEPRINT.
//
// What agents reason and plan in: named regions of typed components. Coordinates
// are LOCAL (build-space, relative to the anchor); world placement is resolved at
// compile time (world = anchor.origin + localCoord). See the design doc.
//
//   region     { id, name, phase, components:[...] }
//   component  structural: { kind:box|wall|cylinder|dome|prism|line|slab,
//                            role|block, a:{x,y,z}, b:{x,y,z}, hollow? }
//              functional: { kind:point, block:"name[state]", at:{x,y,z} }
//              skill:      { kind:skill, skill, args:{...local coords...} }
//              carve:      { kind:carve, a, b } | { kind:point, block:"air", at }
//
// This module owns the schema, validation/normalisation, the incremental edit
// ops, and the converter that turns Grok's proven phased plan into a blueprint.

const { resolvePalette } = require('../../palettes')
const { B, slug } = require('./util')

const STRUCTURAL = new Set(['box', 'wall', 'cylinder', 'dome', 'prism', 'line', 'slab'])
const ROLE_ORDER = ['foundation', 'wall', 'trim', 'accent', 'pillar', 'floor', 'roof', 'glass', 'light', 'detail']

// Ordered phase names Grok/Codex share; index = build order.
const PHASE_NAMES = [
  'clear', 'foundation', 'floor', 'base', 'supports', 'walls', 'wall',
  'platform', 'stairs', 'openings', 'windows', 'roof', 'railing',
  'lighting', 'detail'
]

function v3(p, def = { x: 0, y: 0, z: 0 }) {
  if (!p) return { ...def }
  return { x: Math.round(+p.x || 0), y: Math.round(+p.y || 0), z: Math.round(+p.z || 0) }
}

// Map a component to the shared string phase name (Codex-compatible), used to
// tag jobs. Region.phase (a number) drives ordering; this labels the work.
function phaseNameFor(comp, region) {
  const role = String(comp.role || '').toLowerCase()
  const block = String(comp.block || comp.role || '').toLowerCase()
  if (comp.kind === 'carve') return 'openings'
  if (role === 'foundation' || /foundation/.test(region.id || '')) return 'foundation'
  if (role === 'floor') return 'floor'
  if (role === 'roof' || comp.kind === 'dome' || comp.kind === 'prism') return 'roof'
  if (role === 'pillar') return 'supports'
  if (comp.kind === 'point') {
    if (/door|gate|trapdoor/.test(block)) return 'openings'
    if (/glass|pane|window/.test(block)) return 'windows'
    if (/lantern|torch|light|lamp|end_rod|sea_lantern|chandelier/.test(block)) return 'lighting'
    return 'detail'
  }
  if (comp.kind === 'skill') {
    if (/light|torch|lantern|chandelier/i.test(comp.skill || '')) return 'lighting'
    if (/window/i.test(comp.skill || '')) return 'windows'
    return 'detail'
  }
  if (role === 'wall' || comp.kind === 'wall') return 'walls'
  if (role === 'trim' || role === 'accent') return 'detail'
  return 'walls'
}

// Build a normalised blueprint. Fills palette (full role set), coerces coords,
// assigns region ids/phases. Non-destructive: returns a fresh object.
function normalize(raw, fallbackOrigin) {
  const bp = {
    schemaVersion: 1,
    name: raw.name || raw.project || 'Build',
    style: raw.style || (typeof raw.palette === 'string' ? raw.palette : (raw.palette && raw.palette.style)) || 'medieval',
    palette: resolvePalette(raw.palette || raw.style),
    anchor: {
      origin: v3(raw.anchor && raw.anchor.origin ? raw.anchor.origin : raw.origin, fallbackOrigin || { x: 0, y: 64, z: 0 }),
      facing: (raw.anchor && raw.anchor.facing) || raw.facing || 'north',
      terrainFit: (raw.anchor && raw.anchor.terrainFit) || raw.terrainFit || 'follow'
    },
    bounds: {
      min: v3(raw.bounds && raw.bounds.min, { x: 0, y: 0, z: 0 }),
      max: v3(raw.bounds && raw.bounds.max, { x: 0, y: 0, z: 0 })
    },
    regions: []
  }
  const regions = Array.isArray(raw.regions) ? raw.regions : []
  regions.forEach((r, ri) => {
    if (!r || !Array.isArray(r.components)) return
    const region = {
      id: r.id || slug(r.name || `region-${ri + 1}`),
      name: r.name || r.id || `Region ${ri + 1}`,
      phase: Number.isFinite(+r.phase) ? +r.phase : ri + 1,
      components: []
    }
    for (const c of r.components) {
      const comp = normalizeComponent(c, bp.palette)
      if (comp) region.components.push(comp)
    }
    if (region.components.length) bp.regions.push(region)
  })
  // Auto-fit bounds to whatever the components actually span (union with any
  // provided bounds) so compile never clips a legitimate cell.
  fitBounds(bp)
  return bp
}

function normalizeComponent(c, palette) {
  if (!c || !c.kind) return null
  const kind = String(c.kind).toLowerCase()
  if (kind === 'point') {
    const at = v3(c.at || c)
    const block = c.block || (c.role ? palette[c.role] : null) || 'stone'
    return { kind: 'point', block: String(block), at, role: c.role || null }
  }
  if (kind === 'skill') {
    return { kind: 'skill', skill: String(c.skill || ''), args: Object.assign({}, c.args || {}) }
  }
  if (kind === 'carve') {
    return { kind: 'carve', a: v3(c.a), b: v3(c.b || c.a) }
  }
  if (STRUCTURAL.has(kind)) {
    const role = c.role || null
    const block = c.block || (role ? palette[role] : null) || palette.wall || 'stone_bricks'
    return {
      kind, role, block: String(block),
      a: v3(c.a), b: v3(c.b || c.a),
      hollow: !!c.hollow
    }
  }
  return null
}

// Expand bounds to enclose every component (structural spans + points + carve).
function fitBounds(bp) {
  let mnx = bp.bounds.min.x, mny = bp.bounds.min.y, mnz = bp.bounds.min.z
  let mxx = bp.bounds.max.x, mxy = bp.bounds.max.y, mxz = bp.bounds.max.z
  const eat = (p) => {
    if (!p) return
    mnx = Math.min(mnx, p.x); mny = Math.min(mny, p.y); mnz = Math.min(mnz, p.z)
    mxx = Math.max(mxx, p.x); mxy = Math.max(mxy, p.y); mxz = Math.max(mxz, p.z)
  }
  for (const r of bp.regions) for (const c of r.components) {
    if (c.a) { eat(c.a); eat(c.b) }
    if (c.at) eat(c.at)
    if (c.kind === 'skill') {
      const a = c.args || {}
      for (const [kx, ky, kz] of [['x1', 'y1', 'z1'], ['x2', 'y2', 'z2'], ['x', 'y', 'z']]) {
        if (a[kx] != null && a[kz] != null) eat({ x: +a[kx], y: +(a[ky] != null ? a[ky] : a.y || 0), z: +a[kz] })
      }
    }
  }
  bp.bounds = { min: { x: mnx, y: mny, z: mnz }, max: { x: mxx, y: mxy, z: mxz } }
}

function validate(bp) {
  const errors = []
  if (!bp || bp.schemaVersion !== 1) errors.push('schemaVersion must be 1')
  if (!bp.regions || !bp.regions.length) errors.push('no regions')
  const ids = new Set()
  for (const r of bp.regions || []) {
    if (ids.has(r.id)) errors.push(`duplicate region id ${r.id}`)
    ids.add(r.id)
    if (!r.components.length) errors.push(`region ${r.id} empty`)
    for (const c of r.components) {
      if (c.kind === 'point' && !c.at) errors.push(`point in ${r.id} has no at`)
      if (STRUCTURAL.has(c.kind) && (!c.a || !c.b)) errors.push(`${c.kind} in ${r.id} needs a,b`)
    }
  }
  const sx = bp.bounds.max.x - bp.bounds.min.x + 1
  const sy = bp.bounds.max.y - bp.bounds.min.y + 1
  const sz = bp.bounds.max.z - bp.bounds.min.z + 1
  if (sx <= 0 || sy <= 0 || sz <= 0) errors.push('degenerate bounds')
  return { ok: errors.length === 0, errors }
}

// ---- incremental EDIT OPS (recompile → additive diff → small job set) ----

function addRegion(bp, region) {
  const norm = normalizeComponent
  const r = {
    id: region.id || slug(region.name || `region-${bp.regions.length + 1}`),
    name: region.name || region.id,
    phase: Number.isFinite(+region.phase) ? +region.phase : bp.regions.length + 1,
    components: (region.components || []).map(c => norm(c, bp.palette)).filter(Boolean)
  }
  bp.regions = bp.regions.filter(x => x.id !== r.id).concat([r])
  fitBounds(bp)
  return r
}

function addComponent(bp, regionId, comp) {
  const r = bp.regions.find(x => x.id === regionId)
  if (!r) throw new Error(`no region ${regionId}`)
  const c = normalizeComponent(comp, bp.palette)
  if (c) r.components.push(c)
  fitBounds(bp)
  return c
}

function removeRegion(bp, regionId) {
  const before = bp.regions.length
  bp.regions = bp.regions.filter(x => x.id !== regionId)
  return before !== bp.regions.length
}

// Declarative transforms. Each returns the id(s) of regions it touched so the
// caller can report / diff just those.
function transform(bp, op) {
  const type = String(op.type || op.op || '').toLowerCase()
  if (type === 'raise_roof' || type === 'raise' || type === 'taller') {
    const dy = Math.round(+op.dy || +op.amount || 3)
    const touched = []
    for (const r of bp.regions) {
      const isRoof = /roof/i.test(r.id) || /roof/i.test(r.name) || r.components.some(c => c.role === 'roof' || c.kind === 'dome' || c.kind === 'prism')
      const isWall = /wall|shell|tower|keep/i.test(r.id) || r.components.some(c => c.role === 'wall' || c.kind === 'wall')
      if (isRoof) {
        for (const c of r.components) { if (c.a) { c.a.y += dy; c.b.y += dy } if (c.at) c.at.y += dy }
        touched.push(r.id)
      } else if (isWall) {
        // extend walls upward so the roof still meets them
        for (const c of r.components) if (c.a && (c.role === 'wall' || c.kind === 'wall')) c.b.y += dy
        touched.push(r.id)
      }
    }
    fitBounds(bp)
    return touched
  }
  if (type === 'add_tower' || type === 'tower') {
    const side = String(op.side || op.direction || 'west').toLowerCase()
    const r = towerRegion(bp, side, op)
    addRegion(bp, r)
    return [r.id]
  }
  if (type === 'add_region') { const r = addRegion(bp, op.region || op); return [r.id] }
  if (type === 'remove_region') { removeRegion(bp, op.regionId || op.id); return [op.regionId || op.id] }
  throw new Error(`unknown transform ${type}`)
}

// Compute a corner tower on the given side from the current bounds.
function towerRegion(bp, side, op) {
  const { min, max } = bp.bounds
  const h = Math.round(+op.height || (max.y - min.y) + 6)
  const r = Math.round(+op.radius || 3)
  const cyMin = min.y
  let cx, cz
  const midX = Math.round((min.x + max.x) / 2), midZ = Math.round((min.z + max.z) / 2)
  if (side === 'west') { cx = min.x; cz = midZ }
  else if (side === 'east') { cx = max.x; cz = midZ }
  else if (side === 'north') { cx = midX; cz = min.z }
  else if (side === 'south') { cx = midX; cz = max.z }
  else { cx = min.x; cz = min.z } // default nw corner
  return {
    id: `${side}_tower`, name: `${side[0].toUpperCase()}${side.slice(1)} Tower`, phase: 4,
    components: [
      { kind: 'cylinder', role: 'wall', hollow: true,
        a: { x: cx - r, y: cyMin, z: cz - r }, b: { x: cx + r, y: cyMin + h, z: cz + r } },
      { kind: 'point', role: 'light', block: bp.palette.light || 'lantern', at: { x: cx, y: cyMin + 2, z: cz } }
    ]
  }
}

// ---- converter: Grok's proven PHASED plan -> a Layer-A blueprint ----
//
// Each phased step becomes a SKILL component (reusing all the structures/detail/
// furniture generators), with absolute step coordinates rebased to LOCAL space
// (relative to the plan origin). Structure-generator steps are kept as skills;
// they place their own material and never blanket-clear at compile time because
// the skill adapter drops air writes (see compile.js) — only carve/point-air can
// delete. Primitive fill_box/set_block become box / point components so a
// deliberate "air" fill is honoured as a carve.
function fromPhasedPlan(plan) {
  const origin = v3(plan.origin)
  const palette = resolvePalette(plan.palette)
  const local = (x, y, z) => ({ x: Math.round(x - origin.x), y: Math.round(y - origin.y), z: Math.round(z - origin.z) })
  const regions = []
  const phases = Array.isArray(plan.phases) ? plan.phases : []
  phases.forEach((ph, pi) => {
    const region = { id: slug(ph.phase || `phase-${pi + 1}`) + `-${pi + 1}`, name: ph.phase || `Phase ${pi + 1}`, phase: pi + 1, components: [] }
    for (const step of (ph.steps || [])) {
      const comp = stepToComponent(step, origin, local, palette)
      if (comp) region.components.push(comp)
    }
    if (region.components.length) regions.push(region)
  })
  return normalize({
    name: plan.project || plan.name, style: palette.wall, palette,
    anchor: { origin, facing: plan.facing || 'north', terrainFit: plan.terrainFit || 'follow' },
    regions
  }, origin)
}

// Rebase one arg object's coordinate fields to local space (in place on a copy).
function rebaseArgs(args, origin) {
  const a = Object.assign({}, args)
  const pairs = [['x', 'y', 'z'], ['x1', 'y1', 'z1'], ['x2', 'y2', 'z2']]
  for (const [kx, ky, kz] of pairs) {
    if (a[kx] != null) a[kx] = Math.round(+a[kx] - origin.x)
    if (a[ky] != null) a[ky] = Math.round(+a[ky] - origin.y)
    if (a[kz] != null) a[kz] = Math.round(+a[kz] - origin.z)
  }
  return a
}

function stepToComponent(step, origin, local, palette) {
  const skill = String(step.skill || '').trim()
  const a = step.args || {}
  if (!skill) return null
  // Raw primitives → semantic components (so air is an explicit carve).
  if (skill === 'fill_box' && a.x1 != null) {
    const A = local(+a.x1, +a.y1, +a.z1), Bc = local(+a.x2, +a.y2, +a.z2)
    if (/^air$/i.test(String(a.block || '').trim())) return { kind: 'carve', a: A, b: Bc }
    return { kind: 'box', block: a.block || palette.wall, a: A, b: Bc }
  }
  if (skill === 'set_block' && a.x != null) {
    const at = local(+a.x, +a.y, +a.z)
    if (/^air$/i.test(String(a.block || '').trim())) return { kind: 'carve', a: at, b: at }
    return { kind: 'point', block: a.block || 'stone', at }
  }
  if ((skill === 'foundation') && (a.x1 != null || a.x != null)) {
    // flat plate one below origin -> a slab box in local space
    if (a.x1 != null) { const A = local(+a.x1, origin.y - 1, +a.z1), Bc = local(+a.x2, origin.y - 1, +a.z2); return { kind: 'box', role: 'foundation', block: a.block || palette.foundation, a: A, b: Bc } }
  }
  // Everything else is a skill component with local-rebased args.
  return { kind: 'skill', skill, args: rebaseArgs(a, origin) }
}

module.exports = {
  STRUCTURAL, PHASE_NAMES, phaseNameFor, normalize, normalizeComponent, validate,
  addRegion, addComponent, removeRegion, transform, towerRegion, fromPhasedPlan, v3
}
