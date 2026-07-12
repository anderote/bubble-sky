// grok/agents/crew.js — the BUILDER CREW orchestrator.
//
//   runCrew({ count, goal, origin, host, port, ... })
//
// Lays out `count` building PLOTS as a ring around a central square, assigns each
// plot a ROLE (house / smithy / tavern / church / market / …), spawns one embodied
// LLM Agent per plot (agent.js), and lets every agent's brain decide + build its
// plot CONCURRENTLY. A deterministic, embodied CivicMason builds the CENTRAL
// FEATURE — a covered well, a stone plaza and radial paths connecting each plot to
// the square. Every bot places blocks BY HAND (no /setblock, no /fill).
//
// Coordination is ENTIRELY through the shared state.json (Codex's schema, reused —
// not forked): each agent writes its jobs under a distinct plot claim key and takes
// the claim lease, so disjoint plots + disjoint keys ⇒ agents never collide.
//
// CLI:  node agents/crew.js --count 3 --goal "small village" --origin 0,-60,0 \
//              --port 25567 --console /path/to/console.in
// API:  const { runCrew } = require('./agents/crew'); await runCrew({ ... })

const fs = require('fs')
const path = require('path')
const Vec3 = require('vec3')
const { Builder, withState, keyOf } = require('../builders/builder')
const { claimRegion, releaseRegion, releaseExpired } = require('../lib/build/state')
const makeArchitect = require('../lib/architect')
const { Agent } = require('./agent')

const sleep = ms => new Promise(r => setTimeout(r, ms))

const ROLES = ['house', 'smithy', 'tavern', 'church', 'bakery', 'market', 'barn', 'library', 'house', 'smithy']
const DIRS = [
  { a: 0, n: 'east' }, { a: 45, n: 'southeast' }, { a: 90, n: 'south' }, { a: 135, n: 'southwest' },
  { a: 180, n: 'west' }, { a: 225, n: 'northwest' }, { a: 270, n: 'north' }, { a: 315, n: 'northeast' }
]
function dirName(deg) {
  deg = ((deg % 360) + 360) % 360
  let best = DIRS[0], bd = 999
  for (const d of DIRS) { let diff = Math.abs(deg - d.a); diff = Math.min(diff, 360 - diff); if (diff < bd) { bd = diff; best = d } }
  return best.n
}

// ---- village layout: a ring of plots + a central plaza ----
function layoutVillage(origin, count) {
  const plotSize = 13
  const gap = 7
  const foot = { w: plotSize - 2, l: plotSize - 2 }             // ≤11×11 building
  // ring radius large enough that neighbouring plot footprints never touch
  const minR = ((plotSize + gap) * count) / (2 * Math.PI)
  const R = Math.max(16, Math.ceil(minR))
  const plots = []
  for (let i = 0; i < count; i++) {
    const ang = (2 * Math.PI * i) / count
    const px = origin.x + Math.round(R * Math.cos(ang))
    const pz = origin.z + Math.round(R * Math.sin(ang))
    const deg = (ang * 180 / Math.PI)
    plots.push({
      key: `plot-${i}`, role: ROLES[i % ROLES.length], dir: dirName(deg),
      theme: `${dirName(deg)} side of ${'the village square'}`,
      origin: { x: px, y: origin.y, z: pz }, foot, angle: ang
    })
  }
  const plazaR = Math.max(4, Math.min(6, Math.round(R * 0.3)))
  return { plots, R, plotSize, foot, plazaR, center: { ...origin } }
}

// ---- the central feature: deterministic, embodied (no LLM, no godmode) ----
// A CivicMason samples the ground itself (so it is terrain-robust), then generates
// plaza + path + well jobs and places them BY HAND, exactly like the Builder fleet.
class CivicMason extends Builder {
  constructor(opts) { super(opts); this.center = opts.center; this.lay = opts.lay; this._authored = false }

