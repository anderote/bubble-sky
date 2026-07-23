#!/usr/bin/env node
// mcp/bridge-drone.mjs — a "bridge drone": a Codex swarm worker that builds over
// the mod-side HTTP bridge instead of Mineflayer.
//
// WHY: On a MODDED server, custom blocks/entities (towers, `towerdefense:acid`,
// …) make a Mineflayer / minecraft-data client throw `PartialReadError`, so the
// vanilla-protocol worker (codex-swarm-worker.mjs) can't connect. This drone is a
// DROP-IN alternative that speaks the AgentBridge HTTP API (see BRIDGE.md): it
// reads the SAME shared `.codex-runtime/swarm/state.json` the boss/planner
// compiles, claims jobs, and places them through `POST /batch`.
//
// It mirrors codex-swarm-worker.mjs's coordination EXACTLY so Codex can wire it
// into their launcher with no schema changes:
//   • state.json          {taskId,status:"building",structure,jobs:[{id,x,y,z,block,phase,worker?}]}
//   • progress/<name>.json {taskId,worker,doneIds[],failedIds[],claimedIds[]}
//   • global "done" set    = union of doneIds ∪ failedIds across ALL progress files
//   • jobs run in phase order; a drone only touches jobs assigned to it
//     (job.worker === name) or left unassigned (claimable by anyone)
//
// ADDED over the worker: an atomic, lockfile-guarded CLAIM step. The worker
// serialized drones implicitly by making them walk to each block; the bridge has
// no bodies, so drones would otherwise race for the same jobs. We guard the
// read-select-mark cycle with an O_EXCL lockfile and record claimedIds, so two
// drones split the work with zero collisions.
//
// PLACEMENT IS COMMAND-MODE: /setblock + /fill via /batch — i.e. godmode-style,
// appropriate for a modded server where Mineflayer can't connect. Physical /
// survival placement would need Carpet fake players (a later follow-up; see
// BRIDGE.md).
//
// CLI:   node mcp/bridge-drone.mjs --name Drone1 --state .codex-runtime/swarm/state.json
// API:   import { runBridgeDrone } from "./bridge-drone.mjs"
//        await runBridgeDrone({ name, statePath, bridge })

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { makeBridge } from "./bridge.mjs";
import { normalizeBuildState, normalizeWorkerProgress } from "../shared/build-protocol/index.mjs";

// ---------------------------------------------------------------------------
// Config / CLI parsing
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === "--name") out.name = argv[++i];
    else if (a === "--state") out.statePath = argv[++i];
    else if (a === "--batch-size") out.batchSize = Number(argv[++i]);
    else if (a === "--poll-ms") out.pollMs = Number(argv[++i]);
    else if (a === "--idle-exit-ms") out.idleExitMs = Number(argv[++i]);
    else if (a === "--once") out.once = true;
  }
  return out;
}

// ---------------------------------------------------------------------------
// Core: runBridgeDrone
// ---------------------------------------------------------------------------

/**
 * Run one bridge drone until the task is complete (or idle past idleExitMs).
 *
 * @param {object}  opts
 * @param {string}  opts.name         drone/agent name (its `worker` id + HUD name)
 * @param {string}  opts.statePath    path to the shared swarm state.json
 * @param {object} [opts.bridge]      a makeBridge() client (env-configured by default)
 * @param {number} [opts.batchSize]   max jobs claimed per cycle (default 64)
 * @param {number} [opts.pollMs]      idle poll interval (default 1200ms)
 * @param {number} [opts.idleExitMs]  exit after this long with no work left (default 15000ms)
 * @param {boolean}[opts.once]        drain one cycle of available work, then return
 */
