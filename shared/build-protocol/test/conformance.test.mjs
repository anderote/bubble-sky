import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import {
  normalizeBuildState,
  normalizeWorkerProgress,
  validateBlueprint,
  validateBuildState,
} from '../index.mjs'
import { runBridgeDrone } from '../../../mcp/bridge-drone.mjs'

const require = createRequire(import.meta.url)
const grokState = require('../../../grok/lib/build/state.js')
const dir = path.dirname(fileURLToPath(import.meta.url))
const fixtures = path.join(dir, '..', 'fixtures')

test('protocol schemas are versioned JSON Schema 2020-12 documents', () => {
  for (const name of ['job-state.v1.schema.json', 'worker-progress.v1.schema.json', 'blueprint.v1.schema.json']) {
    const schema = JSON.parse(fs.readFileSync(path.join(dir, '..', name), 'utf8'))
    assert.equal(schema.$schema, 'https://json-schema.org/draft/2020-12/schema')
    assert.match(schema.$id, /\.v1\.schema\.json$/)
  }
})

test('legacy nested positions normalize to the canonical flat job shape', () => {
  const legacy = JSON.parse(fs.readFileSync(path.join(fixtures, 'job-state.legacy-pos.json'), 'utf8'))
  const state = normalizeBuildState(legacy)
  assert.equal(state.schemaVersion, 1)
  assert.deepEqual(state.jobs[0], {
    id: 'legacy-000001', x: 3, y: 70, z: 9,
    block: 'oak_planks', phase: 'walls', region: 'shell'
  })
  assert.equal('pos' in state.jobs[0], false)
})

test('canonical fixture validates and invalid coordinates fail closed', () => {
  const canonical = JSON.parse(fs.readFileSync(path.join(fixtures, 'job-state.v1.json'), 'utf8'))
  assert.deepEqual(validateBuildState(canonical), { ok: true, errors: [] })
  const invalid = structuredClone(canonical)
  invalid.jobs[0].x = 'somewhere'
  const result = validateBuildState(invalid)
  assert.equal(result.ok, false)
  assert.match(result.errors[0], /finite integer/)
})

test('semantic blueprint fixture conforms to the shared v1 surface', () => {
  const blueprint = JSON.parse(fs.readFileSync(path.join(dir, '../../../grok/lib/build/__tests__/fixture.blueprint.json'), 'utf8'))
  assert.deepEqual(validateBlueprint(blueprint), { ok: true, errors: [] })
})

test('region leases and worker claims remain distinct protocol layers', () => {
  const state = normalizeBuildState({
    taskId: 'claims-1', status: 'building', structure: 'keep', jobs: [],
    claims: { shell: { agent: 'Architect', ts: 1000, ttl: 60000 } }
  })
  const progress = normalizeWorkerProgress({
    taskId: 'claims-1', worker: 'Drone1', claimedIds: ['job-1'], doneIds: [], failedIds: []
  })
  assert.equal(state.claims.shell.agent, 'Architect')
  assert.deepEqual(progress.claimedIds, ['job-1'])
  assert.equal(state.claims.shell.claimedIds, undefined)
})

test('Grok state output is consumed by a Codex bridge drone with exact coordinates', async () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'build-protocol-'))
  const statePath = path.join(temp, 'state.json')
  const state = grokState.buildState({
    jobs: [
      { id: 'keep-1', pos: { x: 20, y: 71, z: -4 }, block: 'stone_bricks', phase: 'walls', region: 'shell' },
      { id: 'keep-2', pos: { x: 21, y: 71, z: -4 }, block: 'stone_bricks', phase: 'walls', region: 'shell' },
    ],
    origin: { x: 20, y: 71, z: -4 }, size: { x: 2, y: 1, z: 1 }
  }, { structure: 'keep' })
  grokState.writeState(state, statePath)

  const batches = []
  const bridge = {
    baseUrl: 'fake://bridge',
    health: async () => ({ mcVersion: 'test', modVersion: 'test', tps: 20 }),
    batch: async (ops) => { batches.push(ops); return { results: ops.map(() => ({ ok: true })) } },
    postStatus: async () => ({ ok: true }),
  }
  const result = await runBridgeDrone({ name: 'Drone1', statePath, bridge, once: true, batchSize: 8 })
  assert.equal(result.placed, 2)
  assert.deepEqual(batches, [[{
    op: 'fill', x1: 20, y1: 71, z1: -4,
    x2: 21, y2: 71, z2: -4, block: 'stone_bricks'
  }]])
  const persisted = JSON.parse(fs.readFileSync(statePath, 'utf8'))
  assert.equal(persisted.schemaVersion, 1)
  assert.equal(persisted.jobs[0].x, 20)
  assert.equal(persisted.jobs[0].pos, undefined)
})