  async run() {
    while (!this.stopped) {
      try {
        await this.connect(); await this.ensureCreative(); this.startSync(); await sleep(400)
        if (!this._authored) await this.authorCivic()
        this.claim()
        const jobs = this.pending()
        if (jobs.length) { this.log(`laying plaza, paths + well — ${jobs.length} blocks by hand`); await this.buildRegion('civic', jobs) }
        try { this.bot.chat('Village square, well and paths are laid.') } catch {}
        break
      } catch (e) { this.log('session ended:', e.message) }
      if (this.stopped) break
      this.quit()
      if (this._authored && this.pending().length === 0) break
      this.log('reconnecting to finish the square…'); await sleep(1500)
    }
    this.release(); this.quit()
    return { name: this.name, role: 'civic', key: 'civic', building: 'square+well', placed: this.placed, skipped: this.skipped, jobs: this._jobCount || 0 }
  }

  async authorCivic() {
    const c = this.center
    await this.ensureNear(c)
    const groundTop = this.sampleGround(c)
    const floorY = groundTop + 1
    try { this.bot.chat('Marking out the village square and well.') } catch {}
    const jobs = genCivicJobs(c, floorY, this.lay)
    await withState(this.statePath, state => {
      state.jobs.push(...jobs)
      state.crew = state.crew || {}
      state.crew.civic = { agent: this.name, role: 'civic', building: 'square+well', jobs: jobs.length, placed: 0, status: 'building', via: 'deterministic' }
    })
    this._authored = true; this._jobCount = jobs.length
    this.log(`authored ${jobs.length} civic jobs at floorY=${floorY}`)
  }

  sampleGround(c) {
    for (let y = c.y + 12; y >= c.y - 30; y--) {
      let b; try { b = this.bot.blockAt(new Vec3(c.x, y, c.z)) } catch { b = null }
      if (b && b.name !== 'air' && b.name !== 'cave_air' && b.name !== 'void_air') return y
    }
    return c.y - 1
  }
  claim() { return withState(this.statePath, state => claimRegion(state, 'civic', this.name, this.ttl)) }
  release() { return withState(this.statePath, state => releaseRegion(state, 'civic', this.name)).catch(() => {}) }
  pending() {
    let s; try { s = JSON.parse(fs.readFileSync(this.statePath, 'utf8')) } catch { return [] }
    return s.jobs.filter(j => keyOf(j) === 'civic' && !j.done && !j.skip).map(j => ({ ...j }))
  }
}

// Generate the central feature as a hand-placeable job list (all blocks with real
// item forms; ordered bottom-up by the Builder). floorY sits on the ground top.
function genCivicJobs(center, floorY, lay) {
  const jobs = []
  let n = 0
  const add = (x, y, z, block, region) => { jobs.push({ id: `civic:${String(++n).padStart(6, '0')}`, pos: { x, y, z }, block, phase: 'civic', region: region || 'civic', claim: 'civic' }) }
  const cx = center.x, cz = center.z
  const isWell = (x, z) => Math.abs(x - cx) <= 1 && Math.abs(z - cz) <= 1

  // Plaza pad: a stone-brick square, minus the well footprint (left for the well).
  const pr = lay.plazaR
  for (let dx = -pr; dx <= pr; dx++) for (let dz = -pr; dz <= pr; dz++) {
    const x = cx + dx, z = cz + dz
    if (isWell(x, z)) continue
    add(x, floorY, z, 'stone_bricks', 'plaza')
  }

  // Radial gravel paths from the plaza edge out toward each plot.
  for (const p of lay.plots) {
    const dx = p.origin.x - cx, dz = p.origin.z - cz
    const dist = Math.max(1, Math.round(Math.hypot(dx, dz)))
    const ux = dx / dist, uz = dz / dist
    for (let r = pr; r <= dist - 6; r++) {
      const x = Math.round(cx + ux * r), z = Math.round(cz + uz * r)
      add(x, floorY, z, 'gravel', 'path')
      // widen the path to 2-wide across the dominant axis
      if (Math.abs(ux) > Math.abs(uz)) add(x, floorY, z + 1, 'gravel', 'path')
      else add(x + 1, floorY, z, 'gravel', 'path')
    }
  }

  // The WELL: a cobblestone ring, four fence posts, a cobblestone canopy + a lantern.
  const ring = [[-1, -1], [0, -1], [1, -1], [-1, 0], [1, 0], [-1, 1], [0, 1], [1, 1]]
  for (const [dx, dz] of ring) add(cx + dx, floorY, cz + dz, 'cobblestone', 'well')       // rim
  const posts = [[-1, -1], [1, -1], [-1, 1], [1, 1]]
  for (let dy = 1; dy <= 2; dy++) for (const [dx, dz] of posts) add(cx + dx, floorY + dy, cz + dz, 'oak_fence', 'well')
  for (let dx = -1; dx <= 1; dx++) for (let dz = -1; dz <= 1; dz++) add(cx + dx, floorY + 3, cz + dz, 'cobblestone', 'well') // canopy
  add(cx, floorY + 4, cz, 'lantern', 'well')                                              // crowning light
  return jobs
}

