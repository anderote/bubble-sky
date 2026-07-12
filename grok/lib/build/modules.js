// grok/lib/build/modules.js — reusable BUILDING-BLOCK generators.
//
// Each module returns an array of Layer-A REGIONS ({id,name,phase,components})
// at the given LOCAL positions (anchor-relative, y=0 = ground). They are the
// vocabulary the archetype composer (archetypes.js) assembles into whole,
// varied buildings. Everything is ADDITIVE + correct by construction:
//   - hollow shells (box/cylinder hollow) leave interiors UNSET (preserve world);
//   - only `carve` produces air (doorways, tunnels, stair holes);
//   - structural components carry a ROLE so they inherit the blueprint palette;
//   - detailing/furniture reuse Grok's skill library via `skill` components.
//
// Coordinate convention (shared with template.js):
//   y=0   solid foundation course
//   y=1   floor / wall base line (interior usable from y=2)
//   walls rise y=1..topY; roofs sit at topY+1; a story is `storyH` tall.

const { resolvePalette } = require('../../palettes')

const DIRV = { north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0] }
const OPP = { north: 'south', south: 'north', east: 'west', west: 'east' }

// ---- tiny component/region builders (keep archetypes terse) ----
function region(id, name, phase, components) { return { id, name, phase, components } }
function box(role, a, b, hollow) { return { kind: 'box', role, a, b, hollow: !!hollow } }
function bl(block, a, b, hollow) { return { kind: 'box', block, a, b, hollow: !!hollow } }
function wallSeg(block, a, b) { return { kind: 'wall', block, a, b } }
function cyl(role, a, b, hollow) { return { kind: 'cylinder', role, a, b, hollow: !!hollow } }
function pt(block, at) { return { kind: 'point', block, at } }
function carve(a, b) { return { kind: 'carve', a, b } }
function sk(skill, args) { return { kind: 'skill', skill, args } }

function clampi(v, lo, hi, def) { const n = parseInt(v, 10); return isNaN(n) ? def : Math.min(Math.max(n, lo), hi) }

// Derive a wood family from a palette (for doors/stairs/fence furniture).
function woodOf(P) {
  const src = String((P && (P.floor || P.pillar)) || 'oak')
  const m = src.match(/(oak|spruce|birch|jungle|acacia|dark_oak|mangrove|cherry|bamboo|crimson|warped)/)
  return m ? m[1] : 'oak'
}

// A 2-tall door opening (carve) + the two door halves.
function doorComps(x, y, z, facing, wood) {
  return [
    carve({ x, y, z }, { x, y: y + 1, z }),
    pt(`${wood}_door[facing=${facing},half=lower]`, { x, y, z }),
    pt(`${wood}_door[facing=${facing},half=upper]`, { x, y: y + 1, z })
  ]
}

// ================= ROOMS / STRUCTURE =================

// A hollow room shell + floor(s). The core habitable box; interiors stay UNSET.
//   {tag,x,z,w,l,baseY=1,storyH=5,stories=1,P,foundation=true,pillars=true}
function roomBox(o) {
  const { tag, x, z, w, l, P } = o
  const baseY = o.baseY == null ? 1 : o.baseY
  const storyH = o.storyH || 5
  const stories = o.stories || 1
  const topY = baseY + stories * storyH
  const regs = []
  if (o.foundation !== false) {
    regs.push(region(`${tag}-found`, `${tag} Foundation`, 1, [
      box('foundation', { x, y: baseY - 1, z }, { x: x + w, y: baseY - 1, z: z + l }, false)
    ]))
  }
  // ground floor + any inter-story floor slabs
  const fl = [box('floor', { x: x + 1, y: baseY, z: z + 1 }, { x: x + w - 1, y: baseY, z: z + l - 1 }, false)]
  for (let s = 1; s < stories; s++) {
    const fy = baseY + s * storyH
    fl.push(box('floor', { x: x + 1, y: fy, z: z + 1 }, { x: x + w - 1, y: fy, z: z + l - 1 }, false))
  }
  regs.push(region(`${tag}-floor`, `${tag} Floors`, 2, fl))
  // shell (hollow) + structural rhythm
  const shell = [box(o.wallRole || 'wall', { x, y: baseY, z }, { x: x + w, y: topY, z: z + l }, true)]
  if (o.pillars !== false) {
    shell.push(sk('cornerPillars', { x1: x, z1: z, x2: x + w, z2: z + l, y0: baseY, y1: topY, block: P.pillar }))
    shell.push(sk('pillarGrid', { x1: x, z1: z, x2: x + w, z2: z + l, y0: baseY, y1: topY, every: o.pillarEvery || 4, block: P.trim }))
  }
  regs.push(region(`${tag}-shell`, `${tag} Shell`, 3, shell))
  regs.push(region(`${tag}-trim`, `${tag} Trim`, 9, [
    sk('trimCourse', { x1: x, z1: z, x2: x + w, z2: z + l, y: baseY, block: P.trim }),
    sk('trimCourse', { x1: x, z1: z, x2: x + w, z2: z + l, y: topY, block: P.trim })
  ]))
  return regs
}

