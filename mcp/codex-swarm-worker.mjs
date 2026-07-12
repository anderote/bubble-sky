#!/usr/bin/env node
import mineflayer from "../mindcraft/upstream/node_modules/mineflayer/index.js";
import pathfinderPackage from "../mindcraft/upstream/node_modules/mineflayer-pathfinder/index.js";
import vec3Package from "../mindcraft/upstream/node_modules/vec3/index.js";

const { pathfinder, Movements, goals } = pathfinderPackage;
const { Vec3 } = vec3Package;

const host = process.env.MINECRAFT_HOST || "192.168.86.188";
const port = Number(process.env.MINECRAFT_PORT || 25565);
const version = process.env.MINECRAFT_VERSION || "1.21.6";
const username = process.env.CODEX_BOT_USERNAME || "CodexSwarm1";
const task = process.env.CODEX_SWARM_TASK || "standby";
const origin = parseVec(process.env.CODEX_SWARM_ORIGIN);
const workerIndex = Number(process.env.CODEX_SWARM_INDEX || 0);
const workerCount = Number(process.env.CODEX_SWARM_COUNT || 1);
const announce = process.env.CODEX_SWARM_ANNOUNCE !== "false";

const bot = mineflayer.createBot({
  host,
  port,
  username,
  version,
  auth: "offline",
});

bot.loadPlugin(pathfinder);

let active = true;
let currentTask = task;
let movements = null;

process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);

bot.once("spawn", async () => {
  movements = new Movements(bot);
  bot.pathfinder.setMovements(movements);

  if (announce) {
    bot.chat(`${username} online for swarm task: ${currentTask}`);
  }
  console.log(`${username} joined ${host}:${port} at ${bot.entity.position}`);

  await runForever().catch((error) => {
    console.error(error);
    safeChat(`stopping after error: ${error.message}`);
    shutdown();
  });
});

bot.on("chat", async (speaker, message) => {
  if (speaker === bot.username) return;

  const command = addressedCommand(message);
  if (!command) return;

  console.log(`<${speaker}> ${message}`);
  await handleCommand(command).catch((error) => {
    console.error(error);
    safeChat(`I hit an error: ${error.message}`);
  });
});

bot.on("kicked", (reason) => console.log("kicked", reason));
bot.on("error", (error) => console.error("error", error));
bot.on("end", (reason) => console.log("ended", reason));

async function runForever() {
  while (active) {
    if (currentTask === "standby") {
      await wait(5000);
    } else if (currentTask === "survey") {
      await surveyLoop();
    } else if (currentTask === "flatten") {
      await flattenLoop();
    } else if (currentTask === "castle") {
      await castleLoop();
    } else {
      safeChat(`unknown task '${currentTask}', standing by`);
      currentTask = "standby";
    }
  }
}

async function surveyLoop() {
  const center = origin || bot.entity.position;
  const radius = 6 + workerIndex * 2;
  for (const point of squarePoints(center, radius, bot.entity.position.y)) {
    if (!active || currentTask !== "survey") return;
    await moveNear(point.x, point.y, point.z, 1);
    await wait(500);
  }
}

async function flattenLoop() {
  const center = origin || bot.entity.position;
  const y = Math.floor(center.y);
  const radius = 5 + workerCount;
  const points = assignedGrid(center, radius, y);

  for (const point of points) {
    if (!active || currentTask !== "flatten") return;
    await flattenColumn(point.x, y, point.z);
    await wait(150);
  }

  safeChat("flatten pass complete");
  currentTask = "standby";
}

async function castleLoop() {
  const center = origin || bot.entity.position;
  const y = Math.floor(center.y);
  const radius = 6;
  const height = 4;

  safeChat("starting castle-layout task");
  await flattenLoopForArea(center, y, radius + 1);

  for (const job of assignedCastleBlocks(center, y, radius, height)) {
    if (!active || currentTask !== "castle") return;
    await placeOrMark(job);
    await wait(100);
  }

  safeChat("castle pass complete");
  currentTask = "standby";
}

async function flattenLoopForArea(center, y, radius) {
  for (const point of assignedGrid(center, radius, y)) {
    if (!active || currentTask !== "castle") return;
    await flattenColumn(point.x, y, point.z);
  }
}

async function flattenColumn(x, targetY, z) {
  await moveNear(x, targetY, z, 3);

  for (let y = targetY + 1; y <= targetY + 4; y += 1) {
    const block = bot.blockAt(vec(x, y, z));
    if (block && block.name !== "air" && block.diggable) {
      await dig(block);
    }
  }

  const floor = bot.blockAt(vec(x, targetY - 1, z));
  if (floor?.name === "air") {
    // Without guaranteed inventory/op permissions, report the gap instead of failing the task.
    console.log(`${username}: floor gap at ${x},${targetY - 1},${z}`);
  }
}