// ---- shared state bootstrap ----
function initState(statePath, goal, origin) {
  const state = {
    taskId: `crew-${Date.now()}`,
    status: 'building',
    structure: goal,
    origin,
    source: { type: 'crew', name: goal, additive: true },
    workers: [],
    jobs: [],
    claims: {},
    crew: {},
    createdAt: new Date().toISOString()
  }
  fs.mkdirSync(path.dirname(statePath), { recursive: true })
  fs.writeFileSync(statePath, JSON.stringify(state, null, 2))
  return statePath
}

function progress(statePath) {
  const s = JSON.parse(fs.readFileSync(statePath, 'utf8'))
  const total = s.jobs.length
  const done = s.jobs.filter(j => j.done).length
  const skipped = s.jobs.filter(j => j.skip && !j.done).length
  const owners = {}; for (const [k, c] of Object.entries(s.claims || {})) owners[k] = c.agent
  const crew = s.crew || {}
  return { total, done, skipped, owners, crew }
}

// ---- orchestrate ----
async function runCrew(opts = {}) {
  const count = Math.max(1, opts.count || 3)
  const goal = opts.goal || 'a small village'
  const origin = opts.origin || { x: 0, y: -60, z: 0 }
  const log = opts.log || ((...a) => console.log('[crew]', ...a))
  const statePath = opts.statePath || path.join(__dirname, '..', 'build-out', 'crew-state.json')
  const consolePath = opts.consolePath
  const host = opts.host || 'localhost'
  const port = opts.port || 25565
  const version = opts.version || '1.21.6'
  const bps = opts.blocksPerSec || 6

  const lay = layoutVillage(origin, count)
  log(`village "${goal}" at ${origin.x},${origin.y},${origin.z} — ${count} plots on ring R=${lay.R}, plaza r=${lay.plazaR}`)
  for (const p of lay.plots) log(`  ${p.key}: ${p.role} @ ${p.origin.x},${p.origin.z} (${p.dir})`)

  initState(statePath, goal, origin)

  // One Architect brain shared by all agents for the AUTHOR step (planBlueprint).
  const architect = makeArchitect({ skills: {}, log: () => {} })
  log(`architect brain: ${architect.PROVIDER}/${architect.MODEL}`)

  // Spawn: one Agent per plot + one CivicMason for the central feature.
  const workers = []
  for (let i = 0; i < count; i++) {
    const p = lay.plots[i]
    workers.push(new Agent({
      username: agentName(p.role, i), plot: p, architect, villageGoal: goal,
      provider: architect.PROVIDER, model: architect.MODEL,
      statePath, consolePath, host, port, version, blocksPerSec: bps, ttl: opts.ttl || 180000,
      log: (...a) => console.log(`[${agentName(p.role, i)}]`, ...a)
    }))
  }
  const mason = new CivicMason({
    username: 'Mason', center: lay.center, lay,
    statePath, consolePath, host, port, version, blocksPerSec: bps, ttl: opts.ttl || 180000,
    log: (...a) => console.log('[Mason]', ...a)
  })
  const all = [mason, ...workers]

  // Launch staggered so the opped console (op/gamemode) lines don't collide.
  const runs = []
  for (const w of all) { runs.push(w.run()); await sleep(1100) }

  // Monitor until every job is resolved, or progress stalls / times out.
  let lastDone = -1, stalls = 0
  const startedAt = Date.now()
  while (true) {
    await sleep(4000)
    let p; try { p = progress(statePath) } catch (e) { log('progress read err', e.message); continue }
    const roles = Object.entries(p.crew).map(([k, c]) => `${k}:${c.building || c.role}=${c.placed || 0}${c.status === 'done' ? '✓' : ''}`).join(' ')
    log(`progress ${p.done}/${p.total}${p.skipped ? ` (+${p.skipped} skip)` : ''} | ${roles}`)
    if (p.total > 0 && p.done + p.skipped >= p.total && Object.keys(p.crew).length >= count + 1) { log('all plots + square resolved'); break }
    if (p.done === lastDone) { if (++stalls >= 30) { log('stalled — stopping'); break } }
    else { stalls = 0; lastDone = p.done }
    if (Date.now() - startedAt > (opts.maxMs || 20 * 60 * 1000)) { log('timeout — stopping'); break }
  }

  for (const w of all) w.stop()
  const settle = new Promise(res => setTimeout(() => res(null), 15000))
  const gathered = await Promise.race([Promise.all(runs), settle])
  const results = gathered || all.map(w => ({ name: w.name, placed: w.placed, skipped: w.skipped }))
  const final = progress(statePath)
  const summary = {
    goal, origin, plots: lay.plots.map(p => ({ key: p.key, role: p.role, origin: p.origin })),
    done: final.done, total: final.total, skipped: final.skipped,
    placed: results.reduce((s, r) => s + (r.placed || 0), 0),
    crew: results, statePath
  }
  log(`done ${summary.done}/${summary.total} placed=${summary.placed} skipped=${summary.skipped}`)
  for (const r of results) log(`  ${r.name} [${r.role || '?'}] ${r.building ? `built "${r.building}" ` : ''}placed ${r.placed} skipped ${r.skipped}${r.via ? ` (brain:${r.via})` : ''}`)
  return summary
}