export async function runBridgeDrone(opts) {
  const name = opts.name || "Drone1";
  const statePath = path.resolve(opts.statePath || ".codex-runtime/swarm/state.json");
  const bridge = opts.bridge || makeBridge();
  const batchSize = Number.isFinite(opts.batchSize) && opts.batchSize > 0 ? Math.floor(opts.batchSize) : 64;
  const pollMs = Number.isFinite(opts.pollMs) && opts.pollMs > 0 ? opts.pollMs : 1200;
  const idleExitMs = Number.isFinite(opts.idleExitMs) ? opts.idleExitMs : 15000;

  const runtimeDir = path.dirname(statePath);
  const progressDir = path.join(runtimeDir, "progress");
  const progressPath = path.join(progressDir, `${name}.json`);
  const lockPath = path.join(runtimeDir, "bridge-drone-claim.lock");

  const log = (msg) => console.log(`[${name}] ${msg}`);

  let stopping = false;
  const stop = () => {
    stopping = true;
  };
  process.on("SIGTERM", stop);
  process.on("SIGINT", stop);

  log(`bridge drone online → ${bridge.baseUrl} (state: ${statePath})`);
  try {
    const h = await bridge.health();
    log(`bridge healthy: mc ${h.mcVersion || "?"} mod ${h.modVersion || "?"} tps ${h.tps ?? "?"}`);
  } catch (e) {
    log(`WARN: bridge health check failed: ${e.message}`);
  }

  let activeTaskId = null;
  let idleSince = 0;
  let totalPlaced = 0;

  while (!stopping) {
    const state = readState(statePath);
    if (!state || state.status !== "building" || !Array.isArray(state.jobs) || state.jobs.length === 0) {
      await postStatusSafe(bridge, name, "idle", "waiting for a build", null);
      if (opts.once) break;
      await wait(pollMs);
      continue;
    }

    if (state.taskId !== activeTaskId) {
      activeTaskId = state.taskId;
      idleSince = 0;
      ensureProgress(progressPath, activeTaskId, name);
      log(`joined "${state.structure}" (${state.jobs.length} jobs, task ${state.taskId})`);
    }

    // --- atomically claim the next batch of my jobs ---
    const claimed = await withLock(lockPath, () => claimBatch({ state, name, progressDir, progressPath, batchSize }));

    if (claimed.length === 0) {
      // Nothing for me right now. Are we globally finished, or just waiting on
      // another drone's in-flight claims?
      const { done, total } = globalCounts(progressDir, state);
      await postStatusSafe(bridge, name, done >= total ? "idle" : "waiting", `${done}/${total} placed`, total ? done / total : 1);
      if (done >= total) {
        log(`task complete (${done}/${total} jobs across the swarm) — exiting`);
        break;
      }
      if (!idleSince) idleSince = Date.now();
      if (opts.once || (idleExitMs >= 0 && Date.now() - idleSince > idleExitMs)) {
        log(`no work available for me${opts.once ? "" : ` after ${idleExitMs}ms idle`} — exiting`);
        break;
      }
      await wait(pollMs);
      continue;
    }
    idleSince = 0;

    // --- place the claimed jobs over the bridge ---
    const phase = claimed[0].phase;
    const ops = jobsToBatchOps(claimed);
    let ok = false;
    try {
      const res = await bridge.batch(ops);
      const failures = (res.results || []).filter((rr) => rr && rr.ok === false);
      if (failures.length) throw new Error(`${failures.length}/${ops.length} ops failed: ${failures[0].error || "?"}`);
      ok = true;
    } catch (e) {
      log(`batch failed (${claimed.length} jobs, phase ${phase}): ${e.message}`);
    }

    if (ok) {
      markDone(progressPath, activeTaskId, name, claimed.map((j) => j.id));
      totalPlaced += claimed.length;
      log(`placed ${claimed.length} jobs [${phase}] via ${ops.length} ops (total ${totalPlaced})`);
    } else {
      markFailed(progressPath, activeTaskId, name, claimed.map((j) => j.id));
    }

    const { done, total } = globalCounts(progressDir, state);
    await postStatusSafe(bridge, name, "building", `${state.structure} · ${phase}`, total ? done / total : 0);
  }

  await postStatusSafe(bridge, name, "idle", `done, placed ${totalPlaced}`, 1);
  return { placed: totalPlaced };
}

// ---------------------------------------------------------------------------
// Claim selection (mirrors codex-swarm-worker.nextAvailableJobs, + claimedIds)
// ---------------------------------------------------------------------------