async function placeOrMark(job) {
  await moveNear(job.x, job.y, job.z, 4);

  const existing = bot.blockAt(vec(job.x, job.y, job.z));
  if (!existing || existing.name !== "air") return;

  const item = bot.inventory.items().find((candidate) => candidate.name === job.block);
  if (!item) {
    console.log(`${username}: missing ${job.block}; marking planned block at ${job.x},${job.y},${job.z}`);
    return;
  }

  await bot.equip(item, "hand");
  const reference = bot.blockAt(vec(job.x, job.y - 1, job.z))
    || bot.blockAt(vec(job.x - 1, job.y, job.z))
    || bot.blockAt(vec(job.x + 1, job.y, job.z))
    || bot.blockAt(vec(job.x, job.y, job.z - 1))
    || bot.blockAt(vec(job.x, job.y, job.z + 1));

  if (!reference || reference.name === "air") return;
  const delta = vec(job.x - reference.position.x, job.y - reference.position.y, job.z - reference.position.z);
  await bot.placeBlock(reference, delta);
}

async function handleCommand(commandText) {
  const lower = commandText.trim().toLowerCase();

  if (!lower || lower === "help") {
    safeChat("swarm commands: help | task standby/survey/flatten/castle | stop | where");
    return;
  }

  if (lower === "where") {
    safeChat(`${username} at ${formatPosition(bot.entity.position)} doing ${currentTask}`);
    return;
  }

  if (lower === "stop" || lower === "task standby") {
    currentTask = "standby";
    bot.pathfinder.stop();
    clearControls();
    safeChat(`${username} standing by`);
    return;
  }

  if (lower.startsWith("task ")) {
    currentTask = lower.slice("task ".length).trim();
    safeChat(`${username} switching to ${currentTask}`);
    return;
  }

  safeChat(`unknown swarm command '${commandText}'`);
}

function addressedCommand(message) {
  const trimmed = message.trim();
  const lower = trimmed.toLowerCase();
  const names = [
    username.toLowerCase(),
    `@${username.toLowerCase()}`,
    "codexswarm",
    "@codexswarm",
    "swarm",
    "@swarm",
  ];

  for (const name of names) {
    if (lower === name) return "help";
    if (lower.startsWith(`${name} `)) return trimmed.slice(name.length).trim();
    if (lower.startsWith(`${name},`)) return trimmed.slice(name.length + 1).trim();
    if (lower.startsWith(`${name}:`)) return trimmed.slice(name.length + 1).trim();
  }

  return null;
}

async function moveNear(x, y, z, range) {
  bot.pathfinder.setGoal(new goals.GoalNear(Math.floor(x), Math.floor(y), Math.floor(z), range));
  await waitForPath();
}

function waitForPath() {
  return new Promise((resolve) => {
    const done = () => {
      bot.removeListener("goal_reached", done);
      bot.removeListener("path_stop", done);
      resolve();
    };
    bot.once("goal_reached", done);
    bot.once("path_stop", done);
    setTimeout(done, 15000);
  });
}

async function dig(block) {
  try {
    await bot.dig(block);
  } catch (error) {
    console.log(`${username}: could not dig ${block.name} at ${block.position}: ${error.message}`);
  }
}

function assignedGrid(center, radius, y) {
  const points = [];
  let i = 0;
  const cx = Math.floor(center.x);
  const cz = Math.floor(center.z);
  for (let x = cx - radius; x <= cx + radius; x += 1) {
    for (let z = cz - radius; z <= cz + radius; z += 1) {
      if (i % workerCount === workerIndex) points.push({ x, y, z });
      i += 1;
    }
  }
  return points;
}

function assignedCastleBlocks(center, y, radius, height) {
  const jobs = [];
  const cx = Math.floor(center.x);
  const cz = Math.floor(center.z);

  for (let level = 0; level < height; level += 1) {
    for (let x = cx - radius; x <= cx + radius; x += 1) {
      jobs.push({ x, y: y + level, z: cz - radius, block: "stone_bricks" });
      jobs.push({ x, y: y + level, z: cz + radius, block: "stone_bricks" });
    }
    for (let z = cz - radius + 1; z <= cz + radius - 1; z += 1) {
      jobs.push({ x: cx - radius, y: y + level, z, block: "stone_bricks" });
      jobs.push({ x: cx + radius, y: y + level, z, block: "stone_bricks" });
    }
  }

  return jobs.filter((_, index) => index % workerCount === workerIndex);
}

function squarePoints(center, radius, y) {
  const cx = Math.floor(center.x);
  const cz = Math.floor(center.z);
  return [
    { x: cx - radius, y, z: cz - radius },
    { x: cx + radius, y, z: cz - radius },
    { x: cx + radius, y, z: cz + radius },
    { x: cx - radius, y, z: cz + radius },
  ];
}

function parseVec(value) {
  if (!value) return null;
  const parts = value.split(",").map((part) => Number(part.trim()));
  if (parts.length !== 3 || parts.some((part) => Number.isNaN(part))) {
    throw new Error(`Invalid CODEX_SWARM_ORIGIN '${value}', expected x,y,z`);
  }
  return vec(parts[0], parts[1], parts[2]);
}

function vec(x, y, z) {
  return new Vec3(x, y, z);
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

function formatPosition(position) {
  return `${position.x.toFixed(1)}, ${position.y.toFixed(1)}, ${position.z.toFixed(1)}`;
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function shutdown() {
  active = false;
  clearControls();
  try {
    bot.end();
  } finally {
    setTimeout(() => process.exit(0), 250);
  }
}