// A solid floor/ceiling slab over an interior footprint (multi-level plate).
function floorSlab(o) {
  const { tag, x, z, w, l, y, P } = o
  return [region(tag, `${tag} Slab`, 2, [box('floor', { x: x + 1, y, z: z + 1 }, { x: x + w - 1, y, z: z + l - 1 }, false)])]
}
const story = floorSlab

// A straight interior partition wall (solid, thickness 1).
function interiorWall(o) {
  const { tag, x1, z1, x2, z2, y0, y1, P } = o
  return [region(tag, `${tag} Partition`, 4, [wallSeg(P.wall, { x: x1, y: y0, z: z1 }, { x: x2, y: y1, z: z2 })])]
}

// An interior/exterior doorway (carve + door). facing = the door's facing.
function doorway(o) {
  const { tag, x, y, z, facing, P } = o
  return [region(tag, `${tag} Door`, 6, doorComps(x, y, z, facing, o.wood || woodOf(P)))]
}
function doorRegion(tag, x, y, z, facing, P) { return region(tag, `${tag} Door`, 6, doorComps(x, y, z, facing, woodOf(P))) }

// A straight staircase connecting one floor to the next + a carved hole above.
//   {tag,x,y,z,facing='east',storyH,P}
function staircase(o) {
  const { tag, P } = o
  const storyH = o.storyH || 5
  const facing = o.facing || 'east'
  const wood = woodOf(P)
  const [dx, dz] = DIRV[facing]
  const comps = []
  for (let i = 0; i < storyH; i++) comps.push(pt(`${wood}_stairs[facing=${facing},half=bottom]`, { x: o.x + dx * i, y: o.y + i, z: o.z + dz * i }))
  const hx = o.x + dx * (storyH - 1), hz = o.z + dz * (storyH - 1)
  comps.push(carve({ x: Math.min(o.x, hx), y: o.y + storyH, z: Math.min(o.z, hz) }, { x: Math.max(o.x, hx) + Math.abs(dz), y: o.y + storyH, z: Math.max(o.z, hz) + Math.abs(dx) }))
  return [region(`${tag}-stairs`, `${tag} Stairs`, 7, comps)]
}

// ================= TOWERS =================

// Cardinal window slits, 2 tall, per story (works for round + square walls).
function towerWindows(cx, cz, r, y0, storyH, stories, P, glass) {
  const comps = []
  const g = glass || P.glass
  for (let s = 0; s < stories; s++) {
    const wy = y0 + s * storyH + Math.floor(storyH / 2)
    for (const [dx, dz] of [[0, -r], [0, r], [-r, 0], [r, 0]]) {
      comps.push(pt(g, { x: cx + dx, y: wy, z: cz + dz }))
      comps.push(pt(g, { x: cx + dx, y: wy + 1, z: cz + dz }))
    }
  }
  return comps
}

function roofFor(tag, roof, x1, z1, x2, z2, topY, cx, cz, r, P) {
  if (roof === 'cone') return region(`${tag}-roof`, `${tag} Roof`, 5, [sk('roofCone', { x: cx, z: cz, y: topY + 1, radius: r + 1, height: r + 3, block: P.roof })])
  if (roof === 'spire') return region(`${tag}-roof`, `${tag} Spire`, 5, [sk('roofCone', { x: cx, z: cz, y: topY + 1, radius: r + 1, height: r * 3, block: P.roof }), pt('end_rod', { x: cx, y: topY + 1 + r * 3 + 1, z: cz })])
  if (roof === 'gable') return region(`${tag}-roof`, `${tag} Roof`, 5, [sk('roofGable', { x1, z1, x2, z2, y: topY + 1, block: P.roof }), sk('roofOverhang', { x1, z1, x2, z2, y: topY, block: P.roof })])
  return region(`${tag}-roof`, `${tag} Battlements`, 5, [box('floor', { x: x1, y: topY, z: z1 }, { x: x2, y: topY, z: z2 }, false), sk('crenellate', { x1, z1, x2, z2, y: topY + 1, block: P.wall })])
}