// Under the lock: compute the globally-taken set (done ∪ failed ∪ claimed by ANY
// drone), find the active phase, take up to batchSize jobs in that phase that are
// mine (job.worker === name) or unassigned, record them as claimed, return them.
function claimBatch({ state, name, progressDir, progressPath, batchSize }) {
  const taken = takenSet(progressDir, state.taskId); // includes other drones' claims

  // Active phase = phase of the first job (in state order) nobody has taken yet.
  const activePhase = state.jobs.find((j) => !taken.has(j.id))?.phase;
  if (activePhase == null) return [];

  const mine = state.jobs.filter(
    (j) =>
      j.phase === activePhase &&
      !taken.has(j.id) &&
      (j.worker === name || j.worker == null || j.worker === ""),
  );
  const batch = mine.slice(0, batchSize);
  if (batch.length === 0) return [];

  // Record the claim in MY progress file so other drones (reading it under their
  // own lock turn) won't re-take these jobs.
  const progress = readProgress(progressPath, state.taskId, name);
  progress.claimedIds = unique([...(progress.claimedIds || []), ...batch.map((j) => j.id)]);
  progress.updatedAt = new Date().toISOString();
  writeProgress(progressPath, progress);
  return batch;
}

// Union of done + failed across all progress files (a job leaves "in-flight" once
// it's done/failed; used for global completion accounting and the HUD).
function globalCounts(progressDir, state) {
  const finished = new Set();
  for (const p of allProgress(progressDir)) {
    if (p.taskId !== state.taskId) continue;
    for (const id of p.doneIds || []) finished.add(id);
    for (const id of p.failedIds || []) finished.add(id);
  }
  return { done: finished.size, total: state.jobs.length };
}

// Union of done ∪ failed ∪ claimed across all progress files — a job is "taken"
// (unavailable to claim) if any drone has claimed, done, or failed it.
function takenSet(progressDir, taskId) {
  const taken = new Set();
  for (const p of allProgress(progressDir)) {
    if (p.taskId !== taskId) continue;
    for (const id of p.doneIds || []) taken.add(id);
    for (const id of p.failedIds || []) taken.add(id);
    for (const id of p.claimedIds || []) taken.add(id);
  }
  return taken;
}

// ---------------------------------------------------------------------------
// Job → bridge ops: coalesce contiguous same-block runs into /fill, else
// /setblock (mirrors the worker's lineRuns compression).
// ---------------------------------------------------------------------------

function jobsToBatchOps(jobs) {
  const ops = [];
  for (const run of lineRuns(jobs)) {
    if (run.length === 1) {
      const j = run[0];
      ops.push({ op: "setblock", x: j.x, y: j.y, z: j.z, block: j.block });
    } else {
      const a = run[0];
      const b = run[run.length - 1];
      ops.push({ op: "fill", x1: a.x, y1: a.y, z1: a.z, x2: b.x, y2: b.y, z2: b.z, block: a.block });
    }
  }
  return ops;
}

function lineRuns(jobs) {
  const sorted = [...jobs].sort((a, b) => {
    if (a.block !== b.block) return String(a.block).localeCompare(String(b.block));
    if (a.y !== b.y) return a.y - b.y;
    if (a.z !== b.z) return a.z - b.z;
    return a.x - b.x;
  });
  const runs = [];
  let current = [];
  for (const job of sorted) {
    const prev = current[current.length - 1];
    if (prev && prev.block === job.block && prev.y === job.y && prev.z === job.z && prev.x + 1 === job.x) {
      current.push(job);
    } else {
      if (current.length) runs.push(current);
      current = [job];
    }
  }
  if (current.length) runs.push(current);
  return runs;
}

// ---------------------------------------------------------------------------
// Lockfile: atomic O_EXCL create, retried with backoff, stale-lock breaking.
// ---------------------------------------------------------------------------

async function withLock(lockPath, fn, { timeoutMs = 10000, staleMs = 30000 } = {}) {
  const start = Date.now();
  fs.mkdirSync(path.dirname(lockPath), { recursive: true });
  for (;;) {
    try {
      const fd = fs.openSync(lockPath, "wx"); // O_EXCL: fails if it already exists
      fs.writeSync(fd, `${process.pid} ${new Date().toISOString()}\n`);
      fs.closeSync(fd);
      break;
    } catch (e) {
      if (e.code !== "EEXIST") throw e;
      // Break a stale lock left by a crashed drone.
      try {
        const st = fs.statSync(lockPath);
        if (Date.now() - st.mtimeMs > staleMs) {
          fs.rmSync(lockPath, { force: true });
          continue;
        }
      } catch {
        // lock vanished between stat and now — retry the acquire
      }
      if (Date.now() - start > timeoutMs) throw new Error(`lock timeout: ${lockPath}`);
      await wait(25 + Math.floor(Math.random() * 50));
    }
  }
  try {
    return await fn();
  } finally {
    fs.rmSync(lockPath, { force: true });
  }
}

