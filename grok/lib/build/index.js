// grok/lib/build/index.js — build pipeline orchestrator.
//
//   planAndBuild(request, ctx)   author → anchor(site) → compile → diff(live
//                                world) → realize(godmode); returns a summary.
//   editBuild(op, bp, ctx)       recompile an existing blueprint after an edit
//                                op → additive diff vs the now-built world →
//                                realize only the changed cells.
//
// The AUTHOR step reuses Grok's proven Architect: it plans a phased, furnished,
// lit build, which fromPhasedPlan() converts into a Layer-A blueprint (or the
// Architect's native planBlueprint() is used when available). Either way the
// build then flows through the additive, site-aware compiler — so surroundings
// are preserved and the same job state Codex consumes is produced.

const path = require('path')
const blueprint = require('./blueprint')
const { compile } = require('./compile')
const { anchorToSite, heightSampler } = require('./anchor')
const { diff, botReadBlock } = require('./diff')
const realize = require('./realize')

// Build MODE: 'godmode' (default — /fill+/setblock via hands) or 'fleet' (a fleet
// of player-builder bots physically place the blocks). Set GROK_BUILD_MODE=fleet.
const BUILD_MODE = String(process.env.GROK_BUILD_MODE || 'godmode').toLowerCase()

async function planAndBuild(request, ctx) {
  const { architect, bot, hands, survey, log = () => {}, memory, shouldAbort } = ctx
  const origin = request.origin || { x: 0, y: 64, z: 0 }

  // 1) AUTHOR — a Layer-A blueprint (native or converted from the phased plan).
  let bp = request.blueprint || null
  if (!bp) bp = await author(architect, request, origin, log)

  // 2) ANCHOR — fit to the real terrain.
  const site = anchorSite(bot, survey, origin, request)
  anchorToSite(bp, site)
  const val = blueprint.validate(bp)
  if (!val.ok) log('blueprint validation:', val.errors.join('; '))

  // 3) COMPILE — semantic blueprint → voxel target.
  const target = compile(bp)

  // 4) DIFF — additive vs the live world.
  const read = bot ? botReadBlock(bot) : (() => 'air')
  const d = diff(target, read, { structure: bp.name })

  // 5) REALIZE — godmode filler (default) OR a fleet of player-builders.
  let filled = null
  if (BUILD_MODE === 'fleet') {
    filled = await realizeWithFleet(d, bp, request, log)
  } else if (hands) {
    filled = realize.realize(d.jobs, hands, shouldAbort)   // godmode: /fill + /setblock (abortable)
  }

  // Optionally emit the shared state + schematic for Codex / interop. NOTE: by
  // default this writes to request.statePath (NOT Codex's live state.json) so a
  // running swarm is never clobbered.
  if (request.emit) {
    try { realize.writeState(d, { structure: bp.name, workers: request.workers || [] }, request.statePath) } catch (e) { log('writeState err', e.message) }
    if (request.schemPath) { try { await realize.exportSchem(target, request.schemPath) } catch (e) { log('schem err', e.message) } }
  }

  return summarize(bp, d, filled, target)
}

async function editBuild(op, bp, ctx) {
  const { bot, hands, survey, log = () => {}, shouldAbort } = ctx
  if (!bp) throw new Error('editBuild needs a blueprint')
  const touched = blueprint.transform(bp, op)
  log('edit', op.type || op.op, '->', touched.join(', '))

  // Re-anchor only if the edit could reach new ground; footings already exist.
  const target = compile(bp)
  const read = bot ? botReadBlock(bot) : (() => 'air')
  const d = diff(target, read, { structure: bp.name })
  let filled = null
  if (hands) filled = realize.realize(d.jobs, hands, shouldAbort)
  const s = summarize(bp, d, filled, target)
  s.touched = touched
  return s
}

// ---- fleet realize: write the shared state, then launch player-builders ----
async function realizeWithFleet(d, bp, request, log) {
  const statePath = request.statePath || path.join(process.cwd(), 'build-out', 'state.json')
  try { realize.writeState(d, { structure: bp.name, workers: request.workers || [] }, statePath) }
  catch (e) { log('fleet writeState err', e.message) }
  try {
    const { runFleet } = require('../../builders/fleet')
    return await runFleet({
      statePath,
      count: +(process.env.GROK_FLEET_COUNT || request.fleetCount || 3),
      host: process.env.MC_HOST || 'localhost',
      port: +(process.env.MC_PORT || 25565),
      version: process.env.MC_VERSION || '1.21.6',
      consolePath: process.env.GROK_FLEET_CONSOLE || path.join(__dirname, '..', '..', '..', 'server', 'console.in'),
      blocksPerSec: +(process.env.GROK_FLEET_BPS || 6),
      log
    })
  } catch (e) { log('fleet realize err', e.stack || e.message); return { mode: 'fleet', error: e.message } }
}

// ---- author ----
async function author(architect, request, origin, log) {
  // Prefer a native Layer-A author if the Architect exposes one.
  if (architect && typeof architect.planBlueprint === 'function') {
    try {
      const raw = await architect.planBlueprint({ goal: request.goal, origin, memory: request.memory, site: request.site })
      return blueprint.normalize(raw, origin)
    } catch (e) { log('planBlueprint err, falling back to phased:', e.message) }
  }
  // Fall back to the proven phased planner and convert.
  if (architect && typeof architect.plan === 'function') {
    const plan = await architect.plan({ goal: request.goal, origin, memory: request.memory, site: request.site })
    plan.origin = origin
    return blueprint.fromPhasedPlan(plan)
  }
  throw new Error('no architect available to author a build')
}

function anchorSite(bot, survey, origin, request) {
  let site = { groundY: origin.y, slope: 0 }
  if (bot && survey && typeof survey.surveySite === 'function') {
    try { site = survey.surveySite(bot, origin, request.radius || 20) } catch { /* keep default */ }
  }
  if (request.site) site = Object.assign({}, request.site, { groundY: request.site.groundY != null ? request.site.groundY : site.groundY })
  if (bot) site.heightAt = heightSampler(bot, site.groundY != null ? site.groundY : origin.y)
  if (request.heightAt) site.heightAt = request.heightAt
  if (request.terrainFit) site.terrainFit = request.terrainFit
  return site
}

function summarize(bp, d, filled, target) {
  const materials = [...new Set(target.palette.slice(1).map(s => String(s).replace(/\[.*$/, '')))]
    .filter(x => x && x !== 'air')
  return {
    project: bp.name,
    regions: bp.regions.map(r => r.id),
    regionCount: bp.regions.length,
    jobs: d.total,
    placed: d.placed,
    air: d.air,
    skipped: d.skipped,
    additive: true,
    size: d.size,
    materials: materials.slice(0, 12),
    materialCount: materials.length,
    filled,
    blueprint: bp
  }
}

module.exports = { planAndBuild, editBuild, author, blueprint }