// Square tower centred at (cx,cz), half-size r, from y0 up h blocks.
//   {tag,cx,cz,r,y0,h,P,roof='battlement',stories,storyH=5,glass}
function squareTower(o) {
  const { tag, cx, cz, r, y0, P } = o
  const h = o.h, topY = y0 + h, storyH = o.storyH || 5
  const st = o.stories || Math.max(1, Math.round(h / storyH))
  const x1 = cx - r, x2 = cx + r, z1 = cz - r, z2 = cz + r
  const regs = []
  regs.push(region(`${tag}-found`, `${tag} Base`, 1, [box('foundation', { x: x1, y: y0 - 1, z: z1 }, { x: x2, y: y0 - 1, z: z2 }, false)]))
  regs.push(region(`${tag}-shell`, `${tag} Shell`, 3, [
    box('wall', { x: x1, y: y0, z: z1 }, { x: x2, y: topY, z: z2 }, true),
    sk('pillarGrid', { x1, z1, x2, z2, y0, y1: topY, every: 3, block: P.trim })
  ]))
  const fl = [box('floor', { x: x1 + 1, y: y0, z: z1 + 1 }, { x: x2 - 1, y: y0, z: z2 - 1 }, false)]
  for (let s = 1; s < st; s++) { const fy = y0 + s * storyH; fl.push(box('floor', { x: x1 + 1, y: fy, z: z1 + 1 }, { x: x2 - 1, y: fy, z: z2 - 1 }, false)) }
  regs.push(region(`${tag}-floors`, `${tag} Floors`, 2, fl))
  if (r >= 1) regs.push(region(`${tag}-win`, `${tag} Windows`, 6, towerWindows(cx, cz, r, y0, storyH, st, P, o.glass)))
  regs.push(roofFor(tag, o.roof || 'battlement', x1, z1, x2, z2, topY, cx, cz, r, P))
  regs.push(region(`${tag}-light`, `${tag} Light`, 10, [pt(P.light, { x: cx, y: y0 + 2, z: cz })]))
  return regs
}

// Round tower centred at (cx,cz), radius r, from y0 up h blocks.
//   {tag,cx,cz,r,y0,h,P,cone=true,balcony=false,stories,storyH=5,glass}
function roundTower(o) {
  const { tag, cx, cz, r, y0, P } = o
  const h = o.h, topY = y0 + h, storyH = o.storyH || 5
  const st = o.stories || Math.max(1, Math.round(h / storyH))
  const regs = []
  regs.push(region(`${tag}-found`, `${tag} Base`, 1, [cyl('foundation', { x: cx - r, y: y0 - 1, z: cz - r }, { x: cx + r, y: y0 - 1, z: cz + r }, false)]))
  regs.push(region(`${tag}-shell`, `${tag} Shell`, 3, [cyl('wall', { x: cx - r, y: y0, z: cz - r }, { x: cx + r, y: topY, z: cz + r }, true)]))
  const fl = [cyl('floor', { x: cx - r, y: y0, z: cz - r }, { x: cx + r, y: y0, z: cz + r }, false)]
  for (let s = 1; s < st; s++) { const fy = y0 + s * storyH; fl.push(cyl('floor', { x: cx - r, y: fy, z: cz - r }, { x: cx + r, y: fy, z: cz + r }, false)) }
  regs.push(region(`${tag}-floors`, `${tag} Floors`, 2, fl))
  regs.push(region(`${tag}-win`, `${tag} Windows`, 6, towerWindows(cx, cz, r, y0, storyH, st, P, o.glass)))
  if (o.balcony) {
    const by = y0 + Math.max(storyH, h - storyH)
    const wood = woodOf(P)
    const comps = [cyl('floor', { x: cx - r - 1, y: by, z: cz - r - 1 }, { x: cx + r + 1, y: by, z: cz + r + 1 }, false)]
    for (const [dx, dz] of [[r + 1, 0], [-r - 1, 0], [0, r + 1], [0, -r - 1], [r, r], [r, -r], [-r, r], [-r, -r]]) comps.push(pt(`${wood}_fence`, { x: cx + dx, y: by + 1, z: cz + dz }))
    regs.push(region(`${tag}-balcony`, `${tag} Balcony`, 7, comps))
  }
  if (o.cone !== false) regs.push(region(`${tag}-roof`, `${tag} Roof`, 5, [sk('roofCone', { x: cx, z: cz, y: topY + 1, radius: r + 1, height: r + 3, block: P.roof })]))
  else regs.push(region(`${tag}-roof`, `${tag} Battlements`, 5, [box('floor', { x: cx - r, y: topY, z: cz - r }, { x: cx + r, y: topY, z: cz + r }, false), sk('crenellate', { x1: cx - r, z1: cz - r, x2: cx + r, z2: cz + r, y: topY + 1, block: P.wall })]))
  regs.push(region(`${tag}-light`, `${tag} Light`, 10, [pt(P.light, { x: cx, y: y0 + 2, z: cz })]))
  return regs
}

