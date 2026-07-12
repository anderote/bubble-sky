#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { Vec3 } from "vec3";
import mineflayer from "../mindcraft/upstream/node_modules/mineflayer/index.js";
import pathfinderPackage from "../mindcraft/upstream/node_modules/mineflayer-pathfinder/index.js";

const { pathfinder, Movements, goals } = pathfinderPackage;

const host = process.env.MINECRAFT_HOST || "192.168.86.188";
const port = Number(process.env.MINECRAFT_PORT || 25565);
const version = process.env.MINECRAFT_VERSION || "1.21.6";
const username = process.env.CODEX_BOT_USERNAME || "CodexDrone1";
const runtimeDir = process.env.CODEX_SWARM_RUNTIME || ".codex-runtime/swarm";
const statePath = path.join(runtimeDir, "state.json");
const progressPath = path.join(runtimeDir, "progress", `${username}.json`);
const commandBuild = process.env.CODEX_SWARM_BUILD_MODE !== "inventory";
const commandDelayMs = Number(process.env.CODEX_SWARM_COMMAND_DELAY_MS || 250);
const batchSize = Number(process.env.CODEX_SWARM_BATCH_SIZE || 32);
const verifyCommands = process.env.CODEX_SWARM_VERIFY_COMMANDS === "1";
const announceOnJoin = process.env.CODEX_SWARM_ANNOUNCE_ON_JOIN !== "0";
const reportEveryJobs = Number(process.env.CODEX_DRONE_REPORT_EVERY_JOBS || 80);
const reportMinIntervalMs = Number(process.env.CODEX_DRONE_REPORT_MIN_INTERVAL_MS || 90000);
const reportPhaseChanges = process.env.CODEX_DRONE_REPORT_PHASES === "1";
const reportTaskJoin = process.env.CODEX_DRONE_REPORT_TASK_JOIN === "1";

const bot = mineflayer.createBot({ host, port, username, version, auth: "offline" });
bot.loadPlugin(pathfinder);

let activeTaskId = null;
let movements = null;
let stopping = false;
let lastReportedPhase = null;
let lastReportAt = 0;

process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);

bot.once("spawn", async () => {
  movements = new Movements(bot);
  bot.pathfinder.setMovements(movements);
  if (announceOnJoin) {
    safeChat(`${username} online for swarm work`);
  }
  console.log(`${username} joined ${host}:${port} at ${bot.entity.position}`);
  await workLoop();
});

bot.on("kicked", (reason) => console.log("kicked", reason));
bot.on("error", (error) => console.error("error", error));
bot.on("end", (reason) => {
  console.log("ended", reason);
  if (!stopping) process.exit(1);
});

async function workLoop() {
  while (!stopping) {
    const state = readState();
    if (!state || state.status !== "building") {
      await wait(1500);
      continue;
    }

    if (state.taskId !== activeTaskId) {
      activeTaskId = state.taskId;
      lastReportedPhase = null;
      lastReportAt = 0;
      ensureProgress(state.taskId);
      if (reportTaskJoin) {
        safeChat(`${username} joining ${state.structure} build`);
      }
    }

    const progress = readProgress(state.taskId);
    const allCompleted = readCompletedJobIds(state.taskId);
    const nextJobs = nextAvailableJobs(state, allCompleted);

    if (nextJobs.length === 0) {
      writeProgress({ ...progress, activeJob: null, updatedAt: new Date().toISOString() });
      await wait(2500);
      continue;
    }

    await runJobs(state, nextJobs, progress);
  }
}

async function runJobs(state, jobs, progress) {
  const firstJob = jobs[0];
  const lastJob = jobs[jobs.length - 1];
  writeProgress({ ...progress, activeJob: `${firstJob.phase}:${firstJob.id}${jobs.length > 1 ? `..${lastJob.id}` : ""}`, updatedAt: new Date().toISOString() });
  maybeReportWork(state, firstJob, progress);

  try {
    await moveNear(firstJob.x, firstJob.y, firstJob.z, 5);
    await placeJobs(jobs);
    markDoneMany(state.taskId, jobs.map((job) => job.id));
  } catch (error) {
    console.error(`${username}: failed ${firstJob.id}..${lastJob.id}: ${error.message}`);
    markFailedMany(state.taskId, jobs.map((job) => job.id), error.message);
  }
}

