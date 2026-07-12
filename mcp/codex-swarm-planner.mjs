#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import mineflayer from "../mindcraft/upstream/node_modules/mineflayer/index.js";
import {
  IMPORTED_BLUEPRINTS_DIR,
  compileSchematicPlan,
  findSchematicByName,
  listImportedSchematics,
} from "./blueprint-compiler.mjs";

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
const jobChunkSize = Number(process.env.CODEX_SWARM_JOB_CHUNK_SIZE || 32);

const bot = mineflayer.createBot({ host, port, username, version, auth: "offline" });
let lastReportAt = 0;
let lastReportTaskId = null;
let lastMilestone = -1;
let lastCompletedPhases = new Set();

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
bot.on("end", (reason) => {
  console.log("ended", reason);
  process.exit(1);
});

async function runCommand(speaker, commandText) {
  const command = compact(commandText).replace(/[.!?]+$/g, "");
  const lower = command.toLowerCase();

  if (!command || lower === "help") {
    say("swarm: build cabin|watchtower [at x y z] | build schematic <name> [at x y z] | blueprints | blueprint | status | cancel");
    return;
  }

  if (lower === "blueprints" || lower === "schematics" || lower === "list blueprints") {
    const files = await listImportedSchematics();
    say(files.length ? `available blueprints: ${files.slice(0, 8).join(", ")}${files.length > 8 ? ", ..." : ""}` : `no schematics in ${IMPORTED_BLUEPRINTS_DIR}`);
    return;
  }

  if (lower === "status" || lower === "what are you doing") {
    showStatus();
    return;
  }

  if (
    lower === "blueprint" ||
    lower === "blueprint url" ||
    lower === "plan" ||
    lower === "source" ||
    lower === "what blueprint" ||
    lower === "what blueprint are you using" ||
    lower === "where is the blueprint"
  ) {
    showBlueprint();
    return;
  }

  if (lower === "cancel" || lower === "stop") {
    writeState({ status: "cancelled", taskId: `cancel-${Date.now()}`, jobs: [], workers: workerNames() });
    say("swarm task cancelled");
    return;
  }

  const buildRequest = parseBuildCommand(command);
  if (!buildRequest) {
    say(`I do not know "${command}". Try @swarm build cabin, @swarm build watchtower, or @swarm build schematic castle.`);
    return;
  }

  const origin = parseOrigin(buildRequest.originText) || defaultOrigin();
  const plan = await createPlan(buildRequest, origin);
  writeState(plan);
  writeWorkerProgressFiles(plan.taskId);
  lastReportTaskId = plan.taskId;
  lastMilestone = 0;
  lastCompletedPhases = new Set();
  lastReportAt = Date.now();

  say(`planned ${plan.structure} at ${formatOrigin(origin)}: ${plan.jobs.length} block jobs for ${workerCount} drones`);
  say(`${plan.source ? describeSource(plan.source) : "built-in blueprint"}; drones move near work and place with /setblock or /fill.`);
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
  const summary = progressSummary(state);
  const active = summary.activeText;

  say(`${state.structure} ${state.status}: ${done.size}/${state.jobs.length} done (${summary.percent}%), ${failed} failed; ${summary.phaseText}${active ? `; ${active}` : ""}`);
}

function showBlueprint() {
  const state = readState();
  if (!state?.taskId || state.status === "cancelled") {
    say("no active blueprint");
    return;
  }
  say(`${state.structure}: ${state.jobs?.length || 0} jobs at ${formatOrigin(state.origin || {})}; ${state.source ? describeSource(state.source) : "built-in blueprint"}`);
}

