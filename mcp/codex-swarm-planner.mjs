#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import mineflayer from "../mindcraft/upstream/node_modules/mineflayer/index.js";

const host = process.env.MINECRAFT_HOST || "192.168.86.188";
const port = Number(process.env.MINECRAFT_PORT || 25565);
const version = process.env.MINECRAFT_VERSION || "1.21.6";
const username = process.env.CODEX_SWARM_BOSS || "CodexBoss";
const workerPrefix = process.env.CODEX_SWARM_PREFIX || "CodexDrone";
const workerCount = Number(process.env.CODEX_SWARM_COUNT || 4);
const runtimeDir = process.env.CODEX_SWARM_RUNTIME || ".codex-runtime/swarm";
const statePath = path.join(runtimeDir, "state.json");
const announceOnJoin = process.env.CODEX_SWARM_ANNOUNCE_ON_JOIN !== "0";
const reportIntervalMs = Number(process.env.CODEX_SWARM_REPORT_INTERVAL_MS || 60000);
const milestoneStep = Number(process.env.CODEX_SWARM_REPORT_MILESTONE || 25);

const bot = mineflayer.createBot({ host, port, username, version, auth: "offline" });
let lastReportAt = 0;
let lastReportTaskId = null;
let lastMilestone = -1;

bot.once("spawn", () => {
  if (announceOnJoin) {
    say("CodexBoss online. Use @swarm build cabin or @swarm build watchtower.");
  }
  console.log(`${username} joined ${host}:${port} at ${bot.entity.position}`);
  setInterval(reportProgress, 10000).unref();
});

bot.on("chat", async (speaker, message) => {
  if (speaker === bot.username) return;

  const command = addressedCommand(message);
  if (!command) return;

  console.log(`<${speaker}> ${message}`);
  try {
    await runCommand(speaker, command);
  } catch (error) {
    console.error(error);
    say(`plan failed: ${error.message}`);
  }
});

bot.on("kicked", (reason) => console.log("kicked", reason));
bot.on("error", (error) => console.error("error", error));
bot.on("end", (reason) => console.log("ended", reason));

async function runCommand(speaker, commandText) {
  const command = compact(commandText).replace(/[.!?]+$/g, "");
  const lower = command.toLowerCase();

  if (!command || lower === "help") {
    say("swarm: build cabin [at x y z] | build watchtower [at x y z] | status | cancel");
    return;
  }

  if (lower === "status" || lower === "what are you doing") {
    showStatus();
    return;
  }

  if (lower === "cancel" || lower === "stop") {
    writeState({ status: "cancelled", taskId: `cancel-${Date.now()}`, jobs: [], workers: workerNames() });
    say("swarm task cancelled");
    return;
  }

  const buildMatch = command.match(/^build\s+(cabin|watchtower|tower)(?:\s+(?:at|near)\s+(.+))?$/i);
  if (!buildMatch) {
    say(`I do not know "${command}". Try @swarm build cabin or @swarm build watchtower.`);
    return;
  }

  const structure = buildMatch[1].toLowerCase() === "tower" ? "watchtower" : buildMatch[1].toLowerCase();
  const origin = parseOrigin(buildMatch[2]) || defaultOrigin();
  const plan = createPlan(structure, origin);
  writeState(plan);
  writeWorkerProgressFiles(plan.taskId);
  lastReportTaskId = plan.taskId;
  lastMilestone = 0;
  lastReportAt = Date.now();

  say(`planned ${structure} at ${formatOrigin(origin)}: ${plan.jobs.length} block jobs for ${workerCount} drones`);
  say("Drones will move near assigned jobs and place with /setblock. Op CodexBoss/CodexDroneN if blocks do not appear.");
}

function showStatus() {
  const state = readState();
  if (!state?.taskId || state.status === "cancelled") {
    say("swarm is idle");
    return;
  }

  const progress = readAllProgress(state.taskId);
  const done = new Set(progress.flatMap((entry) => entry.doneIds || []));
  const failed = progress.reduce((sum, entry) => sum + (entry.failedIds?.length || 0), 0);
  const active = progress
    .filter((entry) => entry.activeJob)
    .map((entry) => `${entry.worker}:${entry.activeJob}`)
    .join(", ");

  say(`${state.structure} ${state.status}: ${done.size}/${state.jobs.length} done, ${failed} failed${active ? `; active ${active}` : ""}`);
}

function reportProgress() {
  const state = readState();
  if (!state?.taskId || state.status !== "building" || state.jobs.length === 0) return;

  const summary = progressSummary(state);
  if (state.taskId !== lastReportTaskId) {
    lastReportTaskId = state.taskId;
    lastMilestone = -1;
    lastReportAt = 0;
  }

  if (summary.done >= state.jobs.length) {
    writeState({ ...state, status: "complete", completedAt: new Date().toISOString() });
    say(`${state.structure} complete: ${summary.done}/${state.jobs.length} jobs finished${summary.failed ? `, ${summary.failed} failed` : ""}.`);
    return;
  }

  const safeMilestoneStep = Number.isFinite(milestoneStep) && milestoneStep > 0 ? milestoneStep : 25;
  const milestone = Math.floor(summary.percent / safeMilestoneStep) * safeMilestoneStep;
  const due = Date.now() - lastReportAt >= reportIntervalMs;
  if (!due && milestone <= lastMilestone) return;

  lastReportAt = Date.now();
  lastMilestone = Math.max(lastMilestone, milestone);
  say(`${state.structure} progress: ${summary.done}/${state.jobs.length} jobs (${summary.percent}%), ${summary.failed} failed; ${summary.phaseText}.`);
}

