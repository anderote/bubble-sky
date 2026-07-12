// grok/lib/architect.js — the ARCHITECT. One reasoning-model call turns a build
// goal into a RICH SPEC and then a structured, phased blueprint (submit_build_plan
// tool). It is given a catalog of REAL skills (structures + detailing + furniture +
// palettes) so it composes them, plus any active project from memory so follow-ups
// edit the same build.
//
// Spec-first: the biggest quality lever (per T2BM/APT ablations) is having the
// Architect EXPAND a terse request into a detailed building specification — style,
// palette, room list + furniture, exterior features, detailing + lighting intent,
// proportions — BEFORE it emits phases. The plan is derived FROM that spec.
//
// The LLM backend is pluggable per role (see lib/llm.js). The architect runs on
// Claude (Anthropic) by default when ANTHROPIC_API_KEY is set, else falls back to
// the xAI reasoning model — override with GROK_ARCHITECT_PROVIDER / GROK_ARCHITECT_MODEL.
const { createLLM } = require('./llm')
const { styleNames } = require('../palettes')
const { templateBlueprint } = require('./build/template')
const { compose, ARCHETYPES } = require('./build/archetypes')
const research = require('./research')

function catalogText(skills) {
  const line = ([n, args, desc]) => `  - ${n} ${args} — ${desc}`
  return [
    `PALETTES (pick ONE style; its roles keep the build cohesive): ${styleNames.join(', ')}.`,
    '  Palette roles = {wall, trim, accent, floor, roof, pillar, glass, light, path, foundation, detail}.',
    '  Put the chosen role blocks in the top-level "palette". Every step that needs a block should use a palette role',
    '  block so materials stay consistent. Furniture/furnishRoom steps may omit block args — they inherit the palette.',
    '',
    'STRUCTURE SKILLS (STRUCTURAL — each takes an origin x,y,z = its bottom-centre; executed as fills):',
    ...skills.structureCatalog.map(line),
    '',
    'DETAILING SKILLS (absolute coords; these give walls DEPTH + read as a building):',
    ...skills.detailCatalog.map(line),
    '',
    'FURNITURE + ROOMS (FUNCTIONAL — single-point placements with blockstates; palette-aware):',
    ...(skills.furnitureCatalog || []).map(line),
    '',
    'PRIMITIVES:',
    '  - foundation {x,y,z,radius|(x1,z1,x2,z2),block} — flat base slab one below origin',
    '  - fill_box {x1,y1,z1,x2,y2,z2,block} — solid box (use "air" to hollow out an interior)',
    '  - set_block {x,y,z,block} — single block (supports states like oak_stairs[facing=east,half=top])',
    '  - clear_area {x,y,z,radius,height} — clear to air',
    '  - flatten {x,y,z,radius,floor} — clear + lay a floor'
  ].join('\n')
}

