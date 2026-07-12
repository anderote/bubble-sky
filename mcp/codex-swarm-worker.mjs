#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { Vec3 } from "vec3";
import mineflayer from "../mindcraft/upstream/node_modules/mineflayer/index.js";
import pathfinderPackage from "../mindcraft/upstream/node_modules/mineflayer-pathfinder/index.js";

const { pathfinder, Movements, goals } = pathfinderPackage;
const require = createRequire(import.meta.url);

const host = process.env.MINECRAFT_HOST || "192.168.86.188";
const port = Number(process.env.MINECRAFT_PORT || 25565);
const version = process.env.MINECRAFT_VERSION || "1.21.6";
const username = process.env.CODEX_BOT_USERNAME || "CodexDrone1";
const runtimeDir = process.env.CODEX_SWARM_RUNTIME || ".codex-runtime/swarm";
const statePath = path.join(runtimeDir, "state.json");
const progressPath = path.join(runtimeDir, "progress", `${username}.json`);
const playerMemoryPath = path.join(runtimeDir, "players.json");
const commandClockPath = path.join(runtimeDir, "command-clock.json");
const commandLockDir = path.join(runtimeDir, ".command-lock");
const buildMode = process.env.CODEX_SWARM_BUILD_MODE || "command";
const commandBuild = buildMode === "command";
const bridgeBuild = buildMode === "bridge";
const creativeBuild = buildMode === "creative";
const commandDelayMs = Number(process.env.CODEX_SWARM_COMMAND_DELAY_MS || 100);
const globalCommandDelayMs = Number(process.env.CODEX_SWARM_GLOBAL_COMMAND_DELAY_MS || 650);
const batchSize = Number(process.env.CODEX_SWARM_BATCH_SIZE || 32);
const verifyCommands = process.env.CODEX_SWARM_VERIFY_COMMANDS === "1";
const announceOnJoin = process.env.CODEX_SWARM_ANNOUNCE_ON_JOIN !== "0";
const reportEveryJobs = Number(process.env.CODEX_DRONE_REPORT_EVERY_JOBS || 0);
const reportMinIntervalMs = Number(process.env.CODEX_DRONE_REPORT_MIN_INTERVAL_MS || 90000);
const reportPhaseChanges = process.env.CODEX_DRONE_REPORT_PHASES === "1";
const reportTaskJoin = process.env.CODEX_DRONE_REPORT_TASK_JOIN === "1";
const spawnTimeoutMs = Number(process.env.CODEX_SPAWN_TIMEOUT_MS || 45000);

const bot = mineflayer.createBot({ host, port, username, version, auth: "offline" });
bot.loadPlugin(pathfinder);

const spawnWatchdog = setTimeout(() => {
  console.error(`${username}: did not spawn within ${spawnTimeoutMs}ms; exiting for wrapper retry`);
  process.exit(1);
}, spawnTimeoutMs);

let activeTaskId = null;
let movements = null;
let stopping = false;
let lastReportedPhase = null;
let lastReportAt = 0;
let commandRejection = null;
let Item = null;
let lastTeleportTaskId = null;

process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);

bot.once("spawn", async () => {
  clearTimeout(spawnWatchdog);
  movements = new Movements(bot);
  bot.pathfinder.setMovements(movements);
  Item = require("prismarine-item")(bot.registry);
  if (announceOnJoin) {
    safeChat(`${username} online for swarm work`);
  }
  console.log(`${username} joined ${host}:${port} at ${bot.entity.position}`);
  recordVisiblePlayers("spawn");
  setInterval(() => recordVisiblePlayers("scan"), 2500).unref();
  await workLoop();
});

bot.on("kicked", (reason) => console.log("kicked", reason));
bot.on("error", (error) => console.error("error", error));
bot.on("message", (message) => {
  const text = message.toString();
  if (/unknown or incomplete command|incorrect argument for command|<--\[HERE\]|permission|not allowed|cannot use/i.test(text)) {
    commandRejection = text;
  }
});
bot.on("end", (reason) => {
  console.log("ended", reason);
  if (!stopping) process.exit(1);
});