// ================= CASTLE / FORT PARTS =================

// A straight, solid curtain wall segment with a wall-walk + crenellations.
//   {tag,x1,z1,x2,z2,y0,h,P,walk=true,crenel=true}
function curtainWall(o) {
  const { tag, x1, z1, x2, z2, y0, h, P } = o
  const comps = [box('wall', { x: x1, y: y0, z: z1 }, { x: x2, y: y0 + h, z: z2 }, false)]
  if (o.walk !== false) comps.push(box('floor', { x: x1, y: y0 + h, z: z1 }, { x: x2, y: y0 + h, z: z2 }, false))
  const regs = [region(tag, `${tag} Curtain Wall`, 3, comps)]
  if (o.crenel !== false) regs.push(region(`${tag}-cren`, `${tag} Merlons`, 9, [sk('crenellate', { x1, z1, x2, z2, y: y0 + h + 1, block: P.wall })]))
  return regs
}

// A gatehouse straddling a wall with an arched tunnel through it.
//   {tag,cx,cz,axis,y0,h,gateW=3,P}   axis = the wall's run ('x' or 'z')
function gatehouse(o) {
  const { tag, cx, cz, P } = o
  const axis = o.axis || 'x'
  const gateW = o.gateW || 3
  const h = o.h, y0 = o.y0
  const r = Math.max(3, Math.ceil(gateW / 2) + 2)
  const x1 = cx - r, x2 = cx + r, z1 = cz - r, z2 = cz + r
  const hw = (gateW - 1) >> 1
  const regs = []
  regs.push(region(`${tag}-found`, `${tag} Base`, 1, [box('foundation', { x: x1, y: y0 - 1, z: z1 }, { x: x2, y: y0 - 1, z: z2 }, false)]))
  regs.push(region(`${tag}-shell`, `${tag} Gatehouse`, 3, [box('wall', { x: x1, y: y0, z: z1 }, { x: x2, y: y0 + h, z: z2 }, true)]))
  if (axis === 'x') {
    regs.push(region(`${tag}-gate`, `${tag} Gate`, 6, [
      carve({ x: cx - hw, y: y0, z: z1 }, { x: cx + hw, y: y0 + 3, z: z2 }),
      sk('archway', { x: cx, y: y0, z: z1, width: gateW, height: 4, axis: 'z', block: P.wall })
    ]))
  } else {
    regs.push(region(`${tag}-gate`, `${tag} Gate`, 6, [
      carve({ x: x1, y: y0, z: cz - hw }, { x: x2, y: y0 + 3, z: cz + hw }),
      sk('archway', { x: x1, y: y0, z: cz, width: gateW, height: 4, axis: 'x', block: P.wall })
    ]))
  }
  regs.push(region(`${tag}-cren`, `${tag} Merlons`, 9, [sk('crenellate', { x1, z1, x2, z2, y: y0 + h + 1, block: P.wall })]))
  regs.push(region(`${tag}-light`, `${tag} Light`, 10, [pt(P.light, { x: cx, y: y0 + 3, z: cz })]))
  return regs
}

// An open paved area (parade ground / courtyard) — just a floor plate.
function courtyard(o) {
  const { tag, x, z, w, l, y, P } = o
  return [region(tag, `${tag} Courtyard`, 2, [box('path', { x, y, z }, { x: x + w, y, z: z + l }, false)])]
}

