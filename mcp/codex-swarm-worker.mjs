#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
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
const announceOnJoin = process.env.CODEX_SWARM_ANNOUNCE_ON_JOIN !== "0";

const bot = mineflayer.createBot({ host, port, username, version, auth: "offline" });
bot.loadPlugin(pathfinder);

let activeTaskId = null;
let movements = null;
let stopping = false;

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
bot.on("end", (reason) => console.log("ended", reason));

async function workLoop() {
  while (!stopping) {
    const state = readState();
    if (!state || state.status !== "building") {
      await wait(1500);
      continue;
    }

    if (state.taskId !== activeTaskId) {
      activeTaskId = state.taskId;
      resetProgress(state.taskId);
      safeChat(`${username} joining ${state.structure} build`);
    }

    const progress = readProgress(state.taskId);
    const completed = new Set([...(progress.doneIds || []), ...(progress.failedIds || [])]);
    const nextJob = state.jobs.find((job) => job.worker === username && !completed.has(job.id));

    if (!nextJob) {
      writeProgress({ ...progress, activeJob: null, updatedAt: new Date().toISOString() });
      await wait(2500);
      continue;
    }

    await runJob(state, nextJob, progress);
  }
}

async function runJob(state, job, progress) {
  writeProgress({ ...progress, activeJob: `${job.phase}:${job.id}`, updatedAt: new Date().toISOString() });

  try {
    await moveNear(job.x, job.y, job.z, 5);
    await placeJob(job);
    markDone(state.taskId, job.id);
  } catch (error) {
    console.error(`${username}: failed ${job.id}: ${error.message}`);
    markFailed(state.taskId, job.id, error.message);
  }
}

async function placeJob(job) {
  if (commandBuild) {
    bot.chat(`/setblock ${job.x} ${job.y} ${job.z} ${job.block}`);
    await wait(120);
    return;
  }

  throw new Error("inventory build mode is not implemented yet; use CODEX_SWARM_BUILD_MODE=command");
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

function writeProgress(progress) {
  fs.mkdirSync(path.dirname(progressPath), { recursive: true });
  fs.writeFileSync(progressPath, `${JSON.stringify(progress, null, 2)}\n`);
}

function resetProgress(taskId) {
  writeProgress({ taskId, worker: username, doneIds: [], failedIds: [], activeJob: null, updatedAt: new Date().toISOString() });
}

function markDone(taskId, jobId) {
  const progress = readProgress(taskId);
  const doneIds = unique([...(progress.doneIds || []), jobId]);
  writeProgress({ ...progress, doneIds, activeJob: null, updatedAt: new Date().toISOString() });
}

function markFailed(taskId, jobId, reason) {
  const progress = readProgress(taskId);
  const failedIds = unique([...(progress.failedIds || []), jobId]);
  const failures = { ...(progress.failures || {}), [jobId]: reason };
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