function reportProgress() {
  const state = readState();
  if (!state?.taskId || state.status !== "building" || state.jobs.length === 0) return;

  const summary = progressSummary(state);
  if (state.taskId !== lastReportTaskId) {
    lastReportTaskId = state.taskId;
    lastMilestone = -1;
    lastCompletedPhases = new Set();
    lastReportAt = 0;
  }

  const newlyCompleted = summary.completedPhases.filter((phase) => !lastCompletedPhases.has(phase));
  if (newlyCompleted.length > 0) {
    for (const phase of newlyCompleted) lastCompletedPhases.add(phase);
    say(`${state.structure}: ${humanPhaseList(newlyCompleted)} complete (${summary.done}/${state.jobs.length} jobs).`);
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
  const finishedIds = new Set(progress.flatMap((entry) => [...(entry.doneIds || []), ...(entry.failedIds || [])]));
  const failed = progress.reduce((sum, entry) => sum + (entry.failedIds?.length || 0), 0);
  const activePhases = progress
    .map((entry) => entry.activeJob?.split(":")[0])
    .filter(Boolean);
  const phaseCounts = countBy(activePhases);
  const phaseText = Object.keys(phaseCounts).length
    ? Object.entries(phaseCounts).map(([phase, count]) => `${count} drone${count === 1 ? "" : "s"} on ${phase}`).join(", ")
    : nextPhaseText(state, finishedIds);
  const completedPhases = phaseOrderForState(state)
    .filter((phase) => state.jobs.filter((job) => job.phase === phase).every((job) => finishedIds.has(job.id)));
  const activeText = progress
    .filter((entry) => entry.activeJob)
    .map((entry) => `${entry.worker}:${entry.activeJob}`)
    .join(", ");

  return {
    done: doneIds.size,
    failed,
    percent: Math.floor((doneIds.size / state.jobs.length) * 100),
    phaseText,
    activeText,
    completedPhases,
  };
}

function nextPhaseText(state, finishedIds) {
  const next = state.jobs.find((job) => !finishedIds.has(job.id))?.phase;
  return next ? `waiting on ${next}` : "drones waiting for assignments";
}

function phaseOrderForState(state) {
  return [...new Set((state.jobs || []).map((job) => job.phase))];
}

function humanPhaseList(phases) {
  if (phases.length === 1) return `${phases[0]} phase`;
  if (phases.length === 2) return `${phases[0]} and ${phases[1]} phases`;
  return `${phases.slice(0, -1).join(", ")}, and ${phases[phases.length - 1]} phases`;
}

function describeSource(source) {
  if (!source) return "built-in blueprint";
  const size = source.size ? `${source.size.x}x${source.size.y}x${source.size.z}` : "unknown size";
  const sourcePath = source.path ? path.relative(process.cwd(), source.path) : source.type;
  return `blueprint ${sourcePath} (${size})`;
}

async function createPlan(buildRequest, origin) {
  const workers = workerNames();
  const structure = buildRequest.structure;

  if (buildRequest.kind === "schematic") {
    const input = await findSchematicByName(buildRequest.name);
    if (!input) {
      throw new Error(`could not find ${buildRequest.name}. Put .schem files in ${IMPORTED_BLUEPRINTS_DIR}`);
    }
    return compileSchematicPlan({
      input,
      name: buildRequest.name,
      origin,
      workers,
      chunkSize: jobChunkSize,
      clear: true,
      structure: buildRequest.name,
    });
  }

  const jobs = structure === "cabin" ? cabinJobs(origin) : watchtowerJobs(origin);

  assignJobs(jobs, workers, jobChunkSize);
  jobs.forEach((job, index) => {
    job.id = `${structure}-${String(index + 1).padStart(4, "0")}`;
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

function assignJobs(jobs, workers, chunkSize) {
  sortJobs(jobs);
  const safeChunkSize = Number.isFinite(chunkSize) && chunkSize > 0 ? Math.floor(chunkSize) : 32;
  let chunkIndex = 0;
  let lastPhase = null;

  for (const job of jobs) {
    if (job.phase !== lastPhase) {
      lastPhase = job.phase;
      chunkIndex = 0;
    }
    const workerIndex = Math.floor(chunkIndex / safeChunkSize) % workers.length;
    job.worker = workers[workerIndex];
    chunkIndex += 1;
  }
}

function parseBuildCommand(command) {
  const normalized = normalizeBuildCommand(command);
  const match = normalized.match(/^(?:build|make|construct|create)\s+(.+?)(?:\s+(?:at|near|around)\s+(.+))?$/i);
  if (!match) return null;

  const target = normalizeBuildTarget(match[1]);
  const originText = match[2];
  const lowerTarget = target.toLowerCase();

  if (["cabin", "watchtower", "tower"].includes(lowerTarget)) {
    return {
      kind: "builtin",
      structure: lowerTarget === "tower" ? "watchtower" : lowerTarget,
      originText,
    };
  }

  const schematicMatch = target.match(/^(?:schematic|blueprint)\s+(.+)$/i);
  if (schematicMatch) {
    return {
      kind: "schematic",
      structure: compact(schematicMatch[1]),
      name: compact(schematicMatch[1]),
      originText,
    };
  }

  if (/\.(schem|schematic)$/i.test(target)) {
    return {
      kind: "schematic",
      structure: target,
      name: target,
      originText,
    };
  }

  return {
    kind: "schematic",
    structure: target,
    name: target,
    originText,
  };
}

function normalizeBuildCommand(command) {
  return compact(command)
    .replace(/^(?:please|pls|hey|yo)\s+/i, "")
    .replace(/\b(?:can|could|would)\s+you\s+/i, "")
    .replace(/^(?:i|we)\s+(?:need|want|would like)\s+/i, "build ")
    .replace(/^can\s+i\s+get\s+/i, "build ")
    .replace(/^give\s+(?:me|us)\s+/i, "build ")
    .replace(/\b(?:for\s+me|for\s+us)\b/gi, "")
    .replace(/\s+/g, " ")
    .trim();
}

function normalizeBuildTarget(target) {
  return compact(target)
    .replace(/^(?:me|us)\s+/i, "")
    .replace(/^(?:a|an|the)\s+/i, "")
    .replace(/\b(?:please|pls)\b/gi, "")
    .replace(/\s+/g, " ")
    .trim();
}

function cabinJobs(origin) {
  const jobs = [];
  const width = 9;
  const depth = 7;
  const wallHeight = 4;
  const x0 = origin.x - Math.floor(width / 2);
  const z0 = origin.z - Math.floor(depth / 2);
  const x1 = x0 + width - 1;
  const z1 = z0 + depth - 1;
  const y = origin.y;

  for (let x = x0 - 2; x <= x1 + 2; x += 1) {
    for (let z = z0 - 2; z <= z1 + 2; z += 1) {
      for (let level = 0; level <= wallHeight + 5; level += 1) {
        jobs.push(job(x, y + level, z, "air", "clear"));
      }
    }
  }

  for (let x = x0; x <= x1; x += 1) {
    for (let z = z0; z <= z1; z += 1) {
      jobs.push(job(x, y, z, "oak_planks", "floor"));
    }
  }

  for (let level = 1; level <= wallHeight; level += 1) {
    for (let x = x0 + 1; x <= x1 - 1; x += 1) {
      jobs.push(job(x, y + level, z0, "oak_planks", "north wall"));
      jobs.push(job(x, y + level, z1, "oak_planks", "south wall"));
    }
    for (let z = z0 + 1; z <= z1 - 1; z += 1) {
      jobs.push(job(x0, y + level, z, "oak_planks", "west wall"));
      jobs.push(job(x1, y + level, z, "oak_planks", "east wall"));
    }
  }

  for (const [x, z] of [[x0, z0], [x1, z0], [x0, z1], [x1, z1]]) {
    for (let level = 1; level <= wallHeight + 1; level += 1) {
      jobs.push(job(x, y + level, z, "oak_log", "frame"));
    }
  }

  for (let z = z0 - 1; z <= z1 + 1; z += 1) {
    jobs.push(job(x0 - 1, y + wallHeight + 1, z, "spruce_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", "roof"));
    jobs.push(job(x1 + 1, y + wallHeight + 1, z, "spruce_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]", "roof"));
    jobs.push(job(x0, y + wallHeight + 2, z, "spruce_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", "roof"));
    jobs.push(job(x1, y + wallHeight + 2, z, "spruce_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]", "roof"));
    jobs.push(job(x0 + 1, y + wallHeight + 3, z, "spruce_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", "roof"));
    jobs.push(job(x1 - 1, y + wallHeight + 3, z, "spruce_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]", "roof"));
    for (let x = x0 + 2; x <= x1 - 2; x += 1) {
      jobs.push(job(x, y + wallHeight + 4, z, "spruce_planks", "roof ridge"));
    }
  }

  const doorX = origin.x;
  jobs.push(job(doorX, y + 1, z0, "air", "door"));
  jobs.push(job(doorX, y + 2, z0, "air", "door"));
  jobs.push(job(doorX, y + 3, z0, "oak_planks", "lintel"));
  for (const window of [
    [x0, y + 2, origin.z],
    [x1, y + 2, origin.z],
    [origin.x - 2, y + 2, z1],
    [origin.x + 2, y + 2, z1],
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
  const phaseOrder = [
    "clear",
    "foundation",
    "floor",
    "base",
    "supports",
    "posts",
    "frame",
    "north wall",
    "south wall",
    "east wall",
    "west wall",
    "wall",
    "platform",
    "ladder",
    "door",
    "lintel",
    "windows",
    "roof",
    "roof ridge",
    "railing",
  ];
  return jobs.sort((a, b) => {
    const phaseDelta = phaseIndex(phaseOrder, a.phase) - phaseIndex(phaseOrder, b.phase);
    if (phaseDelta) return phaseDelta;
    if (a.y !== b.y) return a.y - b.y;
    if (a.z !== b.z) return a.z - b.z;
    return a.x - b.x;
  });
}

function phaseIndex(phaseOrder, phase) {
  const index = phaseOrder.indexOf(phase);
  return index === -1 ? phaseOrder.length : index;
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