async function workLoop() {
  while (!stopping) {
    const state = readState();
    if (!state || (state.status !== "building" && state.status !== "recall")) {
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

    if (state.status === "recall") {
      await runRecall(state);
      continue;
    }

    const progress = readProgress(state.taskId);
    const allCompleted = readCompletedJobIds(state.taskId);
    const nextJobs = nextAvailableJobs(state, allCompleted);

    if (nextJobs.length === 0) {
      writeProgress({ ...progress, activeJob: null, updatedAt: new Date().toISOString() });
      markStateCompleteIfDone(state, allCompleted);
      await wait(2500);
      continue;
    }

    await runJobs(state, nextJobs, progress);
  }
}

async function runRecall(state) {
  const progress = readProgress(state.taskId);
  const targetName = state.target;
  const player = targetName ? bot.players[targetName]?.entity : null;
  if (player?.position) recordPlayer(targetName, player.position, "recall");
  const target = player?.position || state.targetPosition || rememberedPlayerPosition(targetName);

  if (!target) {
    writeProgress({ ...progress, activeJob: `recall:waiting-for-${targetName || "target"}` });
    await wait(1500);
    return;
  }

  writeProgress({ ...progress, activeJob: `recall:${targetName || "target"}` });
  await attemptTeleportForRecall(state, target);
  if (creativeBuild) {
    await bot.creative.startFlying();
    await bot.creative.flyTo(new Vec3(target.x, target.y + 1, target.z));
  } else {
    await moveNear(target.x, target.y, target.z, 4);
  }
  writeProgress({ ...readProgress(state.taskId), activeJob: null, recalledAt: new Date().toISOString() });
  await wait(2500);
}

async function attemptTeleportForRecall(state, target) {
  if (!state.teleport || lastTeleportTaskId === state.taskId) return;
  lastTeleportTaskId = state.taskId;
  commandRejection = null;
  if (state.target) {
    bot.chat(`/tp ${username} ${state.target}`);
  } else {
    bot.chat(`/tp ${username} ${Math.round(target.x)} ${Math.round(target.y)} ${Math.round(target.z)}`);
  }
  await wait(350);
  if (commandRejection) {
    console.log(`${username}: teleport unavailable: ${commandRejection}`);
    commandRejection = null;
  }
}

async function runJobs(state, jobs, progress) {
  const firstJob = jobs[0];
  const lastJob = jobs[jobs.length - 1];
  writeProgress({ ...progress, activeJob: `${firstJob.phase}:${firstJob.id}${jobs.length > 1 ? `..${lastJob.id}` : ""}`, updatedAt: new Date().toISOString() });
  maybeReportWork(state, firstJob, progress);

  try {
    if (!commandBuild && !bridgeBuild && !creativeBuild) {
      await moveNear(firstJob.x, firstJob.y, firstJob.z, 5);
    }
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
  if (commandBuild || bridgeBuild) {
    for (const run of lineRuns(jobs)) {
      const commandPrefix = bridgeBuild ? "/bubblesky fill" : "/fill";
      if (run.length === 1) {
        const job = run[0];
        if (bridgeBuild) {
          await sendCommand(`${commandPrefix} ${job.x} ${job.y} ${job.z} ${job.x} ${job.y} ${job.z} ${commandBlockName(job.block)}`);
        } else {
          await sendCommand(`/setblock ${job.x} ${job.y} ${job.z} ${job.block}`);
        }
      } else {
        const first = run[0];
        const last = run[run.length - 1];
        await sendCommand(`${commandPrefix} ${first.x} ${first.y} ${first.z} ${last.x} ${last.y} ${last.z} ${bridgeBuild ? commandBlockName(first.block) : first.block}`);
      }
      if (verifyCommands) await verifyRunBestEffort(run);
    }
    return;
  }

  if (creativeBuild) {
    await bot.creative.startFlying();
    for (const job of jobs) {
      await placeCreativeJob(job);
    }
    return;
  }

  throw new Error(`unsupported build mode ${buildMode}; use command or creative`);
}

async function placeCreativeJob(job) {
  await bot.creative.flyTo(new Vec3(job.x + 0.5, job.y + 1.5, job.z + 0.5));
  const current = bot.blockAt(new Vec3(job.x, job.y, job.z));
  if (!current) throw new Error(`cannot see block at ${job.x},${job.y},${job.z}`);

  const expected = commandBlockName(job.block);
  if (expected === "air") {
    if (current.name !== "air") await bot.dig(current, true);
    return;
  }

  if (current.name === expected) return;
  if (current.name !== "air") await bot.dig(current, true);

  const itemDef = bot.registry.itemsByName[expected];
  if (!itemDef) throw new Error(`no creative item for ${expected}`);
  const item = new Item(itemDef.id, 1, 0);
  await bot.creative.setInventorySlot(36, item, 0);
  await bot.equip(item, "hand");

  const placement = findPlacementReference(job);
  if (!placement) throw new Error(`no placement reference for ${expected} at ${job.x},${job.y},${job.z}`);
  await bot.placeBlock(placement.reference, placement.face);
}

function findPlacementReference(job) {
  const dest = new Vec3(job.x, job.y, job.z);
  const faces = [
    new Vec3(0, -1, 0),
    new Vec3(0, 1, 0),
    new Vec3(-1, 0, 0),
    new Vec3(1, 0, 0),
    new Vec3(0, 0, -1),
    new Vec3(0, 0, 1),
  ];
  for (const face of faces) {
    const reference = bot.blockAt(dest.minus(face));
    if (reference && reference.name !== "air" && reference.name !== "cave_air" && reference.name !== "void_air") {
      return { reference, face };
    }
  }
  return null;
}

async function sendCommand(command) {
  await waitForGlobalCommandSlot();
  commandRejection = null;
  bot.chat(command);
  await wait(Math.max(commandDelayMs, 200) + Math.floor(Math.random() * 80));
  if (commandRejection) {
    throw new Error(`command rejected by server: ${commandRejection}. Op ${username} or use a server-side build bridge.`);
  }
}

async function waitForGlobalCommandSlot() {
  const safeDelay = Number.isFinite(globalCommandDelayMs) && globalCommandDelayMs > 0 ? globalCommandDelayMs : 650;
  while (!stopping) {
    if (tryAcquireCommandLock()) {
      try {
        let lastAt = 0;
        try {
          lastAt = JSON.parse(fs.readFileSync(commandClockPath, "utf8")).lastAt || 0;
        } catch {
          lastAt = 0;
        }
        const waitMs = Math.max(0, safeDelay - (Date.now() - lastAt));
        if (waitMs > 0) await wait(waitMs);
        fs.writeFileSync(commandClockPath, `${JSON.stringify({ lastAt: Date.now(), worker: username }, null, 2)}\n`);
        return;
      } finally {
        releaseCommandLock();
      }
    }
    await wait(35 + Math.floor(Math.random() * 35));
  }
}

function tryAcquireCommandLock() {
  try {
    fs.mkdirSync(commandLockDir, { recursive: false });
    return true;
  } catch {
    try {
      const stat = fs.statSync(commandLockDir);
      if (Date.now() - stat.mtimeMs > 5000) fs.rmSync(commandLockDir, { recursive: true, force: true });
    } catch {
      // Another worker may have released it.
    }
    return false;
  }
}

function releaseCommandLock() {
  try {
    fs.rmSync(commandLockDir, { recursive: true, force: true });
  } catch {
    // Best effort.
  }
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

function writeState(state) {
  fs.mkdirSync(path.dirname(statePath), { recursive: true });
  const tempPath = `${statePath}.${process.pid}.tmp`;
  fs.writeFileSync(tempPath, `${JSON.stringify(state, null, 2)}\n`);
  fs.renameSync(tempPath, statePath);
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

function markStateCompleteIfDone(state, completed) {
  const jobs = Array.isArray(state.jobs) ? state.jobs : [];
  if (!jobs.length || completed.size < jobs.length) return;

  const current = readState();
  if (!current || current.taskId !== state.taskId || current.status !== "building") return;

  const summary = taskProgressSummary(state.taskId);
  writeState({
    ...current,
    status: summary.failed > 0 ? "complete_with_failures" : "complete",
    completedJobs: summary.done,
    failedJobs: summary.failed,
    completedAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  });
}

function taskProgressSummary(taskId) {
  const progressDir = path.dirname(progressPath);
  const done = new Set();
  const failed = new Set();
  try {
    if (!fs.existsSync(progressDir)) return { done: 0, failed: 0 };
    for (const file of fs.readdirSync(progressDir)) {
      if (!file.endsWith(".json")) continue;
      const entry = JSON.parse(fs.readFileSync(path.join(progressDir, file), "utf8"));
      if (entry.taskId !== taskId) continue;
      for (const id of entry.doneIds || []) done.add(id);
      for (const id of entry.failedIds || []) failed.add(id);
    }
  } catch (error) {
    console.error(`${username}: failed to summarize completion: ${error.message}`);
  }
  return { done: done.size, failed: failed.size };
}

function writeProgress(progress) {
  fs.mkdirSync(path.dirname(progressPath), { recursive: true });
  fs.writeFileSync(progressPath, `${JSON.stringify({ ...progress, position: currentPosition(), updatedAt: new Date().toISOString() }, null, 2)}\n`);
}

function recordVisiblePlayers(source) {
  for (const [playerName, player] of Object.entries(bot.players)) {
    recordPlayer(playerName, player?.entity?.position, source);
  }
}

function recordPlayer(playerName, position, source) {
  if (!playerName || !position) return;
  const normalized = playerName.toLowerCase();
  const memory = readPlayerMemory();
  memory.players = memory.players || {};
  const existing = memory.players[normalized];
  if (existing?.seenAt && Date.now() - Date.parse(existing.seenAt) < 500 && existing.observer !== username) return;
  memory.players[normalized] = {
    name: playerName,
    position: vectorPosition(position),
    seenAt: new Date().toISOString(),
    source,
    observer: username,
  };
  writePlayerMemory(memory);
}

function rememberedPlayerPosition(playerName) {
  if (!playerName) return null;
  const entry = readPlayerMemory().players?.[String(playerName).toLowerCase()];
  return entry?.position || null;
}

function readPlayerMemory() {
  try {
    if (!fs.existsSync(playerMemoryPath)) return { players: {} };
    return JSON.parse(fs.readFileSync(playerMemoryPath, "utf8"));
  } catch (error) {
    console.error(`${username}: failed to read player memory: ${error.message}`);
    return { players: {} };
  }
}

function writePlayerMemory(memory) {
  fs.mkdirSync(runtimeDir, { recursive: true });
  const tempPath = `${playerMemoryPath}.${process.pid}.tmp`;
  fs.writeFileSync(tempPath, `${JSON.stringify(memory, null, 2)}\n`);
  fs.renameSync(tempPath, playerMemoryPath);
}

function vectorPosition(position) {
  return { x: position.x, y: position.y, z: position.z };
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

function currentPosition() {
  const position = bot.entity?.position;
  if (!position) return null;
  return {
    x: Number(position.x.toFixed(2)),
    y: Number(position.y.toFixed(2)),
    z: Number(position.z.toFixed(2)),
    altitude: Math.floor(position.y),
  };
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