function maybeReportWork(state, job, progress) {
  const doneCount = progress.doneIds?.length || 0;
  const phaseChanged = reportPhaseChanges && job.phase !== lastReportedPhase;
  const checkpoint = reportEveryJobs > 0 && doneCount > 0 && doneCount % reportEveryJobs === 0;
  const due = Date.now() - lastReportAt >= reportMinIntervalMs;
  if (!due || (!phaseChanged && !checkpoint)) return;

  lastReportedPhase = job.phase;
  lastReportAt = Date.now();
  safeChat(`${username}: ${state.structure} ${job.phase} pass, ${doneCount} jobs done.`);
}

async function placeJobs(jobs) {
  if (commandBuild) {
    for (const run of lineRuns(jobs)) {
      if (run.length === 1) {
        const job = run[0];
        bot.chat(`/setblock ${job.x} ${job.y} ${job.z} ${job.block}`);
      } else {
        const first = run[0];
        const last = run[run.length - 1];
        bot.chat(`/fill ${first.x} ${first.y} ${first.z} ${last.x} ${last.y} ${last.z} ${first.block}`);
      }
      await wait(commandDelayMs + Math.floor(Math.random() * 75));
      if (verifyCommands) await verifyRunBestEffort(run);
    }
    return;
  }

  throw new Error("inventory build mode is not implemented yet; use CODEX_SWARM_BUILD_MODE=command");
}

async function verifyRunBestEffort(run) {
  const first = run[0];
  const last = run[run.length - 1];
  if (await runMatches(first, last)) return;

  if (run.length === 1) {
    bot.chat(`/setblock ${first.x} ${first.y} ${first.z} ${first.block}`);
  } else {
    bot.chat(`/fill ${first.x} ${first.y} ${first.z} ${last.x} ${last.y} ${last.z} ${first.block}`);
  }
  await wait(commandDelayMs + 150);

  if (!(await runMatches(first, last))) {
    console.log(`${username}: verification inconclusive for ${first.block} at ${first.x},${first.y},${first.z}${run.length > 1 ? `..${last.x},${last.y},${last.z}` : ""}`);
  }
}

async function runMatches(first, last) {
  const expected = commandBlockName(first.block);
  return (await blockMatches(first, expected)) && (await blockMatches(last, expected));
}

async function blockMatches(job, expectedName) {
  const block = bot.blockAt(new Vec3(job.x, job.y, job.z));
  return block?.name === expectedName;
}