// ---------------------------------------------------------------------------
// Progress files (same shape as codex-swarm-worker, plus claimedIds)
// ---------------------------------------------------------------------------

function readState(statePath) {
  try {
    if (!fs.existsSync(statePath)) return null;
    return normalizeBuildState(JSON.parse(fs.readFileSync(statePath, "utf8")));
  } catch {
    return null;
  }
}

function readProgress(progressPath, taskId, name) {
  try {
    if (fs.existsSync(progressPath)) {
      const p = JSON.parse(fs.readFileSync(progressPath, "utf8"));
      if (p.taskId === taskId) return normalizeWorkerProgress({ ...p, worker: p.worker || name });
    }
  } catch {
    // fall through to a fresh record
  }
  return normalizeWorkerProgress({ taskId, worker: name, doneIds: [], failedIds: [], claimedIds: [] });
}

function writeProgress(progressPath, progress) {
  fs.mkdirSync(path.dirname(progressPath), { recursive: true });
  fs.writeFileSync(progressPath, `${JSON.stringify(normalizeWorkerProgress(progress), null, 2)}\n`);
}

function ensureProgress(progressPath, taskId, name) {
  const p = readProgress(progressPath, taskId, name);
  writeProgress(progressPath, {
    taskId,
    worker: name,
    doneIds: p.doneIds || [],
    failedIds: p.failedIds || [],
    claimedIds: p.claimedIds || [],
    updatedAt: new Date().toISOString(),
  });
}

function markDone(progressPath, taskId, name, ids) {
  const p = readProgress(progressPath, taskId, name);
  p.doneIds = unique([...(p.doneIds || []), ...ids]);
  p.claimedIds = (p.claimedIds || []).filter((id) => !ids.includes(id));
  p.updatedAt = new Date().toISOString();
  writeProgress(progressPath, p);
}

function markFailed(progressPath, taskId, name, ids) {
  const p = readProgress(progressPath, taskId, name);
  p.failedIds = unique([...(p.failedIds || []), ...ids]);
  p.claimedIds = (p.claimedIds || []).filter((id) => !ids.includes(id));
  p.updatedAt = new Date().toISOString();
  writeProgress(progressPath, p);
}

function allProgress(progressDir) {
  const out = [];
  try {
    for (const file of fs.readdirSync(progressDir)) {
      if (!file.endsWith(".json")) continue;
      try {
        out.push(JSON.parse(fs.readFileSync(path.join(progressDir, file), "utf8")));
      } catch {
        // skip a half-written file; it'll be readable next poll
      }
    }
  } catch {
    // progress dir not created yet
  }
  return out;
}

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

async function postStatusSafe(bridge, name, activity, detail, progress) {
  try {
    await bridge.postStatus(name, activity, detail, progress == null ? null : Number(progress.toFixed?.(3) ?? progress));
  } catch {
    // status is best-effort; never let the HUD stall the build
  }
}

function unique(values) {
  return [...new Set(values)];
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// ---------------------------------------------------------------------------
// CLI entry
// ---------------------------------------------------------------------------

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const args = parseArgs(process.argv.slice(2));
  if (!args.name) {
    console.error("usage: node mcp/bridge-drone.mjs --name <Drone1> [--state <path>] [--batch-size N] [--poll-ms N] [--idle-exit-ms N] [--once]");
    process.exit(2);
  }
  runBridgeDrone(args)
    .then((r) => {
      console.log(`[${args.name}] finished; placed ${r.placed} jobs`);
      process.exit(0);
    })
    .catch((e) => {
      console.error(`[${args.name}] fatal: ${e.stack || e.message}`);
      process.exit(1);
    });
}
