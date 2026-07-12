// grok/lib/build/template.js — deterministic Layer-A blueprint templates.
//
// Produces a furnished, detailed, terrain-fittable blueprint using STRUCTURAL
// components for the shell (hollow boxes → interiors stay UNSET = additive, no
// blanket air) and SKILL components (pillarGrid, trimCourse, windowGrid,
// roofGable, crenellate, furnishRoom, lightingCadence, torchCadence) for depth,
// openings, roof, interior furniture and lighting — reusing all of Grok's
// building intelligence. Coordinates are LOCAL (anchor-relative), bounds.min=0.
//
// The Architect chooses the DESIGN PARAMETERS (style, footprint, stories, room
// types, tower, battlements); the geometry here is deterministic and correct.

const { resolvePalette } = require('../../palettes')

const OPP = { north: 'south', south: 'north', east: 'west', west: 'east' }

function templateBlueprint(opts = {}) {
  const P = resolvePalette(opts.palette || opts.style || 'medieval')
  const W = clampi(opts.width, 6, 128, 12)    // x span (large builds honored)
  const L = clampi(opts.length, 6, 128, 12)   // z span
  const wallH = clampi(opts.wallH || opts.height, 4, 48, 6)
  const roomType = normalizeRoom(opts.roomType || opts.room)
  const battlements = opts.battlements !== false && /castle|keep|fort|medieval/.test(String(opts.style || opts.palette || 'medieval'))
  const roof = opts.roof || (battlements ? 'flat' : 'gable')
  const roofH = Math.max(3, Math.round(Math.min(W, L) / 2))
  const name = opts.name || 'Keep'

  const cx = Math.round(W / 2), cz = Math.round(L / 2)
  const top = wallH               // wall-top line (local y)
  const regions = []

  // 1) foundation (solid) + plinth
  regions.push({ id: 'foundation', name: 'Foundation', phase: 1, components: [
    { kind: 'box', role: 'foundation', a: { x: 0, y: 0, z: 0 }, b: { x: W, y: 0, z: L }, hollow: false }
  ] })
  // 2) interior floor
  regions.push({ id: 'floor', name: 'Floor', phase: 2, components: [
    { kind: 'box', role: 'floor', a: { x: 1, y: 1, z: 1 }, b: { x: W - 1, y: 1, z: L - 1 }, hollow: false }
  ] })
  // 3) shell (HOLLOW → interior preserved/unset) + corner pillars + wall depth
  regions.push({ id: 'shell', name: 'Shell', phase: 3, components: [
    { kind: 'box', role: 'wall', a: { x: 0, y: 1, z: 0 }, b: { x: W, y: top, z: L }, hollow: true },
    { kind: 'skill', skill: 'cornerPillars', args: { x1: 0, z1: 0, x2: W, z2: L, y0: 1, y1: top, block: P.pillar } },
    { kind: 'skill', skill: 'pillarGrid', args: { x1: 0, z1: 0, x2: W, z2: L, y0: 1, y1: top, every: 4, block: P.trim } }
  ] })

  // 4) optional tower
  if (opts.tower) {
    const side = String(opts.tower.side || opts.tower || 'west').toLowerCase()
    regions.push(towerRegionLocal(side, W, L, wallH, P))
  }

  // 5) roof
  if (roof === 'gable') {
    regions.push({ id: 'roof', name: 'Roof', phase: 5, components: [
      { kind: 'skill', skill: 'roofGable', args: { x1: 0, z1: 0, x2: W, z2: L, y: top + 1, block: P.roof } },
      { kind: 'skill', skill: 'roofOverhang', args: { x1: 0, z1: 0, x2: W, z2: L, y: top, block: P.roof } }
    ] })
  } else { // flat roof (battlement platform)
    regions.push({ id: 'roof', name: 'Roof', phase: 5, components: [
      { kind: 'box', role: 'floor', a: { x: 0, y: top, z: 0 }, b: { x: W, y: top, z: L }, hollow: false }
    ] })
  }

  // 6) openings — door (carve + door) on the -z (north) wall, framed windows
  const doorX = cx
  regions.push({ id: 'openings', name: 'Openings', phase: 6, components: [
    { kind: 'carve', a: { x: doorX, y: 1, z: 0 }, b: { x: doorX, y: 2, z: 0 } },
    { kind: 'point', block: `${wood(P)}_door[facing=south,half=lower]`, at: { x: doorX, y: 1, z: 0 } },
    { kind: 'point', block: `${wood(P)}_door[facing=south,half=upper]`, at: { x: doorX, y: 2, z: 0 } },
    { kind: 'skill', skill: 'windowGrid', args: { x1: 0, z1: 0, x2: W, z2: L, y0: 2, spacing: 3, height: 2, block: P.glass } }
  ] })

  // 7) interior furniture + lighting (skills write furniture into the grid)
  const iy = 2, iyTop = top - 1
  regions.push({ id: 'interior', name: 'Interior', phase: 8, components: [
    { kind: 'skill', skill: 'furnishRoom', args: { type: roomType, x1: 1, y1: iy, z1: 1, x2: W - 1, y2: iyTop, z2: L - 1, palette: P } },
    { kind: 'skill', skill: 'lightingCadence', args: { x1: 1, z1: 1, x2: W - 1, z2: L - 1, y0: iy, yTop: iyTop, every: 5, block: P.light } }
  ] })

  // 8) exterior detailing + lighting
  const detail = [
    { kind: 'skill', skill: 'trimCourse', args: { x1: 0, z1: 0, x2: W, z2: L, y: 1, block: P.trim } },
    { kind: 'skill', skill: 'trimCourse', args: { x1: 0, z1: 0, x2: W, z2: L, y: top, block: P.trim } },
    { kind: 'skill', skill: 'torchCadence', args: { x1: 0, z1: 0, x2: W, z2: L, y: 3, every: 4, block: P.light } }
  ]
  if (battlements) detail.push({ kind: 'skill', skill: 'crenellate', args: { x1: 0, z1: 0, x2: W, z2: L, y: top + 1, block: P.wall } })
  regions.push({ id: 'detailing', name: 'Detailing', phase: 9, components: detail })

  return {
    schemaVersion: 1, name, style: opts.style || 'medieval', palette: P,
    anchor: { origin: opts.origin || { x: 0, y: 64, z: 0 }, facing: opts.facing || 'north', terrainFit: opts.terrainFit || 'follow' },
    bounds: { min: { x: -1, y: 0, z: -1 }, max: { x: W + 1, y: top + roofH + 1, z: L + 1 } },
    regions
  }
}