function architectSystem(skills) {
  return `You are the ARCHITECT for Grok, a Minecraft (1.21.6) building assistant with operator powers.
You turn a build goal into a DETAILED building SPEC and then a PHASED blueprint, and submit both by calling submit_build_plan. Do not chat.

You compose ONLY these real skills:
${catalogText(skills)}

SPEC FIRST (do this before planning — it is the single biggest quality lever):
Never plan from the raw one-liner. First write "spec": a short but concrete brief that decides:
  - style + the ONE palette you'll use (name it), and the actual role blocks;
  - the ROOM LIST (e.g. great hall, kitchen, 2 bedrooms, library) and what furniture each holds;
  - exterior features (towers, gatehouse, battlements, porch, chimney…);
  - detailing intentions (where trim courses, pillar grids, framed windows, wall greebling, overhang go);
  - the lighting plan; and the proportions (footprint, ceiling heights).
Then derive "phases" that BUILD that spec. Aim for a spec a skilled human would be happy to build from.

STRUCTURAL vs FUNCTIONAL components:
  - STRUCTURAL = walls/floors/roofs/towers: coordinate spans, executed as /fill. Hollow interiors with a fill_box of "air".
  - FUNCTIONAL = doors, windows, beds, furniture, lights: a POINT + blockstate props (facing, half, open). Use set_block / furniture / furnishRoom.

You are ALSO given a SITE SURVEY of the real terrain (groundY, minY, maxY, slope, hasWater, biome, obstacles). Integrate with it:
- Floor at groundY+1. Do not float or bury the build.
- FIRST phase = "site prep": flatten/clear only the needed pad at groundY; on slopes (slope>2) extend the foundation DOWN to minY-1 with fill_box so it meets the ground.
- If hasWater, keep the build out of the water or dam it first.
- Face the main entrance toward open, flat ground.

ORDERED PHASES (use this order; a single fill is NOT a building):
  1. site prep  2. foundation + PLINTH  3. shell (palette walls + pillarGrid)  4. towers (if any)
  5. roof (with roofGable/roofCone + roofOverhang)  6. openings (doors + FRAMED windows)
  7. interior room structure (partition walls, floors between storeys)  8. interior FURNITURE (furnishRoom per room)
  9. detailing (trimCourse, wallGreeble, cornerPillars, crenellate, bannerRow)  10. LIGHTING (lightingCadence inside + torchCadence outside)
Keep site prep, foundation, shell, interior-furniture, detailing, and lighting — never drop them.

HARD RULES:
1. At least 8 phases in that order. Never a bare shell.
2. Use at least 3 DISTINCT materials from the palette (wall + trim + accent), plus a roof material. Never mono-material.
3. Walls must have DEPTH + rhythm: NO blank wall wider than ~6 without detail. Use pillarGrid every 4–6 blocks, a trimCourse at the floor AND roof lines, framed windows, and wallGreeble to vary depth (protruding beams/stairs/slabs). Multi-depth, not just a flat wall + one band.
4. Interiors must be FURNISHED + FUNCTIONAL: call furnishRoom for each interior room (bedroom|kitchen|library|great_hall|living), giving its interior x1,y1,z1,x2,y2,z2 and the palette. Rooms need real furniture and circulation space.
5. Interiors must be FULLY LIT: a lightingCadence over every room floor (spacing ~5–6; hang lanterns/chandeliers in tall rooms). Exterior lit with torchCadence. No dark interior spots.
6. Sensible proportions: house/cottage interior ceiling 4–5; halls/cathedrals taller (7+). Doors 2 tall, windows silled ~1 above the floor. Roof gets an overhang; the base gets a plinth.
7. Every step needs "skill", an "args" object with ABSOLUTE integer coordinates (consistent with origin + groundY), and a short "why". Use ONLY real Minecraft 1.21.6 block names.
8. Build bottom-up. Towers sit on wall corners; roofs sit on top of shells; trim sits at floor lines; furniture sits inside the hollow interior.

If given an existing project (memory), EDIT it — reuse its origin/palette and only add/extend.
If given a REPAIR request, output ONLY the phases/steps to fix the reported deficiency — do not rebuild what exists.`
}

// Neutral tool shape (see lib/llm.js): { name, description, schema }.
const PLAN_TOOL = {
  name: 'submit_build_plan',
  description: 'Submit the building SPEC + phased blueprint.',
  schema: {
      type: 'object',
      properties: {
        project: { type: 'string', description: 'Short name of what is being built' },
        spec: { type: 'string', description: 'The rich building specification: style+palette, room list + furniture, exterior features, detailing + lighting intent, proportions. Write this BEFORE deciding phases.' },
        origin: { type: 'object', properties: { x: { type: 'integer' }, y: { type: 'integer' }, z: { type: 'integer' } }, required: ['x', 'y', 'z'] },
        palette: {
          type: 'object',
          description: 'The chosen palette role blocks (real MC block names).',
          properties: {
            wall: { type: 'string' }, trim: { type: 'string' }, accent: { type: 'string' }, floor: { type: 'string' },
            roof: { type: 'string' }, pillar: { type: 'string' }, glass: { type: 'string' }, light: { type: 'string' },
            path: { type: 'string' }, foundation: { type: 'string' }, detail: { type: 'string' }
          },
          required: ['wall', 'trim', 'accent', 'roof', 'floor', 'light']
        },
        phases: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              phase: { type: 'string', description: 'phase name, e.g. site prep, foundation, shell, towers, roof, openings, interior structure, furniture, detailing, lighting' },
              steps: {
                type: 'array',
                items: {
                  type: 'object',
                  properties: { skill: { type: 'string' }, args: { type: 'object' }, why: { type: 'string' } },
                  required: ['skill', 'args']
                }
              }
            },
            required: ['phase', 'steps']
          }
        }
      },
      required: ['project', 'spec', 'origin', 'palette', 'phases']
    }
}