function progressSummary(state) {
  const progress = readAllProgress(state.taskId);
  const doneIds = new Set(progress.flatMap((entry) => entry.doneIds || []));
  const failed = progress.reduce((sum, entry) => sum + (entry.failedIds?.length || 0), 0);
  const activePhases = progress
    .map((entry) => entry.activeJob?.split(":")[0])
    .filter(Boolean);
  const phaseCounts = countBy(activePhases);
  const phaseText = Object.keys(phaseCounts).length
    ? Object.entries(phaseCounts).map(([phase, count]) => `${count} drone${count === 1 ? "" : "s"} on ${phase}`).join(", ")
    : "drones waiting for assignments";

  return {
    done: doneIds.size,
    failed,
    percent: Math.floor((doneIds.size / state.jobs.length) * 100),
    phaseText,
  };
}

function createPlan(structure, origin) {
  const workers = workerNames();
  const jobs = structure === "cabin" ? cabinJobs(origin) : watchtowerJobs(origin);

  jobs.forEach((job, index) => {
    job.id = `${structure}-${String(index + 1).padStart(4, "0")}`;
    job.worker = workers[index % workers.length];
  });

  return {
    taskId: `${structure}-${Date.now()}`,
    status: "building",
    structure,
    origin,
    workers,
    jobs,
    createdAt: new Date().toISOString(),
  };
}

function cabinJobs(origin) {
  const jobs = [];
  const width = 7;
  const depth = 7;
  const wallHeight = 4;
  const x0 = origin.x - Math.floor(width / 2);
  const z0 = origin.z - Math.floor(depth / 2);
  const x1 = x0 + width - 1;
  const z1 = z0 + depth - 1;
  const y = origin.y;

  for (let x = x0 - 1; x <= x1 + 1; x += 1) {
    for (let z = z0 - 1; z <= z1 + 1; z += 1) {
      jobs.push(job(x, y, z, "air", "clear"));
      jobs.push(job(x, y + 1, z, "air", "clear"));
      jobs.push(job(x, y + 2, z, "air", "clear"));
      jobs.push(job(x, y + 3, z, "air", "clear"));
      jobs.push(job(x, y + 4, z, "air", "clear"));
    }
  }

  for (let x = x0; x <= x1; x += 1) {
    for (let z = z0; z <= z1; z += 1) {
      jobs.push(job(x, y - 1, z, "oak_planks", "floor"));
    }
  }

  for (let level = 0; level < wallHeight; level += 1) {
    for (let x = x0; x <= x1; x += 1) {
      jobs.push(job(x, y + level, z0, "oak_planks", "wall"));
      jobs.push(job(x, y + level, z1, "oak_planks", "wall"));
    }
    for (let z = z0 + 1; z <= z1 - 1; z += 1) {
      jobs.push(job(x0, y + level, z, "oak_planks", "wall"));
      jobs.push(job(x1, y + level, z, "oak_planks", "wall"));
    }
  }

  for (const [x, z] of [[x0, z0], [x1, z0], [x0, z1], [x1, z1]]) {
    for (let level = 0; level < wallHeight; level += 1) {
      jobs.push(job(x, y + level, z, "oak_log", "posts"));
    }
  }

  for (let dx = -1; dx <= width; dx += 1) {
    jobs.push(job(x0 + dx, y + wallHeight, z0 - 1, "oak_stairs[facing=south]", "roof"));
    jobs.push(job(x0 + dx, y + wallHeight, z1 + 1, "oak_stairs[facing=north]", "roof"));
    jobs.push(job(x0 + dx, y + wallHeight + 1, z0, "oak_planks", "roof"));
    jobs.push(job(x0 + dx, y + wallHeight + 1, z1, "oak_planks", "roof"));
  }
  for (let z = z0; z <= z1; z += 1) {
    jobs.push(job(x0 - 1, y + wallHeight, z, "oak_stairs[facing=east]", "roof"));
    jobs.push(job(x1 + 1, y + wallHeight, z, "oak_stairs[facing=west]", "roof"));
  }

  const doorX = origin.x;
  jobs.push(job(doorX, y, z0, "air", "door"));
  jobs.push(job(doorX, y + 1, z0, "air", "door"));
  jobs.push(job(doorX, y + 2, z0, "oak_planks", "lintel"));
  for (const window of [
    [x0, y + 1, origin.z],
    [x1, y + 1, origin.z],
    [origin.x - 2, y + 1, z1],
    [origin.x + 2, y + 1, z1],
  ]) {
    jobs.push(job(window[0], window[1], window[2], "glass_pane", "windows"));
  }

  return sortJobs(jobs);
}

