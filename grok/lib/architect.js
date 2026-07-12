// grok/lib/architect.js — the ARCHITECT. One reasoning-model call turns a build
// goal into a structured, phased blueprint (submit_build_plan tool). It is given
// a catalog of REAL skills (structures + detailing) so it composes them, plus any
// active project from memory so follow-ups edit the same build.
//
// The LLM backend is pluggable per role (see lib/llm.js). The architect runs on
// Claude (Anthropic) by default when ANTHROPIC_API_KEY is set, else falls back to
// the xAI reasoning model — override with GROK_ARCHITECT_PROVIDER / GROK_ARCHITECT_MODEL.
const { createLLM } = require('./llm')

function catalogText(skills) {
  const line = ([n, args, desc]) => `  - ${n} ${args} — ${desc}`
  return [
    'STRUCTURE SKILLS (each takes an origin x,y,z = its bottom-centre):',
    ...skills.structureCatalog.map(line),
    '',
    'DETAILING SKILLS (absolute coords; these make a box read as a building):',
    ...skills.detailCatalog.map(line),
    '',
    'PRIMITIVES:',
    '  - foundation {x,y,z,radius|(x1,z1,x2,z2),block} — flat base slab one below origin',
    '  - fill_box {x1,y1,z1,x2,y2,z2,block} — solid box',
    '  - set_block {x,y,z,block} — single block (supports states like oak_stairs[facing=east])',
    '  - clear_area {x,y,z,radius,height} — clear to air',
    '  - flatten {x,y,z,radius,floor} — clear + lay a floor'
  ].join('\n')
}

function architectSystem(skills) {
  return `You are the ARCHITECT for Grok, a Minecraft (1.21.6) building assistant with operator powers.
You turn a build goal into a DETAILED, PHASED blueprint and submit it by calling submit_build_plan. Do not chat.

You compose ONLY these real skills:
${catalogText(skills)}

You are ALSO given a SITE SURVEY of the real terrain around the origin (groundY, minY, maxY, slope, hasWater, biome, obstacles). The plan MUST integrate with this landscape:
- Set the build's ground level to the survey groundY (put the floor at groundY+1). Do not float the build or bury it.
- The FIRST phase MUST be "survey + site prep": level ONLY what's needed (flatten/clear a pad at groundY) and, on slopes (slope>2), extend the foundation DOWN to minY-1 with fill_box so it meets the ground instead of hanging in the air.
- If hasWater, keep the build out of the water or fill/dam it first; do not plonk a floor into a lake.
- Orient the main entrance toward open, flat ground; avoid facing it into a hill or obstacles.

HARD RULES (a single fill is NOT a building):
1. The plan MUST have at least 5 phases, ordered: site prep → foundation → shell → towers → roof → openings → interior → detailing. Never drop site prep, foundation, shell, or detailing.
2. Use at least 2 distinct materials (a wall material and a trim/accent, plus a roof material).
3. There MUST be a detailing phase that composes detailing skills (trimCourse, crenellate, torchCadence, windowGrid, cornerPillars, archway, roofGable/roofCone). Battlement/castle builds MUST crenellate walls and light them.
4. Every step needs a "skill", an "args" object with ABSOLUTE integer coordinates (consistent with origin + groundY), and a short "why".
5. Keep the footprint sensible (roughly 20–40 wide for a castle) and self-consistent: towers sit on the wall corners, roofs sit on top of shells, trim sits at floor lines.
6. Build bottom-up: lower phases first.

Palette: choose wall, trim, roof, accent, floor block names (real Minecraft blocks). Reuse them across steps for cohesion.
If given an existing project (memory), EDIT it — reuse its origin/palette and only add/extend (e.g. taller walls, a moat, a new wing).
If given a REPAIR request, output ONLY the phases/steps needed to fix the reported deficiency — do not rebuild what already exists.`
}

// Neutral tool shape (see lib/llm.js): { name, description, schema }.
const PLAN_TOOL = {
  name: 'submit_build_plan',
  description: 'Submit the phased build blueprint.',
  schema: {
      type: 'object',
      properties: {
        project: { type: 'string', description: 'Short name of what is being built' },
        origin: { type: 'object', properties: { x: { type: 'integer' }, y: { type: 'integer' }, z: { type: 'integer' } }, required: ['x', 'y', 'z'] },
        palette: {
          type: 'object',
          properties: { wall: { type: 'string' }, trim: { type: 'string' }, roof: { type: 'string' }, accent: { type: 'string' }, floor: { type: 'string' } },
          required: ['wall', 'trim', 'roof']
        },
        phases: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              phase: { type: 'string', description: 'phase name, e.g. foundation, shell, towers, roof, openings, interior, detailing' },
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
      required: ['project', 'origin', 'palette', 'phases']
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
        ? 'REPAIR MODE: the previous build has the reported deficiency. Emit ONLY the phases/steps to fix it, via submit_build_plan.'
        : 'Emit the blueprint via submit_build_plan. Integrate with the SITE survey. Use absolute coordinates around origin. Obey the HARD RULES.' }
    // Force the tool; long plans need room (~4096) on the Anthropic side.
    const { toolCalls } = await llm.chat({
      system: sys,
      messages: [{ role: 'user', content: JSON.stringify(user) }],
      tools: [PLAN_TOOL], toolChoice: { name: 'submit_build_plan' }, maxTokens: 4096, model: MODEL
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
    bp.phases = Array.isArray(bp.phases) ? bp.phases.filter(p => p && Array.isArray(p.steps) && p.steps.length) : []
    const mats = new Set(), skillNames = new Set()
    let steps = 0
    for (const ph of bp.phases) for (const st of ph.steps) {
      steps++; skillNames.add(st.skill)
      const a = st.args || {}
      for (const k of ['block', 'material', 'roof_material', 'floor', 'trim']) if (a[k]) mats.add(String(a[k]))
    }
    for (const v of Object.values(bp.palette)) if (v) mats.add(String(v))
    bp._validation = {
      phases: bp.phases.length, steps, materials: mats.size, skills: [...skillNames],
      hasDetailing: bp.phases.some(p => /detail|trim|light|batt|crenel|finish/i.test(p.phase)) ||
        [...skillNames].some(s => /trimCourse|torchCadence|windowGrid|crenellate|cornerPillars|archway|roofGable|roofCone/.test(s)),
      ok: bp.phases.length >= 5 && mats.size >= 2
    }
    return bp
  }

  return { plan, MODEL, PROVIDER }
}
