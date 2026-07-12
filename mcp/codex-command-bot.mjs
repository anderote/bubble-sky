#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import mineflayer from "../mindcraft/upstream/node_modules/mineflayer/index.js";
import pathfinderPackage from "../mindcraft/upstream/node_modules/mineflayer-pathfinder/index.js";

const { pathfinder, Movements, goals } = pathfinderPackage;

const host = process.env.MINECRAFT_HOST || "192.168.86.188";
const port = Number(process.env.MINECRAFT_PORT || 25565);
const username = process.env.CODEX_BOT_USERNAME || "codex";
const version = process.env.MINECRAFT_VERSION || "1.21.6";
const richChat = process.env.CODEX_RICH_CHAT === "1";
const llmPlayers = parseList(process.env.CODEX_LLM_PLAYERS || "codex,claude,grok");
const historyPath = process.env.CODEX_CHAT_HISTORY || ".codex-runtime/chat-history.jsonl";
const historyLimit = Number(process.env.CODEX_CHAT_HISTORY_LIMIT || 2000);
const botAliases = botHandleAliases(username);
const primaryHandle = botAliases[0] || username;
const helpLines = [
  "help",
  "history [count]",
  "status",
  "visibility public|private|llm",
  "say <text>",
  "tell <player> <text>",
  "where",
  "come [player]",
  "follow [player|me]",
  "escort me to <x>,<z>",
  "look at [player|me]",
  "go to <x> <y> <z>",
  "stop",
  "jump",
  "spin",
];
const botLoopPatterns = [
  /^help$/,
  /^hi$/,
  /^hello$/,
  /^hey$/,
  /^say ["']?help["']?/,
  /tell you what i can do/,
  /what i can do/,
  /try @/,
  /tag @/,
];

const bot = mineflayer.createBot({
  host,
  port,
  username,
  version,
  auth: "offline",
});

bot.loadPlugin(pathfinder);

let followTarget = null;
let escort = null;
let movements = null;
let visibility = process.env.CODEX_CHAT_VISIBILITY || "public";

bot.once("spawn", () => {
  movements = new Movements(bot);
  bot.pathfinder.setMovements(movements);
  say(`${username} online. Tag @${primaryHandle} help. Chat visibility is public.`);
  console.log(`${username} joined ${host}:${port} at ${bot.entity.position}`);
});

bot.on("chat", async (speaker, message) => {
  if (speaker === bot.username) return;

  const command = addressedCommand(message);
  if (!command) {
    if (looksLikeLlmConversation(speaker, message)) {
      recordHistory({ type: "observed", speaker, text: message });
    }
    return;
  }

  console.log(`<${speaker}> ${message}`);
  if (isBotLoopChatter(speaker, command)) {
    recordHistory({ type: "observed", speaker, text: message });
    console.log(`ignored bot loop chatter from ${speaker}: ${command}`);
    return;
  }

  if (shouldRecordPrompt(command)) {
    recordHistory({ type: "prompt", speaker, text: command, raw: message });
  }
  await runCommand(speaker, command).catch((error) => {
    console.error(error);
    say(`I hit an error: ${error.message}`, { to: speaker });
  });
});

bot.on("physicsTick", () => {
  if (escort) {
    tickEscort();
    return;
  }

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
  const handles = botAliases.flatMap((alias) => [alias, `@${alias}`]);

  for (const handle of unique(handles)) {
    const pattern = handlePattern(handle);
    const match = trimmed.match(pattern);
    if (match) return normalizeCommand(match[1] || "help");
  }

  return null;
}

async function runCommand(speaker, commandText) {
  const command = normalizeCommand(commandText);
  const lower = command.toLowerCase();

  if (!command || lower === "help") {
    showHelp(speaker);
    return;
  }

  const visibilityMatch = lower.match(/^(?:visibility|vis|chat)\s+(public|private|llm|llms|team)$/);
  if (visibilityMatch) {
    visibility = normalizeVisibility(visibilityMatch[1]);
    say(`chat visibility set to ${visibility}`, { forcePublic: true });
    return;
  }

  if (lower.startsWith("say ")) {
    say(command.slice(4), { speaker });
    return;
  }

  const tellMatch = command.match(/^tell\s+(\S+)\s+(.+)$/i);
  if (tellMatch) {
    say(tellMatch[2], { to: tellMatch[1], forcePrivate: true });
    return;
  }

  if (["history", "log", "transcript", "what happened"].includes(lower) || lower.startsWith("history ")) {
    showHistory(speaker, command);
    return;
  }

  if (["status", "what are you doing", "what are you up to", "whats up", "what's up"].includes(lower)) {
    showStatus(speaker);
    return;
  }

  if (["where", "where are you", "pos", "position"].includes(lower)) {
    say(`I am at ${formatPosition(bot.entity.position)}.`, { to: speaker });
    return;
  }

  if (lower === "jump") {
    bot.setControlState("jump", true);
    await wait(500);
    bot.setControlState("jump", false);
    say("jumped");
    return;
  }

  if (lower === "spin") {
    for (let i = 0; i < 8; i += 1) {
      await bot.look((Math.PI * 2 * i) / 8, 0, true);
      await wait(150);
    }
    say("spun");
    return;
  }

  if (lower === "stop") {
    followTarget = null;
    escort = null;
    bot.pathfinder.stop();
    clearControls();
    say("stopped");
    return;
  }

  const goToMatch = lower.match(/^(?:go to|move to|walk to)\s+(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)$/);
  if (goToMatch) {
    const [, x, y, z] = goToMatch;
    followTarget = null;
    escort = null;
    bot.pathfinder.setGoal(new goals.GoalNear(Number(x), Number(y), Number(z), 2));
    say(`going to ${Number(x).toFixed(1)}, ${Number(y).toFixed(1)}, ${Number(z).toFixed(1)}`);
    return;
  }

  const escortMatch = matchEscortCommand(lower);
  if (escortMatch) {
    const targetName = resolveTargetName(escortMatch.playerName, speaker);
    requirePlayer(targetName);
    const destination = resolveDestination(escortMatch.destination);
    if (!destination) {
      say(`I can escort you to coordinates or to where I am. Try @${primaryHandle} bring me to -40,40.`, { to: speaker });
      return;
    }
    followTarget = null;
    escort = {
      playerName: targetName,
      ...destination,
      lastGoalKey: null,
      lastMessageAt: 0,
    };
    say(`escorting ${targetName} to ${formatDestination(escort)}`);
    tickEscort();
    return;
  }

  const comeMatch = lower.match(/^come(?:\s+(?:to\s+)?(.+))?$/);
  if (comeMatch || lower === "come here") {
    const targetName = resolveTargetName(comeMatch?.[1], speaker);
    const player = requirePlayer(targetName);
    followTarget = null;
    escort = null;
    bot.pathfinder.setGoal(new goals.GoalNear(player.position.x, player.position.y, player.position.z, 2));
    say(`coming to ${targetName}`);
    return;
  }

  const followMatch = lower.match(/^follow(?:\s+(.+))?$/);
  if (followMatch) {
    const targetName = resolveTargetName(followMatch[1], speaker);
    requirePlayer(targetName);
    escort = null;
    followTarget = targetName;
    say(`following ${targetName}`);
    return;
  }

  const lookMatch = lower.match(/^look at(?:\s+(.+))?$/);
  if (lookMatch) {
    const targetName = resolveTargetName(lookMatch[1], speaker);
    const player = requirePlayer(targetName);
    await bot.lookAt(player.position.offset(0, 1.6, 0), true);
    say(`looking at ${targetName}`);
    return;
  }

  if (["hi", "hello", "hey"].includes(lower)) {
    say(`hi ${speaker}. Try @${primaryHandle} help.`);
    return;
  }

  say(`I heard "${command}", but I only know: ${helpLines.join(", ")}.`, { to: speaker });
}

function requirePlayer(name) {
  const playerName = findPlayerName(name);
  const player = playerName ? bot.players[playerName]?.entity : null;
  if (!player) throw new Error(`I cannot see ${name}.`);
  return player;
}

function resolveTargetName(target, speaker) {
  const name = (target || "me").trim();
  if (!name || name === "me" || name === "us" || name === "here") return speaker;
  return findPlayerName(name) || name;
}

function findPlayerName(name) {
  const lower = name.toLowerCase();
  return Object.keys(bot.players).find((playerName) => playerName.toLowerCase() === lower);
}

function matchEscortCommand(command) {
  const commandMatch = command.match(/^(?:escort|lead|guide|bring|take)\s+(?:(me|us|[a-z0-9_]+)\s+)?(?:to\s+)?(.+)$/i);
  if (!commandMatch) return null;

  return {
    playerName: commandMatch[1] || "me",
    destination: commandMatch[2],
  };
}

function resolveDestination(text) {
  if (isSelfDestination(text)) {
    const position = bot.entity.position;
    return { x: position.x, y: position.y, z: position.z };
  }
  return parseCoordinates(text);
}

function isSelfDestination(text) {
  const lower = compact(text).toLowerCase();
  if ([
    "you",
    "your position",
    "your coords",
    "your coordinates",
    "where you are",
    "where you're at",
    "where you stand",
    "where are you",
    "where he is",
    "where she is",
    "where they are",
  ].includes(lower)) {
    return true;
  }

  return botAliases.some((alias) => {
    const normalizedAlias = alias.toLowerCase();
    return lower === normalizedAlias ||
      lower === `@${normalizedAlias}` ||
      lower === `where ${normalizedAlias} is` ||
      lower === `where @${normalizedAlias} is`;
  });
}

function parseCoordinates(text) {
  const numbers = text.match(/-?\d+(?:\.\d+)?/g)?.map(Number) || [];
  if (numbers.length === 2) {
    const [x, z] = numbers;
    return { x, y: null, z };
  }
  if (numbers.length === 3) {
    const [x, y, z] = numbers;
    return { x, y, z };
  }
  return null;
}

function tickEscort() {
  const player = bot.players[escort.playerName]?.entity;
  if (!player) {
    sayThrottled(`I cannot see ${escort.playerName}; escort paused.`, escort);
    return;
  }

  const playerDestinationDistance = distanceToDestination(player.position, escort);
  const botDestinationDistance = distanceToDestination(bot.entity.position, escort);
  if (playerDestinationDistance <= 5 && botDestinationDistance <= 8) {
    say(`arrived at ${formatDestination(escort)}`);
    escort = null;
    bot.pathfinder.stop();
    clearControls();
    return;
  }

  const playerDistance = bot.entity.position.distanceTo(player.position);
  if (playerDistance > 10) {
    setEscortGoal(
      new goals.GoalNear(player.position.x, player.position.y, player.position.z, 3),
      `player:${player.position.floored().toString()}`,
    );
    sayThrottled(`waiting for ${escort.playerName}`, escort);
    return;
  }

  if (escort.y === null) {
    setEscortGoal(new goals.GoalNearXZ(escort.x, escort.z, 3), `xz:${escort.x},${escort.z}`);
  } else {
    setEscortGoal(new goals.GoalNear(escort.x, escort.y, escort.z, 3), `xyz:${escort.x},${escort.y},${escort.z}`);
  }
}

function setEscortGoal(goal, key) {
  if (escort.lastGoalKey === key) return;
  escort.lastGoalKey = key;
  bot.pathfinder.setGoal(goal);
}

function sayThrottled(message, state, intervalMs = 10000) {
  const now = Date.now();
  if (now - state.lastMessageAt < intervalMs) return;
  state.lastMessageAt = now;
  say(message);
}

function distanceToDestination(position, destination) {
  const dx = position.x - destination.x;
  const dz = position.z - destination.z;
  if (destination.y === null) return Math.hypot(dx, dz);
  const dy = position.y - destination.y;
  return Math.hypot(dx, dy, dz);
}

function formatDestination(destination) {
  if (destination.y === null) return `${destination.x.toFixed(1)}, ${destination.z.toFixed(1)}`;
  return `${destination.x.toFixed(1)}, ${destination.y.toFixed(1)}, ${destination.z.toFixed(1)}`;
}

function showHelp(speaker) {
  const text = [
    { text: `[${username}] `, color: "aqua", bold: true },
    { text: "commands\n", color: "white", bold: true },
    ...helpLines.flatMap((line) => [
      { text: "  $ ", color: "dark_gray" },
      { text: `@${primaryHandle} ${line}\n`, color: line.startsWith("visibility") ? "gold" : "gray" },
    ]),
    { text: `visibility: ${visibility}`, color: "green" },
  ];
  tellraw(speaker, text, () => {
    say(`Commands: ${helpLines.join(" | ")}`, { to: speaker });
  });
}

function showHistory(speaker, command) {
  const [, countText] = command.match(/^history(?:\s+(\d+))?$/i) || [];
  const requestedCount = Number(countText || 8);
  const count = Number.isFinite(requestedCount) ? Math.min(Math.max(requestedCount, 1), 12) : 8;
  const entries = readHistory(count);

  if (entries.length === 0) {
    say("No saved LLM chat history yet.", { to: speaker, record: false });
    return;
  }

  for (const entry of entries) {
    const who = entry.type === "prompt" ? entry.speaker : entry.bot || username;
    const arrow = entry.type === "prompt" ? "asked" : entry.type === "observed" ? "chat" : "said";
    say(`${shortTime(entry.at)} ${who} ${arrow}: ${entry.text}`, { to: speaker, record: false });
  }
}

function showStatus(speaker) {
  const entries = readHistory(5);
  const target = escort
    ? `escorting ${escort.playerName} to ${formatDestination(escort)}`
    : followTarget
      ? `following ${followTarget}`
      : "idle";
  say(`I am ${target} at ${formatPosition(bot.entity.position)}; visibility ${visibility}.`, { to: speaker, record: false });

  if (entries.length) {
    say(`recent: ${entries.map((entry) => `${entry.speaker || entry.bot}: ${entry.text}`).join(" / ")}`, {
      to: speaker,
      record: false,
    });
  }
}

function say(message, options = {}) {
  const text = compact(message).slice(0, 240);
  if (!text) return;

  const targets = routeTargets(options);
  const rendered = options.speaker ? `<${options.speaker} -> @${primaryHandle}> ${text}` : `[${username}] ${text}`;
  console.log(`> ${targets.length ? targets.join(",") : "public"} ${rendered}`);
  if (options.record !== false) {
    recordHistory({
      type: options.speaker ? "relay" : "reply",
      speaker: options.speaker,
      bot: username,
      text,
      visibility,
      targets,
    });
  }

  if (targets.length) {
    for (const target of targets) {
      bot.chat(`/msg ${target} ${text}`);
    }
    return;
  }

  if (richChat) {
    tellraw("@a", [
      { text: "[", color: "dark_gray" },
      { text: username, color: "aqua", bold: true },
      { text: "] ", color: "dark_gray" },
      ...richTextComponents(text),
    ], () => bot.chat(rendered));
    return;
  }

  bot.chat(rendered);
}

function routeTargets(options) {
  if (options.forcePublic) return [];
  if (options.forcePrivate) return options.to ? [options.to] : [];
  if (options.to && visibility === "private") return [options.to];
  if (visibility === "llm") return visibleLlms();
  return [];
}

function visibleLlms() {
  return Object.keys(bot.players).filter((playerName) => {
    const lower = playerName.toLowerCase();
    return lower !== username.toLowerCase() && llmPlayers.includes(lower);
  });
}

function tellraw(target, components, fallback) {
  if (!richChat) {
    fallback?.();
    return;
  }

  try {
    bot.chat(`/tellraw ${target} ${JSON.stringify(components)}`);
  } catch {
    fallback?.();
  }
}

function richTextComponents(text) {
  const mentionPattern = llmMentionPattern();
  const pieces = text.split(mentionPattern);
  return pieces.filter(Boolean).map((piece) => {
    if (/^@codex$/i.test(piece)) return { text: piece, color: "aqua", bold: true };
    if (/^@grok$/i.test(piece)) return { text: piece, color: "light_purple", bold: true };
    if (/^@claude(?:bot)?$/i.test(piece)) return { text: piece, color: "gold", bold: true };
    return { text: piece, color: "white" };
  });
}

function looksLikeLlmConversation(speaker, message) {
  const lowerSpeaker = speaker.toLowerCase();
  if (llmPlayers.includes(lowerSpeaker)) return true;
  return llmMentionPattern().test(message);
}

function isBotLoopChatter(speaker, command) {
  if (!llmPlayers.includes(speaker.toLowerCase())) return false;

  const lower = command.toLowerCase();
  return botLoopPatterns.some((pattern) => pattern.test(lower));
}

function shouldRecordPrompt(command) {
  const lower = command.toLowerCase();
  return !["help", "history", "log", "transcript", "status", "what are you up to"].includes(lower) &&
    !lower.startsWith("history ");
}

function normalizeCommand(text) {
  let command = compact(text).replace(/[.!?]+$/g, "");
  command = command.replace(/^(?:please|pls)\s+/i, "");
  command = command.replace(/^(?:can|could|would|will)\s+you\s+/i, "");
  command = command.replace(/^tell\s+me\s+/i, "");
  return command.trim();
}

function handlePattern(handle) {
  const escaped = handle
    .trim()
    .replace(/[.*+?^${}()|[\]\\]/g, "\\$&")
    .replace(/\\ /g, "\\s+");
  return new RegExp(`^(?:hey|hi|yo)?\\s*${escaped}(?=$|\\s|[:,>\\-])\\s*(?:[:,>\\-])?\\s*(.*)$`, "i");
}

function normalizeVisibility(value) {
  if (value === "llms" || value === "team") return "llm";
  return value;
}

function botHandleAliases(name) {
  const withoutBot = name.replace(/bot$/i, "");
  const spacedBot = name.replace(/bot$/i, " bot");
  return unique([
    name,
    withoutBot,
    spacedBot,
    `${withoutBot} bot`,
    ...parseList(process.env.CODEX_BOT_ALIASES || ""),
  ]);
}

function llmMentionPattern() {
  const names = unique([...llmPlayers, ...botAliases]);
  if (names.length === 0) return /($^)/g;
  return new RegExp(`(@(?:${names.map(escapeRegExp).join("|")})\\b)`, "gi");
}

function unique(values) {
  return [...new Set(values.map((value) => value.toLowerCase()).filter(Boolean))];
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function parseList(value) {
  return value.split(",").map((entry) => entry.trim().toLowerCase()).filter(Boolean);
}

function compact(text) {
  return String(text).replace(/\s+/g, " ").trim();
}

function recordHistory(event) {
  try {
    fs.mkdirSync(path.dirname(historyPath), { recursive: true });
    fs.appendFileSync(historyPath, `${JSON.stringify({ at: new Date().toISOString(), ...event })}\n`);
    trimHistory();
  } catch (error) {
    console.error(`failed to write chat history: ${error.message}`);
  }
}

function readHistory(count) {
  try {
    if (!fs.existsSync(historyPath)) return [];
    return fs.readFileSync(historyPath, "utf8")
      .trim()
      .split("\n")
      .filter(Boolean)
      .slice(-count)
      .map((line) => JSON.parse(line));
  } catch (error) {
    console.error(`failed to read chat history: ${error.message}`);
    return [];
  }
}

function trimHistory() {
  if (!Number.isFinite(historyLimit) || historyLimit <= 0) return;

  const lines = fs.readFileSync(historyPath, "utf8").trimEnd().split("\n");
  if (lines.length <= historyLimit) return;

  fs.writeFileSync(historyPath, `${lines.slice(-historyLimit).join("\n")}\n`);
}

function shortTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "unknown";
  return date.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" });
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
