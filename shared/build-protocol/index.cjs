'use strict'

const SCHEMA_VERSION = 1

function normalizeJob(raw, index = 0, taskId = 'build') {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new TypeError(`jobs[${index}] must be an object`)
  const source = raw.pos && typeof raw.pos === 'object' ? raw.pos : raw
  const x = coordinate(source.x, `jobs[${index}].x`)
  const y = coordinate(source.y, `jobs[${index}].y`)
  const z = coordinate(source.z, `jobs[${index}].z`)
  const block = String(raw.block || '').trim()
  if (!block) throw new TypeError(`jobs[${index}].block must be a non-empty string`)
  const phase = String(raw.phase || 'walls').trim()
  if (!phase) throw new TypeError(`jobs[${index}].phase must be a non-empty string`)

  const job = {
    id: String(raw.id || `${taskId}-${String(index + 1).padStart(6, '0')}`),
    x, y, z, block, phase
  }
  if (raw.region != null && String(raw.region).trim()) job.region = String(raw.region)
  if (raw.worker != null && raw.worker !== '') job.worker = raw.worker
  return job
}

function normalizeBuildState(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new TypeError('build state must be an object')
  const taskId = String(raw.taskId || '').trim()
  if (!taskId) throw new TypeError('taskId must be a non-empty string')
  if (!Array.isArray(raw.jobs)) throw new TypeError('jobs must be an array')
  const normalized = {
    ...raw,
    schemaVersion: SCHEMA_VERSION,
    taskId,
    status: String(raw.status || 'building'),
    structure: String(raw.structure || raw.name || 'build'),
    jobs: raw.jobs.map((job, index) => normalizeJob(job, index, taskId)),
    claims: normalizeRegionClaims(raw.claims)
  }
  return normalized
}

function normalizeRegionClaims(raw) {
  if (raw == null) return {}
  if (typeof raw !== 'object' || Array.isArray(raw)) throw new TypeError('claims must be an object keyed by region')
  const claims = {}
  for (const [region, claim] of Object.entries(raw)) {
    if (!claim || typeof claim !== 'object') throw new TypeError(`claims.${region} must be an object`)
    const agent = String(claim.agent || '').trim()
    const ts = Number(claim.ts)
    const ttl = Number(claim.ttl)
    if (!agent || !Number.isFinite(ts) || !Number.isFinite(ttl) || ttl <= 0) {
      throw new TypeError(`claims.${region} requires agent, finite ts, and positive ttl`)
    }
    claims[region] = { agent, ts, ttl }
  }
  return claims
}

function normalizeWorkerProgress(raw) {
  if (!raw || typeof raw !== 'object') throw new TypeError('worker progress must be an object')
  const taskId = String(raw.taskId || '').trim()
  const worker = String(raw.worker || '').trim()
  if (!taskId || !worker) throw new TypeError('worker progress requires taskId and worker')
  return {
    ...raw,
    schemaVersion: SCHEMA_VERSION,
    taskId,
    worker,
    doneIds: stringIds(raw.doneIds),
    failedIds: stringIds(raw.failedIds),
    claimedIds: stringIds(raw.claimedIds)
  }
}

function validateBuildState(raw) {
  try {
    normalizeBuildState(raw)
    return { ok: true, errors: [] }
  } catch (error) {
    return { ok: false, errors: [error.message] }
  }
}

function validateBlueprint(raw) {
  const errors = []
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return { ok: false, errors: ['blueprint must be an object'] }
  if (raw.schemaVersion != null && raw.schemaVersion !== SCHEMA_VERSION) errors.push(`unsupported blueprint schemaVersion ${raw.schemaVersion}`)
  if (!String(raw.name || '').trim()) errors.push('name must be a non-empty string')
  if (!raw.palette || typeof raw.palette !== 'object' || Array.isArray(raw.palette)) errors.push('palette must be an object')
  if (!Array.isArray(raw.regions) || raw.regions.length === 0) errors.push('regions must be a non-empty array')
  else raw.regions.forEach((region, index) => {
    if (!region || typeof region !== 'object') errors.push(`regions[${index}] must be an object`)
    else {
      if (!String(region.id || '').trim()) errors.push(`regions[${index}].id is required`)
      if (!Array.isArray(region.components) || region.components.length === 0) errors.push(`regions[${index}].components must be non-empty`)
    }
  })
  return { ok: errors.length === 0, errors }
}

function coordinate(value, label) {
  const number = Number(value)
  if (!Number.isFinite(number) || !Number.isInteger(number)) throw new TypeError(`${label} must be a finite integer`)
  return number
}

function stringIds(value) {
  if (value == null) return []
  if (!Array.isArray(value)) throw new TypeError('progress id collections must be arrays')
  return [...new Set(value.map(String))]
}

module.exports = {
  SCHEMA_VERSION,
  normalizeJob,
  normalizeBuildState,
  normalizeRegionClaims,
  normalizeWorkerProgress,
  validateBuildState,
  validateBlueprint
}