// ================= TRIM / ROOFS / OPENINGS =================

// An entrance: a framed doorway (+ optional arch). facing = door facing (inward).
function entrance(o) {
  const { tag, x, y, z, facing, P } = o
  const wood = woodOf(P)
  const comps = doorComps(x, y, z, facing, wood)
  return [region(tag, `${tag} Entrance`, 6, comps)]
}

// A covered porch: two posts + a slab canopy + a step, projecting `facing`.
function porch(o) {
  const { tag, x, y, z, facing, P } = o
  const w = o.w || 3
  const wood = woodOf(P)
  const [dx, dz] = DIRV[facing]
  const ox = x + dx, oz = z + dz
  const hw = (w - 1) >> 1
  const perp = dx !== 0 ? [0, 1] : [1, 0]
  const comps = []
  for (const s of [1, -1]) {
    comps.push(pt(`${wood}_fence`, { x: ox + perp[0] * hw * s, y, z: oz + perp[1] * hw * s }))
    comps.push(pt(`${wood}_fence`, { x: ox + perp[0] * hw * s, y: y + 1, z: oz + perp[1] * hw * s }))
  }
  comps.push(bl(`${wood}_slab[type=top]`, { x: ox + perp[0] * hw, y: y + 2, z: oz + perp[1] * hw }, { x: ox - perp[0] * hw, y: y + 2, z: oz - perp[1] * hw }, false))
  comps.push(pt(`${wood}_stairs[facing=${OPP[facing]},half=bottom]`, { x: ox, y: y - 1, z: oz }))
  return [region(tag, `${tag} Porch`, 9, comps)]
}

// A chimney stack rising past the roof, capped with a smoking campfire.
function chimney(o) {
  const { tag, x, y, z, top, P } = o
  const b = P.accent || P.trim || 'bricks'
  return [region(tag, `${tag} Chimney`, 9, [
    bl(b, { x, y, z }, { x, y: top, z }, false),
    pt('campfire[lit=true,facing=north]', { x, y: top + 1, z })
  ])]
}

// A conical/pointed spire with a finial (for crossings, gatehouses, roofs).
function spire(o) {
  const { tag, cx, cz, y, r, h, P } = o
  return [region(tag, `${tag} Spire`, 5, [
    sk('roofCone', { x: cx, z: cz, y, radius: r, height: h, block: P.roof }),
    pt('end_rod', { x: cx, y: y + h + 1, z: cz })
  ])]
}

// A pitched gable roof + overhang over a footprint (y = wall-top+1).
function gableRoof(o) {
  const { tag, x, z, w, l, y, P } = o
  return [region(tag, `${tag} Roof`, 5, [
    sk('roofGable', { x1: x, z1: z, x2: x + w, z2: z + l, y, block: P.roof }),
    sk('roofOverhang', { x1: x, z1: z, x2: x + w, z2: z + l, y: y - 1, block: P.roof })
  ])]
}

// A flat rooftop platform with battlement merlons (y = wall-top line).
function flatBattlementRoof(o) {
  const { tag, x, z, w, l, y, P } = o
  return [region(tag, `${tag} Battlements`, 5, [
    box('floor', { x, y, z }, { x: x + w, y, z: z + l }, false),
    sk('crenellate', { x1: x, z1: z, x2: x + w, z2: z + l, y: y + 1, block: P.wall })
  ])]
}

// A band of windows around a footprint at height y.
function windowBand(o) {
  const { tag, x, z, w, l, y, P } = o
  return [region(tag, `${tag} Windows`, 6, [
    sk('windowGrid', { x1: x, z1: z, x2: x + w, z2: z + l, y0: y, spacing: o.spacing || 3, height: o.height || 2, block: P.glass })
  ])]
}

module.exports = {
  // structure
  roomBox, floorSlab, story, interiorWall, doorway, doorRegion, staircase,
  squareTower, roundTower, curtainWall, gatehouse, courtyard,
  // trim / roofs / openings
  entrance, porch, chimney, spire, gableRoof, flatBattlementRoof, windowBand,
  // low-level builders + helpers (used by archetypes.js)
  region, box, bl, wallSeg, cyl, pt, carve, sk, woodOf, clampi, doorComps,
  DIRV, OPP, resolvePalette
}
