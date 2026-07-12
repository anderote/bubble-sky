#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";

const runtimeDir = process.env.CODEX_SWARM_RUNTIME || ".codex-runtime/swarm";
const statePath = path.join(runtimeDir, "state.json");
const progressDir = path.join(runtimeDir, "progress");
const archiveDir = path.join(runtimeDir, "archive");

if (!fs.existsSync(statePath)) {
  console.log("no swarm state");
  process.exit(0);
}

const state = JSON.parse(fs.readFileSync(statePath, "utf8"));
const progress = taskProgressSummary(state.taskId);
const total = Array.isArray(state.jobs) ? state.jobs.length : Number(state.jobCount || 0);
const remaining = Math.max(0, total - progress.done - progress.failed);

console.log(`${state.taskId || "unknown-task"}: ${state.status || "unknown"} (${state.structure || "unknown structure"})`);
console.log(`assigned: ${state.assignedTo || "unknown"} by ${state.assignedBy || "unknown"}${state.requestedBy ? ` for ${state.requestedBy}` : ""}`);
console.log(`jobs: ${progress.done}/${total} done, ${progress.failed} failed, ${remaining} remaining`);
if (progress.activeJob) console.log(`active: ${progress.activeWorker || "worker"} ${progress.activeJob}`);
if (state.origin) console.log(`origin: ${state.origin.x}, ${state.origin.y}, ${state.origin.z}`);
if (state.instructions) console.log(`instructions: ${state.instructions}`);
if (state.updatedAt) console.log(`updated: ${state.updatedAt}`);
console.log(`archives: ${archiveCount()}`);

function taskProgressSummary(taskId) {
  const done = new Set();
  const failed = new Set();
  let activeJob = "";
  let activeWorker = "";
  if (!taskId || !fs.existsSync(progressDir)) return { done: 0, failed: 0, activeJob, activeWorker };

  for (const file of fs.readdirSync(progressDir)) {
    if (!file.endsWith(".json")) continue;
    try {
      const entry = JSON.parse(fs.readFileSync(path.join(progressDir, file), "utf8"));
      if (entry.taskId !== taskId) continue;
      for (const id of entry.doneIds || []) done.add(id);
      for (const id of entry.failedIds || []) failed.add(id);
      if (entry.activeJob) {
        activeJob = entry.activeJob;
        activeWorker = entry.worker || file.replace(/\.json$/, "");
      }
    } catch (error) {
      console.error(`failed to read progress ${file}: ${error.message}`);
    }
  }

  return { done: done.size, failed: failed.size, activeJob, activeWorker };
}

function archiveCount() {
  try {
    return fs.existsSync(archiveDir)
      ? fs.readdirSync(archiveDir).filter((file) => file.endsWith(".json")).length
      : 0;
  } catch {
    return 0;
  }
}