function towerRegionLocal(side, W, L, wallH, P) {
  const r = 3, h = wallH + 5
  const midX = Math.round(W / 2), midZ = Math.round(L / 2)
  let cx, cz
  if (side === 'west') { cx = 0; cz = midZ }
  else if (side === 'east') { cx = W; cz = midZ }
  else if (side === 'north') { cx = midX; cz = 0 }
  else if (side === 'south') { cx = midX; cz = L }
  else { cx = 0; cz = 0 }
  return { id: `${side}_tower`, name: `${cap(side)} Tower`, phase: 4, components: [
    { kind: 'cylinder', role: 'wall', hollow: true, a: { x: cx - r, y: 1, z: cz - r }, b: { x: cx + r, y: h, z: cz + r } },
    { kind: 'skill', skill: 'roofCone', args: { x: cx, z: cz, y: h + 1, radius: r + 1, height: r + 2, block: P.roof } },
    { kind: 'point', block: P.light, at: { x: cx, y: 3, z: cz } }
  ] }
}

// Pick a room type from a keyword; default a great hall for a keep.
function normalizeRoom(s) {
  const k = String(s || '').toLowerCase()
  if (/bed/.test(k)) return 'bedroom'
  if (/kitchen/.test(k)) return 'kitchen'
  if (/librar|study/.test(k)) return 'library'
  if (/live|living|lounge/.test(k)) return 'living'
  return 'great_hall'
}

function wood(P) {
  const src = String(P.floor || P.pillar || 'oak')
  const m = src.match(/(oak|spruce|birch|jungle|acacia|dark_oak|mangrove|cherry|bamboo|crimson|warped)/)
  return m ? m[1] : 'oak'
}
function clampi(v, lo, hi, def) { const n = parseInt(v, 10); return isNaN(n) ? def : Math.min(Math.max(n, lo), hi) }
function cap(s) { return String(s).charAt(0).toUpperCase() + String(s).slice(1) }

module.exports = { templateBlueprint }
