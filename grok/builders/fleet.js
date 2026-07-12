// grok/builders/fleet.js — spawns a fleet of "dumb" builder bots.
//
// Given a shared build state.json (the Codex job schema produced by
// lib/build/state.js), the fleet:
//   1. PARTITIONS the still-pending jobs into `count` balanced, spatially
//      contiguous column-buckets and tags each job with a `claim` key (a locked
//      write, so it never races a running builder);
//   2. spawns Builder1..N, each of which claims a DISTINCT bucket via the shared
//      `claims` lease and places its blocks as a real player (no /fill,/setblock);
//   3. monitors progress from the state file, reconnects any builder that drops
//      while work remains, and resolves once every job is done (or progress
//      stalls) — then disconnects all builders.
//
// CLI:   node builders/fleet.js --count 3 --state <path> [--console <fifo>]
// API:   const { runFleet } = require('./builders/fleet'); await runFleet({...})

const fs = require('fs')
const path = require('path')
const { Builder, withState, keyOf } = require('./builder')

const sleep = ms => new Promise(r => setTimeout(r, ms))

// Split pending jobs into `n` balanced, contiguous column-buckets → job.claim.
// Column-buckets keep each builder in its own vertical stack of columns, so every
// cell places against the block directly below it (which the same builder placed).
async function partitionClaims(statePath, n) {
  return withState(statePath, state => {
    const pend = state.jobs.filter(j => !j.done)
    const cols = new Map()
    for (const j of pend) { const k = j.pos.x + ',' + j.pos.z; if (!cols.has(k)) cols.set(k, []); cols.get(k).push(j) }
    const keys = [...cols.keys()].sort((a, b) => {
      const [ax, az] = a.split(',').map(Number), [bx, bz] = b.split(',').map(Number)
      return ax - bx || az - bz
    })
    const buckets = Math.max(1, Math.min(n, keys.length))
    const per = Math.ceil(keys.length / buckets)
    const counts = {}
    keys.forEach((ck, i) => {
      const bucket = 'fleet-part-' + Math.floor(i / per)
      for (const j of cols.get(ck)) { j.claim = bucket; counts[bucket] = (counts[bucket] || 0) + 1 }
    })
    // Clear any stale claim leases from a previous run.
    state.claims = {}
    return { buckets, counts, pending: pend.length }
  })
}

function progress(statePath) {
  const state = JSON.parse(fs.readFileSync(statePath, 'utf8'))
  const total = state.jobs.length
  const done = state.jobs.filter(j => j.done).length
  const skipped = state.jobs.filter(j => j.skip && !j.done).length
  const owners = {}
  for (const [k, c] of Object.entries(state.claims || {})) owners[k] = c.agent
  return { total, done, skipped, owners }
}

async function runFleet(opts = {}) {
  const statePath = opts.statePath || path.join(process.cwd(), 'build-out', 'state.json')
  const count = Math.max(1, opts.count || 3)
  const log = opts.log || ((...a) => console.log('[fleet]', ...a))
  if (!fs.existsSync(statePath)) throw new Error('state file not found: ' + statePath)

  const part = await partitionClaims(statePath, count)
  log(`partitioned ${part.pending} pending jobs into ${part.buckets} buckets:`, JSON.stringify(part.counts))

  const builders = []
  for (let i = 0; i < count; i++) {
    builders.push(new Builder({
      username: (opts.usernames && opts.usernames[i]) || `Builder${i + 1}`,
      statePath, consolePath: opts.consolePath,
      host: opts.host, port: opts.port, version: opts.version,
      blocksPerSec: opts.blocksPerSec || 6, log
    }))
  }

  // Launch (staggered so op/creative console lines don't collide).
  const runs = []
  for (const b of builders) { runs.push(b.run()); await sleep(900) }

  // Monitor until done or stalled.
  let lastDone = -1, stalls = 0
  const startedAt = Date.now()
  while (true) {
    await sleep(3000)
    let p
    try { p = progress(statePath) } catch (e) { log('progress read err', e.message); continue }
    log(`progress ${p.done}/${p.total}${p.skipped ? ` (+${p.skipped} skipped)` : ''} — owners ${JSON.stringify(p.owners)}`)
    if (p.done + p.skipped >= p.total) { log('all jobs resolved'); break }
    if (p.done === lastDone) { if (++stalls >= 20) { log('stalled — stopping'); break } }
    else { stalls = 0; lastDone = p.done }
    if (Date.now() - startedAt > (opts.maxMs || 15 * 60 * 1000)) { log('timeout — stopping'); break }
  }

  for (const b of builders) b.stop()
  // Bounded wait for builders to unwind; fall back to their live counters.
  const settle = new Promise(res => setTimeout(() => res(null), 12000))
  const gathered = await Promise.race([Promise.all(runs), settle])
  const results = gathered || builders.map(b => ({ name: b.name, placed: b.placed, skipped: b.skipped, owned: [...b.owned] }))
  const final = progress(statePath)
  const summary = {
    mode: 'fleet', done: final.done, total: final.total,
    placed: results.reduce((s, r) => s + r.placed, 0),
    skipped: results.reduce((s, r) => s + r.skipped, 0),
    builders: results,
    // compat with the godmode summary shape used by the pipeline logger:
    ops: results.reduce((s, r) => s + r.placed, 0), sets: results.reduce((s, r) => s + r.placed, 0), fills: 0
  }
  log(`done ${summary.done}/${summary.total} placed=${summary.placed} skipped=${summary.skipped}`)
  for (const r of results) log(`  ${r.name}: placed ${r.placed}, skipped ${r.skipped}, regions [${r.owned.join(', ')}]`)
  return summary
}

// ---- CLI ----
function parseArgs(argv) {
  const o = {}
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--count') o.count = +argv[++i]
    else if (a === '--state') o.statePath = path.resolve(argv[++i])
    else if (a === '--console') o.consolePath = path.resolve(argv[++i])
    else if (a === '--host') o.host = argv[++i]
    else if (a === '--port') o.port = +argv[++i]
    else if (a === '--version') o.version = argv[++i]
    else if (a === '--bps') o.blocksPerSec = +argv[++i]
  }
  return o
}

if (require.main === module) {
  const o = parseArgs(process.argv)
  o.host = o.host || process.env.MC_HOST || 'localhost'
  o.port = o.port || +(process.env.MC_PORT || 25565)
  o.version = o.version || process.env.MC_VERSION || '1.21.6'
  o.consolePath = o.consolePath || process.env.GROK_FLEET_CONSOLE || path.join(__dirname, '..', '..', 'server', 'console.in')
  runFleet(o).then(s => { console.log('[fleet] finished', JSON.stringify({ done: s.done, total: s.total, placed: s.placed, skipped: s.skipped })); process.exit(0) })
    .catch(e => { console.error('[fleet] fatal', e); process.exit(1) })
}

module.exports = { runFleet, partitionClaims, progress }
