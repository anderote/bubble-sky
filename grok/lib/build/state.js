// grok/lib/build/state.js — Layer-C JOB STATE (Codex's state.json, extended).
//
// Emits Codex's EXACT job-state shape so either Grok-godmode or Codex's drones
// can fill the same file, plus a `region` tag on every job and a `claims`
// lease map for multi-agent fill. Worker assignment mirrors Codex's compiler
// (chunked per phase, round-robin over workers).

const fs = require('fs')
const path = require('path')
const { slug } = require('./util')
const { normalizeBuildState } = require('../../../shared/build-protocol/index.cjs')

const STATE_PATH = path.join('.codex-runtime', 'swarm', 'state.json')

// Assemble a state object from a diff result (jobs already ordered + id'd).
function buildState(diffResult, meta = {}) {
  const structure = meta.structure || meta.name || 'build'
  const workers = meta.workers && meta.workers.length ? meta.workers : []
  const jobs = diffResult.jobs.map(j => ({ ...j }))
  if (workers.length) assignJobs(jobs, workers, meta.chunkSize || 32)
  return normalizeBuildState({
    taskId: `${slug(structure)}-${Date.now()}`,
    status: 'building',
    structure,
    origin: diffResult.origin,
    source: {
      type: 'blueprint',
      name: structure,
      size: diffResult.size,
      additive: true
    },
    workers,
    jobs,
    claims: meta.claims || {},
    createdAt: new Date().toISOString()
  })
}

// Round-robin workers across contiguous per-phase chunks (Codex parity).
function assignJobs(jobs, workers, chunkSize) {
  const safe = Number.isFinite(chunkSize) && chunkSize > 0 ? Math.floor(chunkSize) : 32
  let chunkIndex = 0, lastPhase = null
  for (const job of jobs) {
    if (job.phase !== lastPhase) { lastPhase = job.phase; chunkIndex = 0 }
    job.worker = workers[Math.floor(chunkIndex / safe) % workers.length]
    chunkIndex += 1
  }
  return jobs
}

// ---- multi-agent claim / lease helpers ----
function claimRegion(state, region, agent, ttl = 60000, now = Date.now()) {
  state.claims = state.claims || {}
  const existing = state.claims[region]
  if (existing && (now - existing.ts) < existing.ttl && existing.agent !== agent) return false
  state.claims[region] = { agent, ts: now, ttl }
  return true
}

function releaseExpired(state, now = Date.now()) {
  state.claims = state.claims || {}
  let n = 0
  for (const [region, c] of Object.entries(state.claims)) {
    if ((now - c.ts) >= c.ttl) { delete state.claims[region]; n++ }
  }
  return n
}

function releaseRegion(state, region, agent) {
  state.claims = state.claims || {}
  const c = state.claims[region]
  if (c && (!agent || c.agent === agent)) { delete state.claims[region]; return true }
  return false
}

function writeState(state, filePath = STATE_PATH) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true })
  fs.writeFileSync(filePath, JSON.stringify(normalizeBuildState(state), null, 2))
  return filePath
}

function readState(filePath = STATE_PATH) {
  try { return normalizeBuildState(JSON.parse(fs.readFileSync(filePath, 'utf8'))) } catch { return null }
}

module.exports = {
  STATE_PATH, buildState, assignJobs, claimRegion, releaseExpired, releaseRegion, writeState, readState
}