module.exports = function makeArchitect({ skills, log = () => {} }) {
  // Per-role backend: default to Anthropic (Claude) when a key is present, else xAI.
  const PROVIDER = process.env.GROK_ARCHITECT_PROVIDER || (process.env.ANTHROPIC_API_KEY ? 'anthropic' : 'xai')
  const MODEL = process.env.GROK_ARCHITECT_MODEL || (PROVIDER === 'anthropic' ? 'claude-opus-4-8' : 'grok-4.5')
  const llm = createLLM({ provider: PROVIDER })

  async function plan({ goal, origin, memory, site, repair }) {
    const sys = architectSystem(skills)
    const user = { goal, origin, site: site || null, existingProject: memory || null, repair: repair || null,
      note: repair
        ? 'REPAIR MODE: the previous build has the reported deficiency. Emit ONLY the phases/steps to fix it, via submit_build_plan (still include a short spec + palette).'
        : 'Write the SPEC first, then derive phases. Furnish + light every interior. Integrate with the SITE survey. Use absolute coordinates around origin. Obey the HARD RULES.' }
    // Force the tool; rich spec + long plans need room on the Anthropic side.
    const { toolCalls } = await llm.chat({
      system: sys,
      messages: [{ role: 'user', content: JSON.stringify(user) }],
      tools: [PLAN_TOOL], toolChoice: { name: 'submit_build_plan' }, maxTokens: 8192, model: MODEL
    })
    const tc = toolCalls[0]
    if (!tc || !tc.args) throw new Error('architect returned no plan')
    return normalize(tc.args, origin)
  }

  // Fill gaps, coerce types, and validate against the anti-cube guardrails.
  function normalize(bp, fallbackOrigin) {
    bp.origin = bp.origin || fallbackOrigin
    bp.origin = { x: Math.round(+bp.origin.x), y: Math.round(+bp.origin.y), z: Math.round(+bp.origin.z) }
    bp.palette = bp.palette || {}
    bp.spec = bp.spec || ''
    bp.phases = Array.isArray(bp.phases) ? bp.phases.filter(p => p && Array.isArray(p.steps) && p.steps.length) : []
    const mats = new Set(), skillNames = new Set()
    let steps = 0
    for (const ph of bp.phases) for (const st of ph.steps) {
      steps++; skillNames.add(st.skill)
      const a = st.args || {}
      for (const k of ['block', 'material', 'roof_material', 'floor', 'trim', 'wall', 'accent']) if (a[k]) mats.add(String(a[k]))
    }
    for (const v of Object.values(bp.palette)) if (v) mats.add(String(v))
    const names = [...skillNames]
    bp._validation = {
      phases: bp.phases.length, steps, materials: mats.size, skills: names,
      hasDetailing: bp.phases.some(p => /detail|trim|light|batt|crenel|finish/i.test(p.phase)) ||
        names.some(s => /trimCourse|torchCadence|windowGrid|windowFrame|pillarGrid|wallGreeble|plinth|roofOverhang|crenellate|cornerPillars|archway|roofGable|roofCone|bannerRow|lightingCadence/.test(s)),
      hasFurniture: names.some(s => /furnishRoom|chair|table|sofa|bed|bookshelf|desk|wardrobe|kitchen_counter|fireplace|chandelier|rug/.test(s)),
      hasLighting: names.some(s => /lightingCadence|torchCadence|chandelier|lamp_post/.test(s)),
      ok: bp.phases.length >= 5 && mats.size >= 2
    }
    return bp
  }

  // ---- Layer-A blueprint author ----
  // The Architect picks the DESIGN PARAMETERS; template.js turns them into a
  // correct, additive, terrain-fittable semantic blueprint (hollow shell +
  // furnished/detailed skill components). This is the agent-native path: the
  // output is an inspectable, diffable Layer-A blueprint, not an ad-hoc plan.
  async function planBlueprint({ goal, origin, memory, site }) {
    let design = keywordDesign(goal)
    // WEB RESEARCH: for open-ended / "nice" / named-style requests, gather real
    // references first and feed them into the design-params prompt. Bounded +
    // graceful — never blocks or crashes a build. Quiet in chat (one log line).
    let brief = null
    if (research.shouldResearch(goal)) {
      try {
        const r = await research.research(goal)
        if (r && (r.brief || r.palette)) {
          brief = r
          log(`research (${r._via || '?'}): ${(r.brief || '').replace(/\s+/g, ' ').slice(0, 90)}${r.sources && r.sources.length ? ` [${r.sources.length} src]` : ''}`)
        } else if (r && r._fallback) log(`research fallback: ${r._fallback}`)
      } catch (e) { log('research failed (continuing):', e.message) }
    }
    try {
      const params = await pickDesign({ goal, origin, memory, site, brief })
      if (params) design = Object.assign(design, prune(params))
    } catch (e) { log('planBlueprint design pick failed, using keywords:', e.message) }
    // Explicit user dimensions ALWAYS win over the model's pick (e.g. "100x100",
    // "walls 15 tall", "towers every 25") so big asks aren't clamped to a small keep.
    const dims = parseDims(goal)
    if (dims.width) design.width = dims.width
    if (dims.length) design.length = dims.length
    if (dims.wallH) design.wallH = dims.wallH
    if (dims.towerSpacing) design.towerSpacing = dims.towerSpacing
    design.origin = origin
    // Seed varied-but-stable composition choices from the request + placement,
    // so two "cottage" builds at different sites differ yet rebuild identically.
    design.seed = `${String(goal || '')}|${origin.x},${origin.z}`
    // COMPOSE the chosen archetype (varied, multi-room, multi-story). The single
    // template stays as the house/fallback path if a composer errors.
    if (design.archetype && ARCHETYPES[String(design.archetype).toLowerCase()]) {
      try { return compose(design) } catch (e) { log('archetype compose failed, using template:', e.message) }
    }
    return templateBlueprint(design)
  }

  async function pickDesign({ goal, origin, memory, site, brief }) {
    const sys = `You are the ARCHITECT for a Minecraft building assistant. Choose DESIGN PARAMETERS for a build; a COMPOSITIONAL geometry engine turns them into a varied, multi-room, multi-story building. Call submit_design. Do not chat.
Styles: ${styleNames.join(', ')}. Room types: great_hall, bedroom, kitchen, library, living.
ARCHETYPE (pick the one that best matches the request — this drives the whole silhouette + layout):
  - cottage — small home, sometimes L-shaped, chimney + porch + gable, 1-2 rooms.
  - house — central hall, optional wing, 2 stories with a staircase, multiple furnished rooms.
  - manor — bigger house with 2 wings + chimney, multi-room + multi-story.
  - keep — compact fortified tower-house, battlements, 2-4 floors.
  - castle — curtain walls around a courtyard, corner + perimeter towers spaced by towerSpacing, a gatehouse, an inner keep (scales to 100x100+).
  - tower / wizard_tower — tall round multi-floor tower, conical roof, balcony, windows per level.
  - cathedral / hall — long tall nave + transept + big windows + a bell tower/spire + buttresses.
  - fort — rectangular rampart with gatehouses + interior barracks rows + a central hall.
Guidance: HONOR EXPLICIT DIMENSIONS — if the user says "100x100" or "footprint 100" or "60 wide", set width/length to exactly that; if they say "walls 15 tall", set wallH to that; "towers every 25" → towerSpacing 25. Otherwise scale footprint to the archetype: cottage 8-12, house 12-18, manor 16-28, keep 12-22, cathedral 14-30 wide x 28-60 long, tower 9-13, GRAND castle/fort 40-120. Cathedrals/halls → tall wallH (12-24). Set stories for houses/keeps/manors, wings for houses/manors, towerSpacing for castles/forts. Respect the SITE (build near groundY; terrainFit "follow" hugs slopes).
If a REFERENCE brief is provided (from web research), let it inform the style, palette feel, proportions and footprint so the build reflects real references.`
    const user = { goal, origin, existingProject: memory || null, site: site || null,
      reference: brief ? { brief: brief.brief, palette: brief.palette, features: brief.features, sources: brief.sources } : null,
      note: 'Pick archetype, style, footprint (width,length), wall height, stories/wings, the primary room type, and towerSpacing for castles/forts. If a reference brief is present, use it to guide those choices.' }
    const { toolCalls } = await llm.chat({
      system: sys, messages: [{ role: 'user', content: JSON.stringify(user) }],
      tools: [DESIGN_TOOL], toolChoice: { name: 'submit_design' }, maxTokens: 512, model: MODEL
    })
    return toolCalls[0] && toolCalls[0].args
  }

  return { plan, planBlueprint, MODEL, PROVIDER }
}