function agentName(role, i) {
  const R = role.charAt(0).toUpperCase() + role.slice(1)
  return `${R}bot${i + 1}`
}

// ---- CLI ----
function parseArgs(argv) {
  const o = {}
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--count') o.count = +argv[++i]
    else if (a === '--goal') o.goal = argv[++i]
    else if (a === '--origin') { const [x, y, z] = argv[++i].split(',').map(Number); o.origin = { x, y, z } }
    else if (a === '--host') o.host = argv[++i]
    else if (a === '--port') o.port = +argv[++i]
    else if (a === '--version') o.version = argv[++i]
    else if (a === '--console') o.consolePath = path.resolve(argv[++i])
    else if (a === '--state') o.statePath = path.resolve(argv[++i])
    else if (a === '--bps') o.blocksPerSec = +argv[++i]
  }
  return o
}

if (require.main === module) {
  const o = parseArgs(process.argv)
  o.host = o.host || process.env.MC_HOST || 'localhost'
  o.port = o.port || +(process.env.MC_PORT || 25567)
  o.version = o.version || process.env.MC_VERSION || '1.21.6'
  o.consolePath = o.consolePath || process.env.GROK_CREW_CONSOLE
  // load .env if present (XAI/ANTHROPIC keys for the brains)
  try { require('fs').readFileSync(path.join(__dirname, '..', '.env'), 'utf8').split('\n').forEach(l => { const m = l.match(/^\s*([A-Z0-9_]+)\s*=\s*(.+?)\s*$/); if (m && !process.env[m[1]]) process.env[m[1]] = m[2] }) } catch {}
  runCrew(o)
    .then(s => { console.log('[crew] finished', JSON.stringify({ done: s.done, total: s.total, placed: s.placed, skipped: s.skipped })); process.exit(0) })
    .catch(e => { console.error('[crew] fatal', e); process.exit(1) })
}

module.exports = { runCrew, layoutVillage, genCivicJobs, CivicMason }
