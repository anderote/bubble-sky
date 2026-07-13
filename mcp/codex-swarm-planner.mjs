#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import mineflayer from "../mindcraft/upstream/node_modules/mineflayer/index.js";
import pathfinderPackage from "../mindcraft/upstream/node_modules/mineflayer-pathfinder/index.js";
import {
  IMPORTED_BLUEPRINTS_DIR,
  compileSchematicPlan,
  findSchematicByName,
  listImportedSchematics,
} from "./blueprint-compiler.mjs";
import { normalizeBuildState, normalizeWorkerProgress } from "../shared/build-protocol/index.mjs";

const { pathfinder, Movements, goals } = pathfinderPackage;

const host = process.env.MINECRAFT_HOST || "192.168.86.188";
const port = Number(process.env.MINECRAFT_PORT || 25565);
const version = process.env.MINECRAFT_VERSION || "1.21.6";
const username = process.env.CODEX_SWARM_BOSS || "CodexBoss";
const workerPrefix = process.env.CODEX_SWARM_PREFIX || "CodexDrone";
const workerCount = Number(process.env.CODEX_SWARM_COUNT || 4);
const runtimeDir = process.env.CODEX_SWARM_RUNTIME || ".codex-runtime/swarm";
const statePath = path.join(runtimeDir, "state.json");
const playerMemoryPath = path.join(runtimeDir, "players.json");
const announceOnJoin = process.env.CODEX_SWARM_ANNOUNCE_ON_JOIN !== "0";
const reportIntervalMs = Number(process.env.CODEX_SWARM_REPORT_INTERVAL_MS || 60000);
const milestoneStep = Number(process.env.CODEX_SWARM_REPORT_MILESTONE || 25);
const jobChunkSize = Number(process.env.CODEX_SWARM_JOB_CHUNK_SIZE || 32);
const ignoredSpeakers = new Set(parseList(process.env.CODEX_SWARM_IGNORED_SPEAKERS || "codex,claude,claudebot,grok"));

const bot = mineflayer.createBot({ host, port, username, version, auth: "offline" });
bot.loadPlugin(pathfinder);
let lastReportAt = 0;
let lastReportTaskId = null;
let lastMilestone = -1;
let lastCompletedPhases = new Set();
let movements = null;

bot.once("spawn", () => {
  movements = new Movements(bot);
  bot.pathfinder.setMovements(movements);
  if (announceOnJoin) {
    say("CodexBoss online. Use @swarm build cabin or @swarm build watchtower.");
  }
  console.log(`${username} joined ${host}:${port} at ${bot.entity.position}`);
  recordVisiblePlayers("spawn");
  setInterval(reportProgress, 10000).unref();
  setInterval(() => recordVisiblePlayers("scan"), 2500).unref();
});