// Compact design-parameter tool (the geometry is deterministic from these).
const DESIGN_TOOL = {
  name: 'submit_design',
  description: 'Choose the design parameters for the build.',
  schema: {
    type: 'object',
    properties: {
      name: { type: 'string' },
      archetype: { type: 'string', enum: ['cottage', 'house', 'manor', 'keep', 'castle', 'tower', 'wizard_tower', 'cathedral', 'hall', 'fort'], description: 'the building archetype — drives the whole composition/silhouette' },
      style: { type: 'string', description: 'one palette style name' },
      width: { type: 'integer', description: 'x footprint in blocks, 6-128 (scale to the request: cottage ~10, keep ~16, GRAND castle 60-120)' },
      length: { type: 'integer', description: 'z footprint in blocks, 6-128' },
      wallH: { type: 'integer', description: 'wall height, 4-40 (cathedral/keep taller)' },
      stories: { type: 'integer', description: 'number of floors for houses/manors/keeps, 1-4' },
      wings: { type: 'integer', description: 'side wings for house/manor, 0-2' },
      courtyard: { type: 'boolean', description: 'open central courtyard (castle/fort)' },
      towerCount: { type: 'integer', description: 'hint for how many towers a castle should carry' },
      towerSpacing: { type: 'integer', description: 'blocks between perimeter towers on castles/forts (e.g. 25)' },
      roomProgram: { type: 'string', description: 'comma-separated room types to include (e.g. "great_hall,kitchen,bedroom,library")' },
      roofStyle: { type: 'string', enum: ['gable', 'flat', 'hip', 'cone', 'spire'] },
      roomType: { type: 'string', enum: ['great_hall', 'bedroom', 'kitchen', 'library', 'living'] },
      roof: { type: 'string', enum: ['gable', 'flat'] },
      battlements: { type: 'boolean' },
      tower: { type: 'boolean' },
      towerSide: { type: 'string', enum: ['north', 'south', 'east', 'west'] }
    },
    required: ['archetype', 'style', 'width', 'length', 'roomType']
  }
}