function commandBlockName(block) {
  return String(block).replace(/^minecraft:/, "").replace(/\[.*$/, "");
}

function lineRuns(jobs) {
  const sorted = [...jobs].sort((a, b) => {
    if (a.block !== b.block) return a.block.localeCompare(b.block);
    if (a.y !== b.y) return a.y - b.y;
    if (a.z !== b.z) return a.z - b.z;
    return a.x - b.x;
  });
  const runs = [];
  let current = [];

  for (const job of sorted) {
    const previous = current[current.length - 1];
    if (
      previous &&
      previous.block === job.block &&
      previous.y === job.y &&
      previous.z === job.z &&
      previous.x + 1 === job.x
    ) {
      current.push(job);
    } else {
      if (current.length) runs.push(current);
      current = [job];
    }
  }

  if (current.length) runs.push(current);
  return runs;
}

async function moveNear(x, y, z, range) {
  bot.pathfinder.setGoal(new goals.GoalNear(Math.floor(x), Math.floor(y), Math.floor(z), range));
  await waitForPath(10000);
}

function waitForPath(timeoutMs) {
  return new Promise((resolve) => {
    const done = () => {
      clearTimeout(timeout);
      bot.removeListener("goal_reached", done);
      bot.removeListener("path_stop", done);
      resolve();
    };
    const timeout = setTimeout(done, timeoutMs);
    bot.once("goal_reached", done);
    bot.once("path_stop", done);
  });
}

function readState() {
  try {
    if (!fs.existsSync(statePath)) return null;
    return JSON.parse(fs.readFileSync(statePath, "utf8"));
  } catch (error) {
    console.error(`${username}: failed to read state: ${error.message}`);
    return null;
  }
}

function readProgress(taskId) {
  try {
    if (!fs.existsSync(progressPath)) return { taskId, worker: username, doneIds: [], failedIds: [] };
    const progress = JSON.parse(fs.readFileSync(progressPath, "utf8"));
    if (progress.taskId !== taskId) return { taskId, worker: username, doneIds: [], failedIds: [] };
    return progress;
  } catch (error) {
    console.error(`${username}: failed to read progress: ${error.message}`);
    return { taskId, worker: username, doneIds: [], failedIds: [] };
  }
}

function readCompletedJobIds(taskId) {
  const progressDir = path.dirname(progressPath);
  const completed = new Set();
  try {
    if (!fs.existsSync(progressDir)) return completed;
    for (const file of fs.readdirSync(progressDir)) {
      if (!file.endsWith(".json")) continue;
      const entry = JSON.parse(fs.readFileSync(path.join(progressDir, file), "utf8"));
      if (entry.taskId !== taskId) continue;
      for (const id of entry.doneIds || []) completed.add(id);
      for (const id of entry.failedIds || []) completed.add(id);
    }
  } catch (error) {
    console.error(`${username}: failed to read global progress: ${error.message}`);
  }
  return completed;
}

function nextAvailableJobs(state, completed) {
  const activePhase = state.jobs.find((job) => !completed.has(job.id))?.phase;
  if (!activePhase) return [];
  const safeBatchSize = Number.isFinite(batchSize) && batchSize > 0 ? Math.floor(batchSize) : 32;
  return state.jobs
    .filter((job) => job.phase === activePhase && job.worker === username && !completed.has(job.id))
    .slice(0, safeBatchSize);
}

function writeProgress(progress) {
  fs.mkdirSync(path.dirname(progressPath), { recursive: true });
  fs.writeFileSync(progressPath, `${JSON.stringify(progress, null, 2)}\n`);
}

function ensureProgress(taskId) {
  const progress = readProgress(taskId);
  writeProgress({
    taskId,
    worker: username,
    doneIds: progress.doneIds || [],
    failedIds: progress.failedIds || [],
    failures: progress.failures || {},
    activeJob: null,
    updatedAt: new Date().toISOString(),
  });
}

function markDone(taskId, jobId) {
  markDoneMany(taskId, [jobId]);
}

function markDoneMany(taskId, jobIds) {
  const progress = readProgress(taskId);
  const doneIds = unique([...(progress.doneIds || []), ...jobIds]);
  writeProgress({ ...progress, doneIds, activeJob: null, updatedAt: new Date().toISOString() });
}

function markFailed(taskId, jobId, reason) {
  markFailedMany(taskId, [jobId], reason);
}

function markFailedMany(taskId, jobIds, reason) {
  const progress = readProgress(taskId);
  const failedIds = unique([...(progress.failedIds || []), ...jobIds]);
  const failures = { ...(progress.failures || {}) };
  for (const jobId of jobIds) failures[jobId] = reason;
  writeProgress({ ...progress, failedIds, failures, activeJob: null, updatedAt: new Date().toISOString() });
}

function unique(values) {
  return [...new Set(values)];
}

function safeChat(message) {
  try {
    bot.chat(message.slice(0, 240));
  } catch (error) {
    console.log(`${username}: failed to chat: ${error.message}`);
  }
}

function clearControls() {
  for (const control of ["forward", "back", "left", "right", "jump", "sprint", "sneak"]) {
    bot.setControlState(control, false);
  }
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function shutdown() {
  stopping = true;
  clearControls();
  try {
    bot.end();
  } finally {
    setTimeout(() => process.exit(0), 250);
  }
}
