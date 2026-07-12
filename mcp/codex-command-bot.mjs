#!/usr/bin/env node
import mineflayer from "../mindcraft/upstream/node_modules/mineflayer/index.js";
import pathfinderPackage from "../mindcraft/upstream/node_modules/mineflayer-pathfinder/index.js";

const { pathfinder, Movements, goals } = pathfinderPackage;

const host = process.env.MINECRAFT_HOST || "192.168.86.188";
const port = Number(process.env.MINECRAFT_PORT || 25565);
const username = process.env.CODEX_BOT_USERNAME || "CodexBot";
const version = process.env.MINECRAFT_VERSION || "1.21.6";

const bot = mineflayer.createBot({
  host,
  port,
  username,
  version,
  auth: "offline",
});

bot.loadPlugin(pathfinder);

let followTarget = null;
let movements = null;

bot.once("spawn", () => {
  movements = new Movements(bot);
  bot.pathfinder.setMovements(movements);
  bot.chat("CodexBot online. Tag me with '@CodexBot help'.");
  console.log(`${username} joined ${host}:${port} at ${bot.entity.position}`);
});

bot.on("chat", async (speaker, message) => {
  if (speaker === bot.username) return;

  const command = addressedCommand(message);
  if (!command) return;

  console.log(`<${speaker}> ${message}`);
  await runCommand(speaker, command).catch((error) => {
    console.error(error);
    bot.chat(`I hit an error: ${error.message}`);
  });
});

bot.on("physicsTick", () => {
  if (!followTarget) return;
  const player = bot.players[followTarget]?.entity;
  if (!player) return;

  const distance = bot.entity.position.distanceTo(player.position);
  if (distance > 3) {
    bot.pathfinder.setGoal(new goals.GoalFollow(player, 2), true);
  }
});

bot.on("kicked", (reason) => console.log("kicked", reason));
bot.on("error", (error) => console.error("error", error));
bot.on("end", (reason) => console.log("ended", reason));

function addressedCommand(message) {
  const trimmed = message.trim();
  const lower = trimmed.toLowerCase();
  const names = [
    username.toLowerCase(),
    `@${username.toLowerCase()}`,
    "codex",
    "@codex",
  ];

  for (const name of names) {
    if (lower === name) return "help";
    if (lower.startsWith(`${name} `)) return trimmed.slice(name.length).trim();
    if (lower.startsWith(`${name},`)) return trimmed.slice(name.length + 1).trim();
    if (lower.startsWith(`${name}:`)) return trimmed.slice(name.length + 1).trim();
  }

  return null;
}

async function runCommand(speaker, commandText) {
  const command = commandText.trim();
  const lower = command.toLowerCase();

  if (!command || lower === "help") {
    bot.chat("Commands: help | say <text> | where | come | follow me | stop | jump | spin | look at me");
    return;
  }

  if (lower.startsWith("say ")) {
    bot.chat(command.slice(4));
    return;
  }

  if (["where", "where are you", "pos", "position"].includes(lower)) {
    bot.chat(`I am at ${formatPosition(bot.entity.position)}.`);
    return;
  }

  if (lower === "jump") {
    bot.setControlState("jump", true);
    await wait(500);
    bot.setControlState("jump", false);
    bot.chat("jumped");
    return;
  }

  if (lower === "spin") {
    for (let i = 0; i < 8; i += 1) {
      await bot.look((Math.PI * 2 * i) / 8, 0, true);
      await wait(150);
    }
    bot.chat("spun");
    return;
  }

  if (lower === "stop") {
    followTarget = null;
    bot.pathfinder.stop();
    clearControls();
    bot.chat("stopped");
    return;
  }

  if (lower === "come" || lower === "come here") {
    const player = requirePlayer(speaker);
    bot.pathfinder.setGoal(new goals.GoalNear(player.position.x, player.position.y, player.position.z, 2));
    bot.chat(`coming to ${speaker}`);
    return;
  }

  if (lower === "follow me" || lower === "follow") {
    requirePlayer(speaker);
    followTarget = speaker;
    bot.chat(`following ${speaker}`);
    return;
  }

  if (lower === "look at me") {
    const player = requirePlayer(speaker);
    await bot.lookAt(player.position.offset(0, 1.6, 0), true);
    bot.chat(`looking at ${speaker}`);
    return;
  }

  bot.chat(`I heard "${command}", but I only know: help, say, where, come, follow me, stop, jump, spin, look at me.`);
}

function requirePlayer(name) {
  const player = bot.players[name]?.entity;
  if (!player) throw new Error(`I cannot see ${name}.`);
  return player;
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
