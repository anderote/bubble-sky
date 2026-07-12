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
    design.origin = origin
    return templateBlueprint(design)
  }

  async function pickDesign({ goal, origin, memory, site, brief }) {
    const sys = `You are the ARCHITECT for a Minecraft building assistant. Choose DESIGN PARAMETERS for a build; the geometry is generated deterministically from them. Pick a cohesive style + a footprint that suits the request. Call submit_design. Do not chat.
Styles: ${styleNames.join(', ')}. Room types: great_hall, bedroom, kitchen, library, living.
Guidance: castles/keeps → battlements true, a tower, footprint 14-20. Houses/cottages → gable roof, no battlements, footprint 8-12. Cathedrals/halls → tall wallH (8-10). Respect the SITE (build near groundY; terrainFit "follow" hugs slopes).
If a REFERENCE brief is provided (from web research), let it inform the style, palette feel, proportions and footprint so the build reflects real references.`
    const user = { goal, origin, existingProject: memory || null, site: site || null,
      reference: brief ? { brief: brief.brief, palette: brief.palette, features: brief.features, sources: brief.sources } : null,
      note: 'Pick style, footprint (width,length), wall height, the primary room type, and whether it has a tower/battlements. If a reference brief is present, use it to guide those choices.' }
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
      style: { type: 'string', description: 'one palette style name' },
      width: { type: 'integer', description: 'x footprint, 6-24' },
      length: { type: 'integer', description: 'z footprint, 6-24' },
      wallH: { type: 'integer', description: 'wall height, 4-10' },
      roomType: { type: 'string', enum: ['great_hall', 'bedroom', 'kitchen', 'library', 'living'] },
      roof: { type: 'string', enum: ['gable', 'flat'] },
      battlements: { type: 'boolean' },
      tower: { type: 'boolean' },
      towerSide: { type: 'string', enum: ['north', 'south', 'east', 'west'] }
    },
    required: ['style', 'width', 'length', 'roomType']
  }
}

// Deterministic fallback: infer design params from goal keywords.
function keywordDesign(goal) {
  const g = String(goal || '').toLowerCase()
  const style = styleNames.find(s => g.includes(s)) ||
    (/castle|keep|fort|fortress|palace/.test(g) ? 'castle' : /cottage|cabin|hut|farmhouse/.test(g) ? 'cottage' :
      /wizard|magic|elven|enchant|fantasy/.test(g) ? 'fantasy' : /modern|futur|minimal/.test(g) ? 'modern' :
        /tavern|inn|rustic/.test(g) ? 'rustic' : 'medieval')
  const big = /grand|great|big|huge|epic|castle|keep|cathedral|mansion|hall|palace/.test(g)
  const tower = /tower|keep|castle|fort|turret|spire/.test(g)
  const battlements = /castle|keep|fort|fortress|battlement|crenel|rampart/.test(g)
  return {
    name: titleFrom(g), style, width: big ? 16 : 12, length: big ? 16 : 12,
    wallH: /cathedral|hall|tall/.test(g) ? 9 : (big ? 7 : 6),
    roomType: /bed/.test(g) ? 'bedroom' : /kitchen/.test(g) ? 'kitchen' : /librar/.test(g) ? 'library' : big ? 'great_hall' : 'living',
    roof: battlements ? 'flat' : 'gable', battlements,
    tower: tower ? { side: 'west' } : null
  }
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
  if (/castle/.test(g)) return 'Castle'
  if (/keep/.test(g)) return 'Keep'
  if (/cathedral/.test(g)) return 'Cathedral'
  if (/mansion/.test(g)) return 'Manor'
  if (/tower/.test(g)) return 'Tower'
  if (/cottage|cabin/.test(g)) return 'Cottage'
  if (/house|home/.test(g)) return 'House'
  return 'Build'
}