bot.on("chat", async (speaker, message) => {
  if (speaker === bot.username) return;
  if (isIgnoredSpeaker(speaker)) return;

  const command = addressedCommand(message);
  if (!command) return;

  console.log(`<${speaker}> ${message}`);
  recordPlayer(speaker, bot.players[speaker]?.entity?.position, "chat");
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
    say("swarm: build cabin|watchtower [at x y z] | build schematic <name> [at x y z] | burn/flood/freeze/curse this [at x y z] | come|follow me | status | cancel");
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

  const recall = parseRecallCommand(command, speaker);
  if (recall) {
    await recallSwarm(recall.target);
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

  const planRequest = parsePlanCommand(command);
  if (!planRequest) {
    say(`I do not know "${command}". Try @swarm build cabin, @swarm build schematic castle, or @swarm burn this castle with lava.`);
    return;
  }

  const origin = resolveOrigin(planRequest.originText, speaker);
  const plan = await createPlan(planRequest, origin);
  writeState(plan);
  writeWorkerProgressFiles(plan.taskId);
  lastReportTaskId = plan.taskId;
  lastMilestone = 0;
  lastCompletedPhases = new Set();
  lastReportAt = Date.now();

  say(`planned ${plan.structure} at ${formatOrigin(origin)}: ${plan.jobs.length} block jobs for ${workerCount} drones`);
  say(`${describePlanSource(plan)}; drones place exact block runs through the Bubble Sky bridge.`);
}

async function recallSwarm(target) {
  const targetName = findPlayerName(target) || target;
  const player = bot.players[targetName]?.entity;
  const fallbackPosition = parseOrigin(target, bot.entity.position);
  const rememberedPosition = rememberedPlayerPosition(targetName);
  const targetPosition = player?.position ? vectorPosition(player.position) : fallbackPosition || rememberedPosition;
  const isCoordinateTarget = Boolean(fallbackPosition);

  const taskId = `recall-${Date.now()}`;
  writeState({
    status: "recall",
    taskId,
    target: isCoordinateTarget ? null : targetName,
    targetPosition,
    teleport: true,
    workers: workerNames(),
    jobs: [],
    createdAt: new Date().toISOString(),
  });
  writeWorkerProgressFiles(taskId);
  if (targetPosition) {
    attemptTeleportSelf(targetName, targetPosition, isCoordinateTarget);
    bot.pathfinder.setGoal(new goals.GoalNear(targetPosition.x, targetPosition.y, targetPosition.z, 3));
    say(`recalling ${workerCount} drones and boss to ${isCoordinateTarget ? "coordinates" : targetName} at ${formatPosition(targetPosition)}`);
  } else {
    say(`recalling ${workerCount} drones to ${targetName}. No coordinates cached yet; I will keep looking and use /tp if the server allows it.`);
  }
}

function parseRecallCommand(command, speaker) {
  const lower = compact(command).toLowerCase();
  if (/^(?:come|come here|follow me|recall|recall me|rally|rally up)$/.test(lower)) {
    return { target: speaker };
  }
  const match = command.match(/^(?:come to|follow|recall|rally to)\s+(.+)$/i);
  if (match) return { target: normalizeBuildTarget(match[1]) === "me" ? speaker : compact(match[1]) };
  return null;
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
  const locations = workerLocationText(progress);
  const failureText = firstFailureText(progress);

  say(`${state.structure} ${state.status}: ${done.size}/${state.jobs.length} done (${summary.percent}%), ${failed} failed; ${summary.phaseText}${failureText ? `; ${failureText}` : ""}${active ? `; ${active}` : ""}${locations ? `; ${locations}` : ""}`);
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

function workerLocationText(progress) {
  const locations = progress
    .filter((entry) => entry.position)
    .map((entry) => `${entry.worker}@${entry.position.x},${entry.position.altitude},${entry.position.z}`);
  if (locations.length === 0) return "";
  return `drones ${locations.join(" ")}`;
}

function firstFailureText(progress) {
  for (const entry of progress) {
    const firstFailure = Object.values(entry.failures || {})[0];
    if (firstFailure) return `first failure: ${String(firstFailure).slice(0, 120)}`;
  }
  return "";
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

function describePlanSource(plan) {
  if (plan.source) return describeSource(plan.source);
  return plan.kind === "effect" ? "effect plan" : "built-in blueprint";
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

  const jobs = buildRequest.kind === "effect"
    ? structureEffectJobs(origin, buildRequest.effect)
    : structure === "cabin" ? cabinJobs(origin) : watchtowerJobs(origin);

  assignJobs(jobs, workers, jobChunkSize);
  jobs.forEach((job, index) => {
    job.id = `${structure}-${String(index + 1).padStart(4, "0")}`;
  });

  return {
    taskId: `${structure}-${Date.now()}`,
    status: "building",
    kind: buildRequest.kind,
    structure,
    origin,
    workers,
    jobs,
    createdAt: new Date().toISOString(),
  };
}

function parsePlanCommand(command) {
  return parseEffectCommand(command) || parseBuildCommand(command);
}

function parseEffectCommand(command) {
  const normalized = normalizeEffectCommand(command);
  const effect = effectForCommand(normalized);
  if (!effect) return null;

  const originText = extractOriginText(normalized);
  return {
    kind: "effect",
    structure: effectStructureName(effect),
    effect,
    originText,
  };
}

function normalizeEffectCommand(command) {
  return compact(command)
    .replace(/^(?:please|pls|hey|yo)\s+/i, "")
    .replace(/\b(?:can|could|would)\s+you\s+/i, "")
    .replace(/\b(?:for\s+me|for\s+us)\b/gi, "")
    .replace(/\s+/g, " ")
    .trim();
}

function effectForCommand(command) {
  const lower = command.toLowerCase();
  const hasTarget = /\b(this|that|nearby|castle|fortress|fort|tower|base|build|building|structure|house|village|wall|walls|area|place|thing)\b/.test(lower);
  if (!hasTarget) return null;

  if (/\b(burn|ignite|torch|melt|scorch|incinerate|set\s+.*on\s+fire|lava|volcano|hellscape)\b/.test(lower)) return "lava_burn";
  if (/\b(flood|drown|submerge|soak|waterlog|water)\b/.test(lower)) return "flood";
  if (/\b(freeze|ice|snow|blizzard|frost)\b/.test(lower)) return "freeze";
  if (/\b(curse|haunt|spooky|evil|corrupt|darken|doom|ruin)\b/.test(lower)) return "curse";
  return null;
}

function extractOriginText(command) {
  const match = command.match(/\b(?:at|near|around)\s+(-?\d+(?:\.\d+)?(?:\s*,?\s*-?\d+(?:\.\d+)?){1,2}|here)$/i);
  if (match) return match[1];
  if (/\bhere\b/i.test(command)) return "here";
  return undefined;
}

function effectStructureName(effect) {
  return {
    lava_burn: "lava burn",
    flood: "flood",
    freeze: "freeze",
    curse: "curse",
  }[effect] || "effect";
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

  let target = normalizeBuildTarget(match[1]);
  let originText = match[2];
  if (/\bhere$/i.test(target)) {
    target = compact(target.replace(/\bhere$/i, ""));
    originText = "here";
  }
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
    jobs.push(job(x0 - 1, y + wallHeight + 1, z, "spruce_planks", "roof"));
    jobs.push(job(x1 + 1, y + wallHeight + 1, z, "spruce_planks", "roof"));
    jobs.push(job(x0, y + wallHeight + 2, z, "spruce_planks", "roof"));
    jobs.push(job(x1, y + wallHeight + 2, z, "spruce_planks", "roof"));
    jobs.push(job(x0 + 1, y + wallHeight + 3, z, "spruce_planks", "roof"));
    jobs.push(job(x1 - 1, y + wallHeight + 3, z, "spruce_planks", "roof"));
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

function structureEffectJobs(origin, effect) {
  const jobs = [];
  const add = (phase, x, y, z, block) => jobs.push(job(x, y, z, block, phase));
  const cuboid = (phase, x1, y1, z1, x2, y2, z2, block) => {
    for (let yy = Math.min(y1, y2); yy <= Math.max(y1, y2); yy += 1) {
      for (let zz = Math.min(z1, z2); zz <= Math.max(z1, z2); zz += 1) {
        for (let xx = Math.min(x1, x2); xx <= Math.max(x1, x2); xx += 1) {
          add(phase, xx, yy, zz, block);
        }
      }
    }
  };

  const x0 = origin.x - 18;
  const x1 = origin.x + 18;
  const z0 = origin.z - 18;
  const z1 = origin.z + 18;
  const y0 = origin.y - 4;
  const y1 = origin.y + 24;

  if (effect === "flood") {
    cuboid("basin", x0, y0, z0, x1, y0, z1, "water");
    cuboid("surge", x0, origin.y + 2, z0, x1, origin.y + 3, z1, "water");
    cuboid("cascade", origin.x - 8, origin.y + 8, origin.z - 8, origin.x + 8, origin.y + 9, origin.z + 8, "water");
    cuboid("containment", x0, origin.y, z0, x1, origin.y + 8, z0, "blue_stained_glass");
    cuboid("containment", x0, origin.y, z1, x1, origin.y + 8, z1, "blue_stained_glass");
    cuboid("containment", x0, origin.y, z0, x0, origin.y + 8, z1, "blue_stained_glass");
    cuboid("containment", x1, origin.y, z0, x1, origin.y + 8, z1, "blue_stained_glass");
    return sortJobs(jobs);
  }

  if (effect === "freeze") {
    cuboid("ice floor", x0, y0, z0, x1, y0, z1, "packed_ice");
    cuboid("snow", x0, origin.y + 1, z0, x1, origin.y + 1, z1, "snow_block");
    cuboid("ice cap", x0, origin.y + 10, z0, x1, origin.y + 10, z1, "ice");
    for (let x = x0; x <= x1; x += 6) {
      cuboid("icicles", x, origin.y + 2, z0, x, y1, z0, "blue_ice");
      cuboid("icicles", x, origin.y + 2, z1, x, y1, z1, "blue_ice");
    }
    for (let z = z0; z <= z1; z += 6) {
      cuboid("icicles", x0, origin.y + 2, z, x0, y1, z, "blue_ice");
      cuboid("icicles", x1, origin.y + 2, z, x1, y1, z, "blue_ice");
    }
    return sortJobs(jobs);
  }

  if (effect === "curse") {
    cuboid("corruption", x0, y0, z0, x1, y0, z1, "blackstone");
    cuboid("soul ring", x0, origin.y, z0, x1, origin.y + 1, z0, "soul_sand");
    cuboid("soul ring", x0, origin.y, z1, x1, origin.y + 1, z1, "soul_sand");
    cuboid("soul ring", x0, origin.y, z0, x0, origin.y + 1, z1, "soul_sand");
    cuboid("soul ring", x1, origin.y, z0, x1, origin.y + 1, z1, "soul_sand");
    for (const [dx, dz] of [[-14, -14], [14, -14], [-14, 14], [14, 14], [0, -18], [0, 18]]) {
      cuboid("spires", origin.x + dx, origin.y, origin.z + dz, origin.x + dx, origin.y + 10, origin.z + dz, "obsidian");
      add("soul fire", origin.x + dx, origin.y + 11, origin.z + dz, "soul_fire");
    }
    cuboid("haunt", origin.x - 5, origin.y + 2, origin.z - 5, origin.x + 5, origin.y + 8, origin.z + 5, "purple_stained_glass");
    return sortJobs(jobs);
  }

  cuboid("magma floor", x0, y0, z0, x1, y0, z1, "magma_block");
  cuboid("ignition", x0, origin.y + 1, z0, x1, origin.y + 1, z1, "fire");
  cuboid("lava shelf", origin.x - 9, origin.y + 12, origin.z - 9, origin.x + 9, origin.y + 12, origin.z + 9, "lava");
  cuboid("lava crown", origin.x - 4, origin.y + 18, origin.z - 4, origin.x + 4, origin.y + 18, origin.z + 4, "lava");
  cuboid("magma wall", x0, origin.y, z0, x1, origin.y + 4, z0, "magma_block");
  cuboid("magma wall", x0, origin.y, z1, x1, origin.y + 4, z1, "magma_block");
  cuboid("magma wall", x0, origin.y, z0, x0, origin.y + 4, z1, "magma_block");
  cuboid("magma wall", x1, origin.y, z0, x1, origin.y + 4, z1, "magma_block");
  for (const [dx, dz] of [[-16, -16], [16, -16], [-16, 16], [16, 16], [0, 0]]) {
    cuboid("lava falls", origin.x + dx, origin.y, origin.z + dz, origin.x + dx, origin.y + 12, origin.z + dz, "lava");
  }
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

function resolveOrigin(text, speaker) {
  const speakerPosition = bot.players[speaker]?.entity?.position;
  const fallback = defaultOrigin();
  if (!text || compact(text).toLowerCase() === "here") {
    return positionOrigin(speakerPosition, fallback, text ? 0 : 8);
  }

  const parsed = parseOrigin(text, speakerPosition || fallback);
  if (parsed) return parsed;
  return positionOrigin(speakerPosition, fallback, 8);
}

function parseOrigin(text, referencePosition) {
  if (!text) return null;
  const numbers = text.match(/-?\d+(?:\.\d+)?/g)?.map(Number) || [];
  if (numbers.length === 2) {
    return { x: Math.round(numbers[0]), y: Math.floor(referencePosition.y), z: Math.round(numbers[1]) };
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

function positionOrigin(position, fallback, zOffset) {
  if (!position) return fallback;
  return { x: Math.round(position.x), y: Math.floor(position.y), z: Math.round(position.z + zOffset) };
}

function workerNames() {
  return Array.from({ length: workerCount }, (_, index) => `${workerPrefix}${index + 1}`);
}

function isIgnoredSpeaker(speaker) {
  const normalized = String(speaker).toLowerCase();
  return ignoredSpeakers.has(normalized) || normalized === username.toLowerCase() || normalized.startsWith(workerPrefix.toLowerCase());
}

function parseList(text) {
  return String(text)
    .split(",")
    .map((entry) => entry.trim().toLowerCase())
    .filter(Boolean);
}

function readState() {
  try {
    if (!fs.existsSync(statePath)) return null;
    return normalizeBuildState(JSON.parse(fs.readFileSync(statePath, "utf8")));
  } catch (error) {
    console.error(`failed to read swarm state: ${error.message}`);
    return null;
  }
}

function writeState(state) {
  fs.mkdirSync(runtimeDir, { recursive: true });
  fs.writeFileSync(statePath, `${JSON.stringify(normalizeBuildState(state), null, 2)}\n`);
}

function writeWorkerProgressFiles(taskId) {
  const progressDir = path.join(runtimeDir, "progress");
  fs.mkdirSync(progressDir, { recursive: true });
  for (const worker of workerNames()) {
    const progress = normalizeWorkerProgress({ taskId, worker, doneIds: [], failedIds: [], claimedIds: [] });
    fs.writeFileSync(path.join(progressDir, `${worker}.json`), `${JSON.stringify(progress, null, 2)}\n`);
  }
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
  const entry = readPlayerMemory().players?.[String(playerName).toLowerCase()];
  return entry?.position || null;
}

function readPlayerMemory() {
  try {
    if (!fs.existsSync(playerMemoryPath)) return { players: {} };
    return JSON.parse(fs.readFileSync(playerMemoryPath, "utf8"));
  } catch (error) {
    console.error(`failed to read player memory: ${error.message}`);
    return { players: {} };
  }
}

function writePlayerMemory(memory) {
  fs.mkdirSync(runtimeDir, { recursive: true });
  const tempPath = `${playerMemoryPath}.${process.pid}.tmp`;
  fs.writeFileSync(tempPath, `${JSON.stringify(memory, null, 2)}\n`);
  fs.renameSync(tempPath, playerMemoryPath);
}

function attemptTeleportSelf(targetName, targetPosition, isCoordinateTarget) {
  if (isCoordinateTarget && targetPosition) {
    bot.chat(`/tp ${username} ${Math.round(targetPosition.x)} ${Math.round(targetPosition.y)} ${Math.round(targetPosition.z)}`);
    return;
  }
  if (targetName) bot.chat(`/tp ${username} ${targetName}`);
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

function formatPosition(position) {
  return `${Math.round(position.x)}, ${Math.round(position.y)}, ${Math.round(position.z)}`;
}

function vectorPosition(position) {
  return { x: position.x, y: position.y, z: position.z };
}

function findPlayerName(name) {
  const lower = String(name).toLowerCase();
  return Object.keys(bot.players).find((playerName) => playerName.toLowerCase() === lower);
}