// Pull explicit dimensions out of the request so big asks aren't clamped away.
function parseDims(goal) {
  const g = String(goal || '').toLowerCase().replace(/,/g, '')
  const out = {}
  let m = g.match(/(\d{1,3})\s*(?:x|by|×)\s*(\d{1,3})/)          // "100x100", "100 by 100"
  if (m) { out.width = +m[1]; out.length = +m[2] }
  if (out.width == null) {                                        // "footprint 100", "60 wide"
    m = g.match(/(?:footprint|width|wide|across|diameter)\D{0,8}(\d{2,3})/) || g.match(/(\d{2,3})\s*(?:blocks?\s*)?(?:footprint|wide|across)/)
    if (m) { out.width = out.length = +m[1] }
  }
  m = g.match(/(\d{1,3})\s*(?:blocks?\s*)?(?:tall|high)/)          // "walls 15 tall"
  if (m) out.wallH = +m[1]
  m = g.match(/towers?\s*(?:every|each)\s*(\d{1,3})/)              // "towers every 25"
  if (m) out.towerSpacing = +m[1]
  return out
}

// Per-archetype footprint/proportion defaults (explicit dims + LLM picks override).
const ARCHETYPE_DEFAULTS = {
  cottage: { width: 10, length: 10, wallH: 5, roomType: 'living', stories: 1, roof: 'gable', battlements: false },
  house: { width: 14, length: 12, wallH: 5, roomType: 'living', stories: 2, roof: 'gable', battlements: false },
  manor: { width: 22, length: 16, wallH: 6, roomType: 'great_hall', stories: 2, wings: 2, roof: 'gable', battlements: false },
  keep: { width: 14, length: 14, wallH: 15, roomType: 'great_hall', stories: 3, roof: 'flat', battlements: true },
  castle: { width: 60, length: 60, wallH: 10, roomType: 'great_hall', towerSpacing: 25, roof: 'flat', battlements: true },
  tower: { width: 11, length: 11, wallH: 22, roomType: 'library', roof: 'cone' },
  wizard_tower: { width: 11, length: 11, wallH: 26, roomType: 'library', roof: 'cone' },
  cathedral: { width: 20, length: 40, wallH: 16, roomType: 'great_hall', roof: 'gable' },
  hall: { width: 20, length: 40, wallH: 16, roomType: 'great_hall', roof: 'gable' },
  fort: { width: 40, length: 30, wallH: 7, roomType: 'great_hall', towerSpacing: 20, roof: 'flat', battlements: true }
}
const ARCHETYPE_STYLE = {
  wizard_tower: 'fantasy', castle: 'castle', keep: 'medieval', fort: 'castle',
  cathedral: 'medieval', hall: 'medieval', cottage: 'cottage', manor: 'rustic', house: 'medieval', tower: 'medieval'
}

