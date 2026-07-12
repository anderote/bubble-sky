// grok/lib/build/realize.js — GODMODE reference filler + exporters.
//
// Consumes the additive job list and places it with operator commands via
// `hands` (the same throttled /fill+/setblock backend the rest of Grok uses):
// contiguous same-block runs along x collapse into one /fill, everything else is
// a /setblock. This is ONE filler for the shared state file; Codex's drones are
// other fillers of the same jobs. Also exports the target as `.schem` and the
// job list as Codex's state.json.

const voxel = require('./voxel')
const state = require('./state')

// Place jobs through hands. Returns { fills, sets, ops }.
function realize(jobs, hands) {
  const runs = batchRuns(jobs)
  let fills = 0, sets = 0
  for (const r of runs) {
    if (r.x2 > r.x1) { hands.fillBox(r.x1, r.y, r.z, r.x2, r.y, r.z, r.block); fills++ }
    else { hands.setBlock(r.x1, r.y, r.z, r.block); sets++ }
  }
  return { fills, sets, ops: fills + sets, jobs: jobs.length }
}

// Collapse jobs (already phase/y/z/x sorted) into x-runs of the same block.
function batchRuns(jobs) {
  const runs = []
  let cur = null
  for (const j of jobs) {
    const { x, y, z } = j.pos
    if (cur && cur.block === j.block && cur.y === y && cur.z === z && x === cur.x2 + 1) {
      cur.x2 = x
    } else {
      cur = { block: j.block, x1: x, x2: x, y, z }
      runs.push(cur)
    }
  }
  return runs
}

// Re-diff after filling and return the still-missing jobs (for requeue). Needs a
// live readBlock; callers pass diff() + the same target.
function requeueFailures(target, readBlock, diffFn) {
  const again = diffFn(target, readBlock)
  return again.jobs
}

async function exportSchem(target, filePath, version = '1.21.6') {
  return voxel.writeSchemFile(target, filePath, version)
}

function writeState(diffResult, meta, filePath) {
  const st = state.buildState(diffResult, meta)
  state.writeState(st, filePath || state.STATE_PATH)
  return st
}

module.exports = { realize, batchRuns, requeueFailures, exportSchem, writeState }