function watchtowerJobs(origin) {
  const jobs = [];
  const radius = 3;
  const height = 8;
  const y = origin.y;

  for (let x = origin.x - radius - 1; x <= origin.x + radius + 1; x += 1) {
    for (let z = origin.z - radius - 1; z <= origin.z + radius + 1; z += 1) {
      for (let dy = 0; dy <= height + 2; dy += 1) {
        jobs.push(job(x, y + dy, z, "air", "clear"));
      }
    }
  }

  for (const [x, z] of [
    [origin.x - radius, origin.z - radius],
    [origin.x + radius, origin.z - radius],
    [origin.x - radius, origin.z + radius],
    [origin.x + radius, origin.z + radius],
  ]) {
    for (let dy = 0; dy <= height; dy += 1) {
      jobs.push(job(x, y + dy, z, "spruce_log", "supports"));
    }
  }

  for (let x = origin.x - radius; x <= origin.x + radius; x += 1) {
    for (let z = origin.z - radius; z <= origin.z + radius; z += 1) {
      const edge = x === origin.x - radius || x === origin.x + radius || z === origin.z - radius || z === origin.z + radius;
      jobs.push(job(x, y + height, z, edge ? "spruce_planks" : "oak_planks", "platform"));
      if (edge && (x + z) % 2 === 0) jobs.push(job(x, y + height + 1, z, "cobblestone_wall", "railing"));
    }
  }

  for (let dy = 1; dy < height; dy += 1) {
    jobs.push(job(origin.x, y + dy, origin.z - radius, "ladder[facing=south]", "ladder"));
  }
  jobs.push(job(origin.x, y, origin.z, "oak_planks", "base"));

  return sortJobs(jobs);
}

function job(x, y, z, block, phase) {
  return { x, y, z, block, phase };
}

function sortJobs(jobs) {
  const phaseOrder = ["clear", "floor", "base", "supports", "posts", "wall", "platform", "ladder", "windows", "door", "lintel", "roof", "railing"];
  return jobs.sort((a, b) => phaseOrder.indexOf(a.phase) - phaseOrder.indexOf(b.phase));
}

function addressedCommand(message) {
  const trimmed = message.trim();
  const handles = [username, `@${username}`, "swarm", "@swarm", "boss", "@boss", "codexboss", "@codexboss"];
  for (const handle of handles) {
    const pattern = new RegExp(`^${escapeRegex(handle)}\\s*(?:[:,>-])?\\s*(.*)$`, "i");
    const match = trimmed.match(pattern);
    if (match) return compact(match[1] || "help");
  }
  return null;
}

function parseOrigin(text) {
  if (!text) return null;
  const numbers = text.match(/-?\d+(?:\.\d+)?/g)?.map(Number) || [];
  if (numbers.length === 2) {
    return { x: Math.round(numbers[0]), y: Math.floor(bot.entity.position.y), z: Math.round(numbers[1]) };
  }
  if (numbers.length >= 3) {
    return { x: Math.round(numbers[0]), y: Math.round(numbers[1]), z: Math.round(numbers[2]) };
  }
  return null;
}

function defaultOrigin() {
  const position = bot.entity.position;
  return { x: Math.round(position.x), y: Math.floor(position.y), z: Math.round(position.z + 8) };
}

function workerNames() {
  return Array.from({ length: workerCount }, (_, index) => `${workerPrefix}${index + 1}`);
}

function readState() {
  try {
    if (!fs.existsSync(statePath)) return null;
    return JSON.parse(fs.readFileSync(statePath, "utf8"));
  } catch (error) {
    console.error(`failed to read swarm state: ${error.message}`);
    return null;
  }
}

function writeState(state) {
  fs.mkdirSync(runtimeDir, { recursive: true });
  fs.writeFileSync(statePath, `${JSON.stringify(state, null, 2)}\n`);
}

function writeWorkerProgressFiles(taskId) {
  const progressDir = path.join(runtimeDir, "progress");
  fs.mkdirSync(progressDir, { recursive: true });
  for (const worker of workerNames()) {
    fs.writeFileSync(path.join(progressDir, `${worker}.json`), `${JSON.stringify({ taskId, worker, doneIds: [], failedIds: [] }, null, 2)}\n`);
  }
}

function readAllProgress(taskId) {
  const progressDir = path.join(runtimeDir, "progress");
  if (!fs.existsSync(progressDir)) return [];
  return fs.readdirSync(progressDir)
    .filter((file) => file.endsWith(".json"))
    .map((file) => JSON.parse(fs.readFileSync(path.join(progressDir, file), "utf8")))
    .filter((entry) => entry.taskId === taskId);
}

function countBy(values) {
  return values.reduce((counts, value) => {
    counts[value] = (counts[value] || 0) + 1;
    return counts;
  }, {});
}

function say(message) {
  bot.chat(`[swarm] ${message}`.slice(0, 240));
}

function compact(text) {
  return String(text).replace(/\s+/g, " ").trim();
}

function escapeRegex(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function formatOrigin(origin) {
  return `${origin.x}, ${origin.y}, ${origin.z}`;
}