// Map request words → an archetype (keeps the deterministic fallback rich).
function archetypeFor(g) {
  if (/wizard|mage|sorcer|arcane/.test(g)) return 'wizard_tower'
  if (/cathedral|church|chapel|basilica|minster|temple/.test(g)) return 'cathedral'
  if (/great\s*hall|\bhall\b/.test(g)) return 'cathedral'
  if (/castle|palace|citadel|stronghold|fortress/.test(g)) return 'castle'
  if (/\bfort\b|garrison|barracks|legion|castrum|roman camp/.test(g)) return 'fort'
  if (/keep|donjon/.test(g)) return 'keep'
  if (/tower|turret|spire|belfry/.test(g)) return 'tower'
  if (/manor|mansion|estate|villa|chateau/.test(g)) return 'manor'
  if (/cottage|cabin|hut|farmhouse|bungalow|shack/.test(g)) return 'cottage'
  return 'house'
}

// Deterministic fallback: infer design params from goal keywords.
function keywordDesign(goal) {
  const g = String(goal || '').toLowerCase()
  const archetype = archetypeFor(g)
  const d = Object.assign({}, ARCHETYPE_DEFAULTS[archetype] || ARCHETYPE_DEFAULTS.house)
  d.archetype = archetype
  d.name = titleFrom(g)
  d.style = styleNames.find(s => g.includes(s)) ||
    (/wizard|magic|elven|enchant|fantasy/.test(g) ? 'fantasy' : /modern|futur|minimal/.test(g) ? 'modern' :
      /tavern|inn|rustic/.test(g) ? 'rustic' : ARCHETYPE_STYLE[archetype] || 'medieval')
  if (/bed/.test(g)) d.roomType = 'bedroom'
  else if (/kitchen/.test(g)) d.roomType = 'kitchen'
  else if (/librar|study/.test(g)) d.roomType = 'library'
  return d
}

function prune(o) {
  const out = {}
  for (const [k, v] of Object.entries(o)) if (v != null && v !== '') out[k] = v
  if (out.tower === true) out.tower = { side: out.towerSide || 'west' }
  else if (out.tower === false) out.tower = null
  delete out.towerSide
  return out
}

function titleFrom(g) {
  if (/castle|palace|citadel|stronghold|fortress/.test(g)) return 'Castle'
  if (/\bfort\b|garrison|barracks|castrum/.test(g)) return 'Fort'
  if (/keep|donjon/.test(g)) return 'Keep'
  if (/cathedral|church|chapel|basilica|minster/.test(g)) return 'Cathedral'
  if (/wizard|mage|sorcer|arcane/.test(g)) return 'Wizard Tower'
  if (/manor|mansion|estate|villa|chateau/.test(g)) return 'Manor'
  if (/tower|turret|spire|belfry/.test(g)) return 'Tower'
  if (/cottage|cabin|hut|farmhouse/.test(g)) return 'Cottage'
  if (/house|home/.test(g)) return 'House'
  return 'Build'
}
