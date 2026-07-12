#!/usr/bin/env node
import { AsyncLocalStorage } from "node:async_hooks";
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { Vec3 } from "vec3";
import mineflayer from "../mindcraft/upstream/node_modules/mineflayer/index.js";
import pathfinderPackage from "../mindcraft/upstream/node_modules/mineflayer-pathfinder/index.js";

const { pathfinder, Movements, goals } = pathfinderPackage;

const host = process.env.MINECRAFT_HOST || "192.168.86.188";
const port = Number(process.env.MINECRAFT_PORT || 25565);
const username = process.env.CODEX_BOT_USERNAME || "codex";
const version = process.env.MINECRAFT_VERSION || "1.21.6";
const richChat = process.env.CODEX_RICH_CHAT === "1";
const llmPlayers = parseList(process.env.CODEX_LLM_PLAYERS || "codex,claude,claudebot,grok");
const ignoredSpeakers = parseList(process.env.CODEX_IGNORED_SPEAKERS || "codexdrone1,codexdrone2,codexdrone3,codexdrone4,codexboss");
const historyPath = process.env.CODEX_CHAT_HISTORY || ".codex-runtime/chat-history.jsonl";
const historyLimit = Number(process.env.CODEX_CHAT_HISTORY_LIMIT || 2000);
const swarmRuntimeDir = process.env.CODEX_SWARM_RUNTIME || ".codex-runtime/swarm";
const swarmStatePath = path.join(swarmRuntimeDir, "state.json");
const delegatedDroneName = process.env.CODEX_ARCHITECT_DRONE || "CodexDrone1";
const buildCommandDelayMs = Number(process.env.CODEX_BUILD_COMMAND_DELAY_MS || 150);
const openaiApiKey = process.env.OPENAI_API_KEY || "";
const commandModel = process.env.CODEX_COMMAND_MODEL || "gpt-5-mini";
const commandApiTimeoutMs = Number(process.env.CODEX_COMMAND_API_TIMEOUT_MS || 3500);
const chatModel = process.env.CODEX_CHAT_MODEL || commandModel;
const chatApiTimeoutMs = Number(process.env.CODEX_CHAT_API_TIMEOUT_MS || 8000);
const codexCliPath = process.env.CODEX_COMMAND_CLI || "/opt/homebrew/bin/codex";
const codexCliEnabled = process.env.CODEX_COMMAND_CLI_ENABLED !== "0";
const codexCliTimeoutMs = Number(process.env.CODEX_COMMAND_CLI_TIMEOUT_MS || 12000);
const codexChatCliEnabled = process.env.CODEX_CHAT_CLI_ENABLED !== "0";
const codexChatCliTimeoutMs = Number(process.env.CODEX_CHAT_CLI_TIMEOUT_MS || 12000);
const commandContext = new AsyncLocalStorage();
const botAliases = botHandleAliases(username);
const primaryHandle = botAliases[0] || username;
const helpLines = [
  "help",
  "history [count]",
  "status",
  "visibility public|private|llm|alone",
  "say <text>",
  "tell <player> <text>",
  "where",
  "come [player]",
  "follow [player|me]",
  "escort me to <x>,<z>",
  "look at [player|me]",
  "what am I looking at",
  "keep building this red thing",
  "go to <x> <y> <z>",
  "build castle wall with two towers",
  "build fortress here",
  "delete this castle",
  "burn this castle with lava",
  "freeze this tower",
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
  say(`${username} online. Tag @${primaryHandle} help. Chat visibility is ${visibility}.`);
  console.log(`${username} joined ${host}:${port} at ${bot.entity.position}`);
});

bot.on("chat", async (speaker, message) => {
  if (speaker === bot.username) return;
  if (isIgnoredSpeaker(speaker)) {
    if (visibility !== "alone") {
      recordHistory({ type: "observed", speaker, text: message });
    }
    return;
  }

  const command = addressedCommand(message);
  if (!command) {
    if (shouldRecordAmbientChat(speaker, message)) {
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
  await commandContext.run({ speaker }, () => runCommand(speaker, command)).catch((error) => {
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

  const visibilityMatch = lower.match(/^(?:visibility|vis|chat)\s+(public|private|alone|llm|llms|team)$/);
  if (visibilityMatch) {
    visibility = normalizeVisibility(visibilityMatch[1]);
    const message = visibility === "alone"
      ? "chat visibility set to alone; I will whisper you and keep LLM/build-bot noise out of my history/status"
      : `chat visibility set to ${visibility}`;
    say(message, visibility === "alone" ? { to: speaker, forcePrivate: true } : { forcePublic: true });
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

  if (isCapabilityQuestion(lower)) {
    say(`yes. I can read what you're looking at, inspect nearby blocks/colors, continue visible builds like "keep building this red thing", and transform structures with commands like "burn this castle with lava".`, { to: speaker });
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
    await moveDirect(Number(x), Number(y), Number(z));
    say(`going to ${Number(x).toFixed(1)}, ${Number(y).toFixed(1)}, ${Number(z).toFixed(1)}`);
    return;
  }

  const escortMatch = matchEscortCommand(lower);
  if (escortMatch) {
    const targetName = resolveTargetName(escortMatch.playerName, speaker);
    await resolvePlayerPosition(targetName, { teleportToPlayer: false });
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
  if (comeMatch || lower === "come here" || lower === "here") {
    const targetName = resolveTargetName(comeMatch?.[1], speaker);
    followTarget = null;
    escort = null;
    const targetPosition = await resolvePlayerPosition(targetName, { teleportToPlayer: true });
    if (!targetPosition.teleported) {
      await moveDirect(targetPosition.x, targetPosition.y + 1, targetPosition.z);
    }
    say(`coming to ${targetName}`);
    return;
  }

  const followMatch = lower.match(/^follow(?:\s+(.+))?$/);
  if (followMatch) {
    const targetName = resolveTargetName(followMatch[1], speaker);
    await resolvePlayerPosition(targetName, { teleportToPlayer: true });
    escort = null;
    followTarget = targetName;
    say(`following ${targetName}`);
    return;
  }

  const lookMatch = lower.match(/^look at(?:\s+(.+))?$/);
  if (lookMatch) {
    const targetName = resolveTargetName(lookMatch[1], speaker);
    const position = await resolvePlayerPosition(targetName, { teleportToPlayer: false });
    await bot.lookAt(new Vec3(position.x, position.y + 1.6, position.z), true);
    say(`looking at ${targetName}`);
    return;
  }

  if (isContextExtendCommand(lower)) {
    await runContextExtendCommand(speaker, command);
    return;
  }

  if (isInspectContextCommand(lower)) {
    await inspectPhysicalContext(speaker, command);
    return;
  }

  const architectCommand = interpretArchitectCommand(lower) ||
    await interpretArchitectCommandWithOpenAI(command, speaker) ||
    await interpretArchitectCommandWithCodexCli(command, speaker);
  if (architectCommand) {
    await runArchitectCommand(speaker, architectCommand, command);
    return;
  }

  if (["hi", "hello", "hey"].includes(lower)) {
    say(`hi ${speaker}. Try @${primaryHandle} help.`);
    return;
  }

  if (["why", "lmao", "lol"].includes(lower)) {
    say(`yeah, that was a bad command path. I'm online now; try @${primaryHandle} come here or point at a build and say @${primaryHandle} do you see this.`, { to: speaker });
    return;
  }

  await answerGeneralChat(speaker, command);
}

function isCapabilityQuestion(lower) {
  return /\b(?:are you smart(?: yet| now)?|smart yet|smart now|what can you do|what do you understand|can you see|do you understand this|are you smarter)\b/.test(lower);
}

async function answerGeneralChat(speaker, command) {
  const answer = await answerGeneralChatWithOpenAI(speaker, command) ||
    await answerGeneralChatWithCodexCli(speaker, command) ||
    localGeneralChatAnswer(speaker, command);
  say(answer, { to: speaker });
}

async function answerGeneralChatWithOpenAI(speaker, command) {
  if (!openaiApiKey) return null;

  const prompt = generalChatPrompt(speaker, command);
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), chatApiTimeoutMs);
    const response = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${openaiApiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: chatModel,
        input: prompt,
        reasoning: { effort: "minimal" },
        max_output_tokens: 120,
        store: false,
      }),
      signal: controller.signal,
    }).finally(() => clearTimeout(timeout));

    if (!response.ok) {
      const body = await response.text().catch(() => "");
      console.error(`OpenAI chat failed: ${response.status} ${body.slice(0, 300)}`);
      return null;
    }

    return cleanChatAnswer(extractResponseText(await response.json()));
  } catch (error) {
    console.error(`OpenAI chat error: ${error.message}`);
    return null;
  }
}

async function answerGeneralChatWithCodexCli(speaker, command) {
  if (!codexCliEnabled || !codexChatCliEnabled) return null;

  const outputPath = path.join(os.tmpdir(), `codex-chat-${process.pid}-${Date.now()}.txt`);
  try {
    await runCodexCli(generalChatPrompt(speaker, command), outputPath, codexChatCliTimeoutMs);
    return cleanChatAnswer(fs.readFileSync(outputPath, "utf8"));
  } catch (error) {
    console.error(`Codex CLI chat error: ${error.message}`);
    return null;
  } finally {
    fs.rmSync(outputPath, { force: true });
  }
}

function generalChatPrompt(speaker, command) {
  const recent = readHistory(8)
    .map((entry) => `${entry.speaker || entry.bot || "chat"}: ${entry.text}`)
    .join("\n");
  const position = bot.entity?.position ? formatPosition(bot.entity.position) : "unknown";
  return [
    "You are codex, an AI living inside a Minecraft world.",
    "Answer the addressed player naturally, like a capable in-game companion.",
    "Keep it concise: one sentence, under 180 characters if possible.",
    "Do not list a help menu unless they asked for help.",
    "If they ask whether you are smart/capable, answer confidently and mention you can chat, move, inspect what they point at, and build/transform structures.",
    `Your in-game position: ${position}`,
    recent ? `Recent chat:\n${recent}` : "Recent chat: none",
    `${speaker}: ${command}`,
    "codex:",
  ].join("\n");
}

function cleanChatAnswer(text) {
  const answer = compact(String(text || "")
    .replace(/^codex:\s*/i, "")
    .replace(/^["']|["']$/g, ""));
  if (!answer) return null;
  return answer.slice(0, 220);
}

function localGeneralChatAnswer(speaker, command) {
  const lower = command.toLowerCase();
  if (/\b(thanks|thank you|ty)\b/.test(lower)) return `anytime, ${speaker}.`;
  if (/\b(sorry|my bad)\b/.test(lower)) return `all good. I'm here and listening.`;
  if (/\b(hello|hi|hey|yo)\b/.test(lower)) return `hey ${speaker}. I can chat normally now, and I can act on what you point at in-world.`;
  if (/\b(are you smart|smart|capable|can you think)\b/.test(lower)) {
    return `yes. I can chat, move, inspect what you're looking at, and build or transform structures from natural language.`;
  }
  if (/\?$/.test(command)) return `reasonable question. I can answer better with the chat model connected; in-world, I can still inspect, move, build, and transform things.`;
  return `heard you. I'm not going to dump a help menu; talk to me normally or point at something and tell me what to do with it.`;
}

function interpretArchitectCommand(lower) {
  const effectCommand = interpretEffectCommand(lower);
  if (effectCommand) return effectCommand;

  if (/\b(delete|destroy|demolish|erase|wipe|nuke|trash|remove|obliterate)\b/.test(lower) &&
      /\b(castle|fortress|fort|tower|base|build|building|structure|dumpster)\b/.test(lower)) {
    return { action: "demolish" };
  }

  if (/\b(build|make|create|construct|spawn|summon|freestyle)\b/.test(lower) &&
      /\b(wall|walls|rampart|battlement|battlements)\b/.test(lower) &&
      /\b(castle|fortress|fort|two towers|towers)\b/.test(lower)) {
    return { action: "castle_wall" };
  }

  if (/\b(build|make|create|construct|spawn|summon|freestyle)\b/.test(lower) &&
      /\b(castle|fortress|fort|citadel|stronghold|keep)\b/.test(lower)) {
    return { action: "fortress" };
  }

  if (/\b(repair|fix|upgrade|enhance|make.*sick|make.*badass)\b/.test(lower) &&
      /\b(castle|fortress|fort|base|this|here)\b/.test(lower)) {
    return { action: "fortress" };
  }

  return null;
}

function interpretEffectCommand(lower) {
  const targetsStructure = /\b(this|that|nearby|castle|fortress|fort|tower|base|build|building|structure|house|village|wall|walls|area|place|thing)\b/.test(lower);
  if (!targetsStructure) return null;

  if (/\b(burn|ignite|torch|melt|scorch|incinerate|set\s+.*on\s+fire|lava|volcano|hellscape)\b/.test(lower)) {
    return { action: "effect", effect: "lava_burn" };
  }

  if (/\b(flood|drown|submerge|soak|waterlog|water)\b/.test(lower)) {
    return { action: "effect", effect: "flood" };
  }

  if (/\b(freeze|ice|snow|blizzard|frost)\b/.test(lower)) {
    return { action: "effect", effect: "freeze" };
  }

  if (/\b(curse|haunt|spooky|evil|corrupt|darken|doom|ruin)\b/.test(lower)) {
    return { action: "effect", effect: "curse" };
  }

  return null;
}

async function interpretArchitectCommandWithOpenAI(command, speaker) {
  if (!openaiApiKey || !shouldUseLlmArchitectParser(command)) return null;

  const player = bot.players[speaker]?.entity;
  const playerPosition = player ? formatPosition(player.position) : "unknown";
  const prompt = [
    "You normalize Minecraft chat commands for a godmode building bot named codex.",
    "Return only compact JSON. No markdown.",
    "Allowed JSON shapes:",
    '{"action":"demolish"} for requests to delete, destroy, wipe, erase, remove, trash, or nuke a nearby castle/fortress/building/structure.',
    '{"action":"effect","effect":"lava_burn"} for requests to burn, ignite, scorch, melt, incinerate, or pour lava on a nearby structure.',
    '{"action":"effect","effect":"flood"} for requests to flood, drown, submerge, soak, or waterlog a nearby structure.',
    '{"action":"effect","effect":"freeze"} for requests to freeze, ice over, snow over, frost, or blizzard a nearby structure.',
    '{"action":"effect","effect":"curse"} for requests to curse, haunt, corrupt, darken, or make a nearby structure spooky/evil.',
    '{"action":"castle_wall"} for requests to build a castle/fortress wall, rampart, battlement, or a wall with two towers.',
    '{"action":"fortress"} for requests to build, make, create, summon, upgrade, or freestyle a castle/fortress/fort/keep here.',
    '{"action":"none"} for anything else.',
    "Never include coordinates unless the user explicitly typed them. Do not invent new action or effect names.",
    `Speaker: ${speaker}`,
    `Speaker position: ${playerPosition}`,
    `Command: ${JSON.stringify(command)}`,
  ].join("\n");

  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), commandApiTimeoutMs);
    const response = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${openaiApiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: commandModel,
        input: prompt,
        reasoning: { effort: "minimal" },
        max_output_tokens: 80,
        store: false,
      }),
      signal: controller.signal,
    }).finally(() => clearTimeout(timeout));

    if (!response.ok) {
      const body = await response.text().catch(() => "");
      console.error(`OpenAI command normalization failed: ${response.status} ${body.slice(0, 300)}`);
      return null;
    }

    const data = await response.json();
    const text = extractResponseText(data);
    const parsed = parseJsonObject(text);
    if (isValidArchitectAction(parsed)) {
      console.log(`OpenAI normalized "${command}" -> ${parsed.action}`);
      return parsed.action === "effect" ? { action: "effect", effect: parsed.effect } : { action: parsed.action };
    }
  } catch (error) {
    console.error(`OpenAI command normalization error: ${error.message}`);
  }

  return null;
}

async function interpretArchitectCommandWithCodexCli(command, speaker) {
  if (!codexCliEnabled || !shouldUseLlmArchitectParser(command)) return null;

  const outputPath = path.join(os.tmpdir(), `codex-command-${process.pid}-${Date.now()}.json`);
  const player = bot.players[speaker]?.entity;
  const playerPosition = player ? formatPosition(player.position) : "unknown";
  const prompt = [
    "Return only compact JSON. No markdown.",
    "Classify this Minecraft command for a godmode building bot named codex.",
    "Allowed outputs:",
    '{"action":"demolish"} for requests to delete, destroy, wipe, erase, remove, trash, or nuke a nearby castle/fortress/building/structure.',
    '{"action":"effect","effect":"lava_burn"} for requests to burn, ignite, scorch, melt, incinerate, or pour lava on a nearby structure.',
    '{"action":"effect","effect":"flood"} for requests to flood, drown, submerge, soak, or waterlog a nearby structure.',
    '{"action":"effect","effect":"freeze"} for requests to freeze, ice over, snow over, frost, or blizzard a nearby structure.',
    '{"action":"effect","effect":"curse"} for requests to curse, haunt, corrupt, darken, or make a nearby structure spooky/evil.',
    '{"action":"castle_wall"} for requests to build a castle/fortress wall, rampart, battlement, or a wall with two towers.',
    '{"action":"fortress"} for requests to build, make, create, summon, upgrade, or freestyle a castle/fortress/fort/keep here.',
    '{"action":"none"} for anything else.',
    "Do not invent other actions or effects.",
    `Speaker: ${speaker}`,
    `Speaker position: ${playerPosition}`,
    `Command: ${JSON.stringify(command)}`,
  ].join("\n");

  try {
    await runCodexCli(prompt, outputPath, codexCliTimeoutMs);

    const parsed = parseJsonObject(fs.readFileSync(outputPath, "utf8"));
    if (isValidArchitectAction(parsed)) {
      console.log(`Codex CLI normalized "${command}" -> ${parsed.action}`);
      return parsed.action === "effect" ? { action: "effect", effect: parsed.effect } : { action: parsed.action };
    }
  } catch (error) {
    console.error(`Codex CLI command normalization error: ${error.message}`);
  } finally {
    fs.rmSync(outputPath, { force: true });
  }

  return null;
}

function shouldUseLlmArchitectParser(command) {
  return /\b(build|make|create|construct|spawn|summon|freestyle|upgrade|enhance|repair|fix|delete|destroy|demolish|erase|wipe|nuke|trash|remove|obliterate|burn|ignite|torch|melt|scorch|incinerate|lava|flood|drown|submerge|waterlog|freeze|ice|snow|blizzard|frost|curse|haunt|spooky|evil|corrupt|darken|castle|fortress|fort|tower|towers|wall|walls|rampart|battlement|base|building|structure|house|village|area|dumpster|badass|sick|ugly|beneath|nearby|here)\b/i.test(command);
}

function isValidArchitectAction(parsed) {
  if (["demolish", "fortress", "castle_wall"].includes(parsed?.action)) return true;
  return parsed?.action === "effect" && ["lava_burn", "flood", "freeze", "curse"].includes(parsed.effect);
}

function extractResponseText(data) {
  if (typeof data.output_text === "string") return data.output_text;
  const parts = [];
  for (const item of data.output || []) {
    for (const content of item.content || []) {
      if (typeof content.text === "string") parts.push(content.text);
    }
  }
  return parts.join("\n");
}

function parseJsonObject(text) {
  const trimmed = String(text || "").trim();
  if (!trimmed) return null;
  try {
    return JSON.parse(trimmed);
  } catch {
    const match = trimmed.match(/\{[\s\S]*\}/);
    if (!match) return null;
    try {
      return JSON.parse(match[0]);
    } catch {
      return null;
    }
  }
}

function runCodexCli(prompt, outputPath, timeoutMs) {
  return new Promise((resolve, reject) => {
    const child = spawn(codexCliPath, [
      "exec",
      "--ephemeral",
      "--ignore-rules",
      "-s",
      "read-only",
      "--output-last-message",
      outputPath,
      prompt,
    ], {
      cwd: process.cwd(),
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stderr = "";
    let stdout = "";
    const timeout = setTimeout(() => {
      child.kill("SIGTERM");
      reject(new Error(`timed out after ${timeoutMs}ms`));
    }, timeoutMs);

    child.stdout.on("data", (chunk) => {
      stdout += chunk.toString();
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString();
    });
    child.on("error", (error) => {
      clearTimeout(timeout);
      reject(error);
    });
    child.on("close", (code) => {
      clearTimeout(timeout);
      if (code === 0) {
        resolve({ stdout, stderr });
        return;
      }
      reject(new Error(`exited ${code}: ${(stderr || stdout).slice(0, 500)}`));
    });
  });
}

async function runArchitectCommand(speaker, command, originalText = "") {
  const playerPosition = await resolvePlayerPosition(speaker, { teleportToPlayer: true });
  const origin = await resolveArchitectOrigin(speaker, command, originalText, playerPosition);

  if (command.action === "demolish") {
    say(`demolishing nearby structure around ${formatBlockPosition(origin)}`);
    await executeDemolition(origin);
    say(`demolition complete around ${formatBlockPosition(origin)}`);
    return;
  }

  if (command.action === "effect") {
    const effect = command.effect || "lava_burn";
    say(`${effectDescription(effect)} around ${formatBlockPosition(origin)}`);
    await executeStructureEffect(origin, effect);
    say(`${effectStatus(effect)} around ${formatBlockPosition(origin)}`);
    return;
  }

  if (command.action === "fortress") {
    const buildOrigin = { x: origin.x, y: origin.y - 8, z: origin.z };
    say(`freestyling a fortress around ${formatBlockPosition(buildOrigin)}`);
    await executeFortress(buildOrigin);
    say(`fortress pass complete around ${formatBlockPosition(buildOrigin)}`);
    return;
  }

  if (command.action === "castle_wall") {
    const buildOrigin = { x: origin.x, y: origin.y - 1, z: origin.z + 12 };
    say(`building a detailed castle wall at ${formatBlockPosition(buildOrigin)}; delegating the east tower to ${delegatedDroneName}`);
    await executeDelegatedCastleWall(buildOrigin);
    say(`main wall and west tower are placed; ${delegatedDroneName} has the east tower job.`);
  }
}

async function resolveArchitectOrigin(speaker, command, originalText, playerPosition) {
  if (!usesPhysicalReference(originalText) || !["demolish", "effect"].includes(command.action)) {
    return blockPosition(playerPosition);
  }

  try {
    const context = await resolvePhysicalContext(speaker, originalText);
    if (context.focus?.position) return context.focus.position;
  } catch (error) {
    console.error(`physical context unavailable: ${error.message}`);
  }

  return blockPosition(playerPosition);
}

function usesPhysicalReference(text) {
  return /\b(this|that|these|those|it|thing|what i'?m looking at|red|blue|green|yellow|white|black|purple|orange|pink|gray|grey|cyan|lime)\b/i.test(text);
}

async function executeStructureEffect(origin, effect) {
  const commands = structureEffectCommands(origin, effect);
  await sendCommandBatch(commands);
}

function structureEffectCommands(origin, effect) {
  const x0 = origin.x - 18;
  const x1 = origin.x + 18;
  const z0 = origin.z - 18;
  const z1 = origin.z + 18;
  const y0 = origin.y - 4;
  const y1 = origin.y + 24;
  const commands = [];

  if (effect === "flood") {
    commands.push(fillCommand(x0, y0, z0, x1, y0, z1, "water"));
    commands.push(fillCommand(x0, origin.y + 2, z0, x1, origin.y + 3, z1, "water"));
    commands.push(fillCommand(origin.x - 8, origin.y + 8, origin.z - 8, origin.x + 8, origin.y + 9, origin.z + 8, "water"));
    commands.push(fillCommand(x0, origin.y, z0, x1, origin.y + 8, z0, "blue_stained_glass"));
    commands.push(fillCommand(x0, origin.y, z1, x1, origin.y + 8, z1, "blue_stained_glass"));
    commands.push(fillCommand(x0, origin.y, z0, x0, origin.y + 8, z1, "blue_stained_glass"));
    commands.push(fillCommand(x1, origin.y, z0, x1, origin.y + 8, z1, "blue_stained_glass"));
    return commands;
  }

  if (effect === "freeze") {
    commands.push(fillCommand(x0, y0, z0, x1, y0, z1, "packed_ice"));
    commands.push(fillCommand(x0, origin.y + 1, z0, x1, origin.y + 1, z1, "snow_block"));
    commands.push(fillCommand(x0, origin.y + 10, z0, x1, origin.y + 10, z1, "ice"));
    for (let x = x0; x <= x1; x += 6) {
      commands.push(fillCommand(x, origin.y + 2, z0, x, y1, z0, "blue_ice"));
      commands.push(fillCommand(x, origin.y + 2, z1, x, y1, z1, "blue_ice"));
    }
    for (let z = z0; z <= z1; z += 6) {
      commands.push(fillCommand(x0, origin.y + 2, z, x0, y1, z, "blue_ice"));
      commands.push(fillCommand(x1, origin.y + 2, z, x1, y1, z, "blue_ice"));
    }
    return commands;
  }

  if (effect === "curse") {
    commands.push(fillCommand(x0, y0, z0, x1, y0, z1, "blackstone"));
    commands.push(fillCommand(x0, origin.y, z0, x1, origin.y + 1, z0, "soul_sand"));
    commands.push(fillCommand(x0, origin.y, z1, x1, origin.y + 1, z1, "soul_sand"));
    commands.push(fillCommand(x0, origin.y, z0, x0, origin.y + 1, z1, "soul_sand"));
    commands.push(fillCommand(x1, origin.y, z0, x1, origin.y + 1, z1, "soul_sand"));
    for (const [dx, dz] of [[-14, -14], [14, -14], [-14, 14], [14, 14], [0, -18], [0, 18]]) {
      commands.push(fillCommand(origin.x + dx, origin.y, origin.z + dz, origin.x + dx, origin.y + 10, origin.z + dz, "obsidian"));
      commands.push(setBlockCommand(origin.x + dx, origin.y + 11, origin.z + dz, "soul_fire"));
    }
    commands.push(fillCommand(origin.x - 5, origin.y + 2, origin.z - 5, origin.x + 5, origin.y + 8, origin.z + 5, "purple_stained_glass"));
    return commands;
  }

  commands.push(fillCommand(x0, y0, z0, x1, y0, z1, "magma_block"));
  commands.push(fillCommand(x0, origin.y + 1, z0, x1, origin.y + 1, z1, "fire"));
  commands.push(fillCommand(origin.x - 9, origin.y + 12, origin.z - 9, origin.x + 9, origin.y + 12, origin.z + 9, "lava"));
  commands.push(fillCommand(origin.x - 4, origin.y + 18, origin.z - 4, origin.x + 4, origin.y + 18, origin.z + 4, "lava"));
  commands.push(fillCommand(x0, origin.y, z0, x1, origin.y + 4, z0, "magma_block"));
  commands.push(fillCommand(x0, origin.y, z1, x1, origin.y + 4, z1, "magma_block"));
  commands.push(fillCommand(x0, origin.y, z0, x0, origin.y + 4, z1, "magma_block"));
  commands.push(fillCommand(x1, origin.y, z0, x1, origin.y + 4, z1, "magma_block"));
  for (const [dx, dz] of [[-16, -16], [16, -16], [-16, 16], [16, 16], [0, 0]]) {
    commands.push(fillCommand(origin.x + dx, origin.y, origin.z + dz, origin.x + dx, origin.y + 12, origin.z + dz, "lava"));
  }
  return commands;
}

function effectDescription(effect) {
  return {
    lava_burn: "pouring lava and fire over the nearby structure",
    flood: "flooding the nearby structure",
    freeze: "freezing the nearby structure",
    curse: "cursing the nearby structure",
  }[effect] || "transforming the nearby structure";
}

function effectStatus(effect) {
  return {
    lava_burn: "lava burn complete",
    flood: "flood pass complete",
    freeze: "freeze pass complete",
    curse: "curse pass complete",
  }[effect] || "effect complete";
}

function isInspectContextCommand(lower) {
  return /^(?:do you see|can you see|what(?:'s| is) this|what am i looking at|what is that|inspect this|look at this)\b/.test(lower) ||
    /\b(?:see|inspect|describe)\s+(?:what i'?m looking at|this|that|this castle|this thing|the red thing)\b/.test(lower);
}

function isContextExtendCommand(lower) {
  return /\b(?:keep building|continue|extend|finish|complete|copy|grow)\b/.test(lower) &&
    /\b(?:it|this|that|thing|build|building|wall|tower|castle|structure|red|blue|green|yellow|white|black|purple|orange|pink|gray|grey|cyan|lime)\b/.test(lower);
}

async function inspectPhysicalContext(speaker, command) {
  const context = await resolvePhysicalContext(speaker, command);
  if (!context.focus) {
    say(`I cannot lock onto a block from your view yet. Stand closer or look directly at the thing.`, { to: speaker });
    return;
  }

  const blockText = context.lookedBlock
    ? `${context.lookedBlock.name} at ${formatBlockPosition(context.lookedBlock.position)}`
    : `near ${formatBlockPosition(context.focus.position)}`;
  const paletteText = context.palette.length
    ? `nearby blocks: ${context.palette.slice(0, 5).map((entry) => `${entry.name} x${entry.count}`).join(", ")}`
    : "no nearby solid block cluster";
  const colorText = context.colors.length
    ? `colors: ${context.colors.slice(0, 4).map((entry) => `${entry.color} x${entry.count}`).join(", ")}`
    : "no obvious color";

  say(`I see ${blockText}; ${paletteText}; ${colorText}.`, { to: speaker });
}

async function runContextExtendCommand(speaker, command) {
  const context = await resolvePhysicalContext(speaker, command);
  if (!context.focus) {
    say(`I need you to look directly at the thing to continue it.`, { to: speaker });
    return;
  }

  const selected = chooseContextBlock(context, command);
  if (!selected) {
    say(`I can see the area, but I cannot find a solid block to continue.`, { to: speaker });
    return;
  }

  const extension = contextExtensionCommands(context, selected);
  say(`continuing ${selected.name} from ${formatBlockPosition(selected.position)} toward ${extension.directionText}`);
  await sendCommandBatch(extension.commands);
  say(`continued ${selected.name} with ${extension.commands.length} placement commands`);
}

async function resolvePhysicalContext(speaker, command) {
  const player = await resolveVisiblePlayer(speaker);
  await makeContextVisible(player, speaker);
  const refreshedName = findPlayerName(speaker) || speaker;
  const refreshed = bot.players[refreshedName]?.entity || player;
  const lookedBlock = findLookedBlock(refreshed, command);
  const focus = lookedBlock || nearestSolidBlockAround(refreshed.position, 10);
  const blocks = focus ? scanBlocksAround(focus.position, 6, 5) : [];
  return {
    speaker,
    command,
    player: refreshed,
    playerPosition: vectorPosition(refreshed.position),
    lookedBlock,
    focus,
    blocks,
    palette: summarizeBlocks(blocks),
    colors: summarizeColors(blocks),
  };
}

async function resolveVisiblePlayer(speaker) {
  const playerName = findPlayerName(speaker) || speaker;
  let player = bot.players[playerName]?.entity;
  if (player?.position) return player;

  await resolvePlayerPosition(playerName, { teleportToPlayer: true });
  await wait(250);
  player = bot.players[playerName]?.entity;
  if (!player?.position) throw new Error(`I cannot see ${speaker} well enough to read what they are looking at.`);
  return player;
}

async function makeContextVisible(player, speaker) {
  if (!bot.entity?.position || bot.entity.position.distanceTo(player.position) <= 32) return;
  bot.chat(`/tp ${username} ${findPlayerName(speaker) || speaker}`);
  await wait(Number(process.env.CODEX_PLAYER_TP_WAIT_MS || 700));
}

function findLookedBlock(player, command) {
  const origin = player.position.offset(0, 1.62, 0);
  const direction = lookDirection(player);
  const maxDistance = mentionsFarTarget(command) ? 96 : 64;
  let previousKey = "";

  for (let distance = 1.5; distance <= maxDistance; distance += 0.25) {
    const position = origin.plus(direction.scaled(distance));
    const flooredPosition = position.floored();
    const key = flooredPosition.toString();
    if (key === previousKey) continue;
    previousKey = key;
    const block = bot.blockAt(flooredPosition, false);
    if (isSolidContextBlock(block)) {
      return {
        name: block.name,
        displayName: block.displayName,
        position: blockPosition(block.position || flooredPosition),
      };
    }
  }
  return null;
}

function lookDirection(entity) {
  const yaw = Number(entity.yaw || 0);
  const pitch = Number(entity.pitch || 0);
  const cosPitch = Math.cos(pitch);
  return new Vec3(
    -Math.sin(yaw) * cosPitch,
    -Math.sin(pitch),
    -Math.cos(yaw) * cosPitch,
  );
}

function mentionsFarTarget(command) {
  return /\b(castle|tower|fortress|wall|base|building|structure)\b/i.test(command);
}

function nearestSolidBlockAround(position, radius) {
  const base = blockPosition(position);
  let best = null;
  let bestDistance = Infinity;
  for (let y = base.y - 2; y <= base.y + 5; y += 1) {
    for (let z = base.z - radius; z <= base.z + radius; z += 1) {
      for (let x = base.x - radius; x <= base.x + radius; x += 1) {
        const block = bot.blockAt(new Vec3(x, y, z), false);
        if (!isSolidContextBlock(block)) continue;
        const distance = Math.hypot(x - position.x, y - position.y, z - position.z);
        if (distance < bestDistance) {
          bestDistance = distance;
          best = { name: block.name, displayName: block.displayName, position: { x, y, z } };
        }
      }
    }
  }
  return best;
}

function scanBlocksAround(center, radius, verticalRadius) {
  const blocks = [];
  for (let y = center.y - verticalRadius; y <= center.y + verticalRadius; y += 1) {
    for (let z = center.z - radius; z <= center.z + radius; z += 1) {
      for (let x = center.x - radius; x <= center.x + radius; x += 1) {
        const block = bot.blockAt(new Vec3(x, y, z), false);
        if (!isSolidContextBlock(block)) continue;
        blocks.push({ name: block.name, displayName: block.displayName, position: { x, y, z } });
      }
    }
  }
  return blocks;
}

function summarizeBlocks(blocks) {
  return Object.entries(countBy(blocks.map((block) => block.name)))
    .map(([name, count]) => ({ name, count }))
    .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name));
}

function summarizeColors(blocks) {
  return Object.entries(countBy(blocks.map((block) => colorForBlock(block.name)).filter(Boolean)))
    .map(([color, count]) => ({ color, count }))
    .sort((a, b) => b.count - a.count || a.color.localeCompare(b.color));
}

function chooseContextBlock(context, command) {
  const requestedColor = requestedBlockColor(command);
  if (!requestedColor && context.lookedBlock) return context.lookedBlock;

  const candidates = requestedColor
    ? context.blocks.filter((block) => colorForBlock(block.name) === requestedColor)
    : context.blocks;
  if (candidates.length === 0) return context.lookedBlock || context.focus;

  const counts = countBy(candidates.map((block) => block.name));
  const dominantName = Object.entries(counts).sort((a, b) => b[1] - a[1])[0]?.[0];
  const dominantBlocks = candidates.filter((block) => block.name === dominantName);
  return nearestBlockTo(dominantBlocks, context.lookedBlock?.position || context.focus.position) || context.lookedBlock || context.focus;
}

function contextExtensionCommands(context, selected) {
  const direction = extensionDirection(context.playerPosition, selected.position);
  const block = commandBlockName(selected.name);
  const commands = [];
  const start = selected.position;
  const widthAxis = direction.axis === "x" ? "z" : "x";
  const width = 3;
  const length = 10;
  const height = inferContinuationHeight(context, selected.name);

  for (let step = 1; step <= length; step += 1) {
    const x = start.x + (direction.axis === "x" ? direction.sign * step : 0);
    const z = start.z + (direction.axis === "z" ? direction.sign * step : 0);
    const x1 = widthAxis === "x" ? x - width : x;
    const x2 = widthAxis === "x" ? x + width : x;
    const z1 = widthAxis === "z" ? z - width : z;
    const z2 = widthAxis === "z" ? z + width : z;
    commands.push(fillCommand(x1, start.y, z1, x2, start.y + height - 1, z2, block));
  }

  return {
    commands,
    directionText: `${direction.sign > 0 ? "positive" : "negative"} ${direction.axis}`,
  };
}

function inferContinuationHeight(context, blockName) {
  const matching = context.blocks.filter((block) => block.name === blockName);
  if (matching.length < 4) return 2;
  const ys = matching.map((block) => block.position.y);
  return Math.max(1, Math.min(8, Math.max(...ys) - Math.min(...ys) + 1));
}

function extensionDirection(playerPosition, targetPosition) {
  const dx = targetPosition.x - playerPosition.x;
  const dz = targetPosition.z - playerPosition.z;
  if (Math.abs(dx) >= Math.abs(dz)) return { axis: "x", sign: dx >= 0 ? 1 : -1 };
  return { axis: "z", sign: dz >= 0 ? 1 : -1 };
}

function requestedBlockColor(command) {
  const match = String(command).toLowerCase().match(/\b(red|blue|green|yellow|white|black|purple|orange|pink|gray|grey|cyan|lime|brown)\b/);
  if (!match) return null;
  return match[1] === "grey" ? "gray" : match[1];
}

function colorForBlock(blockName) {
  const name = String(blockName).toLowerCase();
  const colors = ["red", "blue", "green", "yellow", "white", "black", "purple", "orange", "pink", "gray", "cyan", "lime", "brown"];
  return colors.find((color) => name.includes(color)) || null;
}

function isSolidContextBlock(block) {
  if (!block || !block.name) return false;
  return !["air", "cave_air", "void_air", "water", "lava", "fire", "soul_fire"].includes(block.name);
}

function nearestBlockTo(blocks, position) {
  if (!blocks.length) return null;
  return blocks.reduce((best, block) => {
    const distance = blockDistance(block.position, position);
    if (!best || distance < best.distance) return { block, distance };
    return best;
  }, null).block;
}

function blockDistance(a, b) {
  return Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);
}

function countBy(values) {
  return values.reduce((counts, value) => {
    counts[value] = (counts[value] || 0) + 1;
    return counts;
  }, {});
}

async function executeDemolition(origin) {
  const x0 = origin.x - 22;
  const x1 = origin.x + 22;
  const z0 = origin.z - 22;
  const z1 = origin.z + 22;
  const y0 = Math.max(-64, origin.y - 38);
  const y1 = origin.y + 18;
  const midX = origin.x;
  const midZ = origin.z;

  const commands = [
    fillCommand(x0, y0, z0, midX - 1, y1, midZ - 1, "air"),
    fillCommand(midX, y0, z0, x1, y1, midZ - 1, "air"),
    fillCommand(x0, y0, midZ, midX - 1, y1, z1, "air"),
    fillCommand(midX, y0, midZ, x1, y1, z1, "air"),
    fillCommand(origin.x - 18, y0 - 1, origin.z - 18, origin.x + 18, y0 - 1, origin.z + 18, "blackstone"),
    fillCommand(origin.x - 8, y0, origin.z - 8, origin.x + 8, y0, origin.z + 8, "magma_block"),
    fillCommand(origin.x - 3, y0 + 1, origin.z - 3, origin.x + 3, y0 + 1, origin.z + 3, "lava"),
    ...ruinPillars(origin, y0),
    fillCommand(origin.x - 2, origin.y, origin.z - 2, origin.x + 2, origin.y, origin.z + 2, "gold_block"),
    setBlockCommand(origin.x, origin.y + 1, origin.z, "beacon"),
  ];
  await sendCommandBatch(commands);
}

function ruinPillars(origin, y) {
  const pillars = [
    [-16, -15, 9, "cracked_stone_bricks"],
    [16, -15, 10, "blackstone"],
    [-16, 15, 8, "cobbled_deepslate"],
    [16, 15, 11, "cracked_deepslate_bricks"],
    [0, 18, 13, "basalt"],
    [10, 0, 10, "cracked_stone_bricks"],
  ];
  const commands = [];
  for (const [dx, dz, height, block] of pillars) {
    commands.push(fillCommand(origin.x + dx, y, origin.z + dz, origin.x + dx + 1, y + height, origin.z + dz + 1, block));
    commands.push(setBlockCommand(origin.x + dx, y + height + 1, origin.z + dz, "fire"));
  }
  return commands;
}

async function executeFortress(origin) {
  const cx = origin.x;
  const y = origin.y;
  const cz = origin.z;
  const x0 = cx - 20;
  const x1 = cx + 20;
  const z0 = cz - 20;
  const z1 = cz + 20;
  const commands = [];

  commands.push(fillCommand(x0, y, z0, x1, y + 1, z1, "polished_deepslate"));
  commands.push(fillCommand(x0 + 3, y + 2, z0 + 3, x1 - 3, y + 2, z1 - 3, "stone_bricks"));
  commands.push(fillCommand(cx - 6, y - 10, cz - 6, cx + 6, y - 1, cz + 6, "basalt"));
  commands.push(setBlockCommand(cx, y - 11, cz, "pointed_dripstone"));

  commands.push(fillCommand(x0, y + 3, z0, x1, y + 12, z0, "deepslate_bricks"));
  commands.push(fillCommand(x0, y + 3, z1, x1, y + 12, z1, "deepslate_bricks"));
  commands.push(fillCommand(x0, y + 3, z0, x0, y + 12, z1, "deepslate_bricks"));
  commands.push(fillCommand(x1, y + 3, z0, x1, y + 12, z1, "deepslate_bricks"));
  commands.push(fillCommand(x0 + 1, y + 4, z0 + 1, x1 - 1, y + 11, z1 - 1, "air"));

  for (let x = x0; x <= x1; x += 4) {
    commands.push(fillCommand(x, y + 13, z0, x + 1, y + 15, z0, "polished_blackstone_bricks"));
    commands.push(fillCommand(x, y + 13, z1, x + 1, y + 15, z1, "polished_blackstone_bricks"));
  }
  for (let z = z0; z <= z1; z += 4) {
    commands.push(fillCommand(x0, y + 13, z, x0, y + 15, z + 1, "polished_blackstone_bricks"));
    commands.push(fillCommand(x1, y + 13, z, x1, y + 15, z + 1, "polished_blackstone_bricks"));
  }

  for (const [tx, tz] of [[x0, z0], [x1, z0], [x0, z1], [x1, z1]]) {
    commands.push(fillCommand(tx - 4, y + 2, tz - 4, tx + 4, y + 20, tz + 4, "deepslate_tiles"));
    commands.push(fillCommand(tx - 2, y + 4, tz - 2, tx + 2, y + 19, tz + 2, "air"));
    commands.push(fillCommand(tx - 5, y + 21, tz - 5, tx + 5, y + 21, tz + 5, "polished_blackstone"));
    commands.push(fillCommand(tx - 2, y + 22, tz - 2, tx + 2, y + 24, tz + 2, "blackstone"));
    commands.push(setBlockCommand(tx, y + 25, tz, "soul_lantern"));
  }

  commands.push(fillCommand(cx - 8, y + 3, cz - 8, cx + 8, y + 22, cz + 8, "stone_bricks"));
  commands.push(fillCommand(cx - 5, y + 5, cz - 5, cx + 5, y + 21, cz + 5, "air"));
  commands.push(fillCommand(cx - 6, y + 23, cz - 6, cx + 6, y + 25, cz + 6, "polished_blackstone"));
  commands.push(fillCommand(cx - 2, y + 26, cz - 2, cx + 2, y + 29, cz + 2, "gilded_blackstone"));
  commands.push(setBlockCommand(cx, y + 30, cz, "beacon"));

  commands.push(fillCommand(x0 - 10, y + 2, cz - 3, x0 - 1, y + 3, cz + 3, "polished_deepslate"));
  commands.push(fillCommand(x0, y + 4, cz - 4, x0, y + 10, cz + 4, "air"));
  commands.push(fillCommand(x0 - 3, y + 4, cz - 6, x0 - 1, y + 14, cz + 6, "polished_blackstone_bricks"));
  for (let z = cz - 4; z <= cz + 4; z += 2) {
    commands.push(fillCommand(x0 - 3, y + 4, z, x0 - 3, y + 11, z, "iron_bars"));
  }

  for (const [fx, fz] of [[x0, z0], [x1, z0], [x0, z1], [x1, z1], [cx, cz - 8], [cx, cz + 8]]) {
    commands.push(fillCommand(fx, y + 25, fz, fx, y + 33, fz, "dark_oak_fence"));
    commands.push(fillCommand(fx + 1, y + 30, fz, fx + 6, y + 33, fz, "red_wool"));
    commands.push(fillCommand(fx + 1, y + 27, fz, fx + 4, y + 29, fz, "black_wool"));
  }

  await sendCommandBatch(commands);
}

async function executeDelegatedCastleWall(origin) {
  const cx = origin.x;
  const y = origin.y;
  const cz = origin.z;
  const westTower = { x: cx - 26, y, z: cz };
  const eastTower = { x: cx + 26, y, z: cz };
  const commands = [];

  commands.push(fillCommand(cx - 34, y, cz - 9, cx, y + 30, cz + 9, "air"));
  commands.push(fillCommand(cx + 1, y, cz - 9, cx + 34, y + 30, cz + 9, "air"));

  commands.push(fillCommand(cx - 24, y, cz - 4, cx + 24, y + 1, cz + 4, "polished_deepslate"));
  commands.push(fillCommand(cx - 23, y + 2, cz - 3, cx + 23, y + 13, cz + 3, "deepslate_bricks"));
  commands.push(fillCommand(cx - 20, y + 7, cz - 1, cx + 20, y + 10, cz + 1, "air"));
  commands.push(fillCommand(cx - 4, y + 2, cz - 4, cx + 4, y + 8, cz + 4, "air"));
  commands.push(fillCommand(cx - 5, y + 9, cz - 4, cx + 5, y + 11, cz + 4, "polished_blackstone_bricks"));
  commands.push(fillCommand(cx - 3, y + 9, cz - 5, cx + 3, y + 11, cz + 5, "air"));
  commands.push(fillCommand(cx - 1, y + 2, cz - 5, cx + 1, y + 7, cz - 5, "iron_bars"));
  commands.push(fillCommand(cx - 1, y + 2, cz + 5, cx + 1, y + 7, cz + 5, "iron_bars"));

  for (let x = cx - 22; x <= cx + 22; x += 4) {
    commands.push(fillCommand(x, y + 14, cz - 3, x + 1, y + 16, cz - 3, "polished_blackstone_bricks"));
    commands.push(fillCommand(x, y + 14, cz + 3, x + 1, y + 16, cz + 3, "polished_blackstone_bricks"));
    commands.push(setBlockCommand(x, y + 12, cz - 4, "soul_lantern"));
    commands.push(setBlockCommand(x, y + 12, cz + 4, "soul_lantern"));
  }

  for (let x = cx - 18; x <= cx + 18; x += 6) {
    commands.push(fillCommand(x, y + 4, cz - 4, x + 1, y + 6, cz - 4, "iron_bars"));
    commands.push(fillCommand(x, y + 4, cz + 4, x + 1, y + 6, cz + 4, "iron_bars"));
    commands.push(fillCommand(x, y + 11, cz - 4, x + 1, y + 12, cz - 4, "chiseled_deepslate"));
    commands.push(fillCommand(x, y + 11, cz + 4, x + 1, y + 12, cz + 4, "chiseled_deepslate"));
  }

  commands.push(fillCommand(cx - 24, y + 12, cz - 1, cx + 24, y + 12, cz + 1, "smooth_stone_slab"));
  commands.push(fillCommand(cx - 22, y + 13, cz, cx + 22, y + 13, cz, "dark_oak_fence"));
  commands.push(fillCommand(cx - 7, y + 2, cz - 6, cx + 7, y + 3, cz - 10, "polished_deepslate"));
  commands.push(fillCommand(cx - 2, y + 4, cz - 10, cx + 2, y + 5, cz - 10, "chain"));
  commands.push(setBlockCommand(cx, y + 6, cz - 10, "soul_lantern"));

  commands.push(...westTowerCommands(westTower));
  await sendCommandBatch(commands);

  const droneJobs = detailedTowerJobs(eastTower, delegatedDroneName);
  writeDelegatedState({
    taskId: `codex-wall-${Date.now()}`,
    structure: "castle wall east tower",
    origin,
    jobs: droneJobs,
  });
}

function westTowerCommands(origin) {
  const { x, y, z } = origin;
  const commands = [
    fillCommand(x - 6, y, z - 6, x + 6, y + 1, z + 6, "polished_deepslate"),
    fillCommand(x - 5, y + 2, z - 5, x + 5, y + 22, z + 5, "deepslate_tiles"),
    fillCommand(x - 3, y + 3, z - 3, x + 3, y + 20, z + 3, "air"),
    fillCommand(x - 4, y + 2, z - 4, x + 4, y + 2, z + 4, "stone_bricks"),
    fillCommand(x - 4, y + 11, z - 4, x + 4, y + 11, z + 4, "stone_bricks"),
    fillCommand(x - 4, y + 20, z - 4, x + 4, y + 20, z + 4, "stone_bricks"),
    fillCommand(x - 7, y + 23, z - 7, x + 7, y + 23, z + 7, "polished_blackstone"),
    fillCommand(x - 4, y + 24, z - 4, x + 4, y + 26, z + 4, "blackstone"),
    fillCommand(x - 2, y + 27, z - 2, x + 2, y + 28, z + 2, "gilded_blackstone"),
    setBlockCommand(x, y + 29, z, "soul_lantern"),
    fillCommand(x - 1, y + 3, z - 6, x + 1, y + 7, z - 6, "iron_bars"),
    fillCommand(x - 1, y + 12, z - 6, x + 1, y + 16, z - 6, "iron_bars"),
    fillCommand(x - 1, y + 3, z + 6, x + 1, y + 7, z + 6, "iron_bars"),
    fillCommand(x - 1, y + 12, z + 6, x + 1, y + 16, z + 6, "iron_bars"),
    fillCommand(x - 6, y + 8, z - 1, x - 6, y + 10, z + 1, "iron_bars"),
    fillCommand(x + 6, y + 8, z - 1, x + 6, y + 10, z + 1, "iron_bars"),
    fillCommand(x - 6, y + 2, z, x - 24, y + 5, z, "deepslate_bricks"),
    fillCommand(x - 25, y + 1, z - 1, x - 16, y + 2, z + 1, "polished_deepslate"),
  ];

  for (const [dx, dz] of [[-5, -5], [5, -5], [-5, 5], [5, 5]]) {
    commands.push(fillCommand(x + dx, y + 24, z + dz, x + dx, y + 29, z + dz, "dark_oak_fence"));
    commands.push(fillCommand(x + dx, y + 28, z + dz, x + dx + Math.sign(dx) * 4, y + 31, z + dz, "red_wool"));
    commands.push(fillCommand(x + dx, y + 25, z + dz, x + dx + Math.sign(dx) * 3, y + 27, z + dz, "black_wool"));
  }

  for (const [dx, dz] of [[-4, -4], [4, -4], [-4, 4], [4, 4]]) {
    commands.push(setBlockCommand(x + dx, y + 12, z + dz, "soul_lantern"));
    commands.push(fillCommand(x + dx, y + 3, z + dz, x + dx, y + 18, z + dz, "chain"));
  }

  return commands;
}

function detailedTowerJobs(origin, worker) {
  const jobs = [];
  const add = (phase, x, y, z, block) => {
    jobs.push({ id: `${phase}-${jobs.length}`, worker, phase, x, y, z, block });
  };
  const cuboid = (phase, x1, y1, z1, x2, y2, z2, block) => {
    for (let yy = Math.min(y1, y2); yy <= Math.max(y1, y2); yy += 1) {
      for (let zz = Math.min(z1, z2); zz <= Math.max(z1, z2); zz += 1) {
        for (let xx = Math.min(x1, x2); xx <= Math.max(x1, x2); xx += 1) {
          add(phase, xx, yy, zz, block);
        }
      }
    }
  };
  const { x, y, z } = origin;

  cuboid("foundation", x - 6, y, z - 6, x + 6, y + 1, z + 6, "polished_deepslate");
  cuboid("shell", x - 5, y + 2, z - 5, x + 5, y + 22, z + 5, "deepslate_tiles");
  cuboid("hollow", x - 3, y + 3, z - 3, x + 3, y + 20, z + 3, "air");
  for (const floorY of [y + 2, y + 11, y + 20]) {
    cuboid("floors", x - 4, floorY, z - 4, x + 4, floorY, z + 4, "stone_bricks");
    cuboid("floors", x - 1, floorY, z - 1, x + 1, floorY, z + 1, "air");
  }

  for (const windowY of [y + 5, y + 14]) {
    cuboid("windows", x - 1, windowY, z - 6, x + 1, windowY + 3, z - 6, "iron_bars");
    cuboid("windows", x - 1, windowY, z + 6, x + 1, windowY + 3, z + 6, "iron_bars");
    cuboid("windows", x - 6, windowY, z - 1, x - 6, windowY + 3, z + 1, "iron_bars");
    cuboid("windows", x + 6, windowY, z - 1, x + 6, windowY + 3, z + 1, "iron_bars");
  }

  cuboid("crown", x - 7, y + 23, z - 7, x + 7, y + 23, z + 7, "polished_blackstone");
  cuboid("crown", x - 4, y + 24, z - 4, x + 4, y + 26, z + 4, "blackstone");
  cuboid("crown", x - 2, y + 27, z - 2, x + 2, y + 28, z + 2, "gilded_blackstone");
  add("crown", x, y + 29, z, "soul_lantern");
  for (let dx = -6; dx <= 6; dx += 4) {
    cuboid("battlements", x + dx, y + 24, z - 7, x + dx + 1, y + 27, z - 7, "polished_blackstone_bricks");
    cuboid("battlements", x + dx, y + 24, z + 7, x + dx + 1, y + 27, z + 7, "polished_blackstone_bricks");
  }
  for (let dz = -6; dz <= 6; dz += 4) {
    cuboid("battlements", x - 7, y + 24, z + dz, x - 7, y + 27, z + dz + 1, "polished_blackstone_bricks");
    cuboid("battlements", x + 7, y + 24, z + dz, x + 7, y + 27, z + dz + 1, "polished_blackstone_bricks");
  }

  for (const [dx, dz] of [[-5, -5], [5, -5], [-5, 5], [5, 5]]) {
    cuboid("flags", x + dx, y + 24, z + dz, x + dx, y + 30, z + dz, "dark_oak_fence");
    const direction = Math.sign(dx) || 1;
    cuboid("flags", x + dx, y + 28, z + dz, x + dx + direction * 4, y + 31, z + dz, "red_wool");
    cuboid("flags", x + dx, y + 25, z + dz, x + dx + direction * 3, y + 27, z + dz, "black_wool");
  }

  for (const [dx, dz] of [[-4, -4], [4, -4], [-4, 4], [4, 4]]) {
    cuboid("detail", x + dx, y + 3, z + dz, x + dx, y + 18, z + dz, "chain");
    add("detail", x + dx, y + 12, z + dz, "soul_lantern");
  }
  cuboid("detail", x + 6, y + 2, z, x + 24, y + 5, z, "deepslate_bricks");
  cuboid("detail", x + 16, y + 1, z - 1, x + 25, y + 2, z + 1, "polished_deepslate");

  return jobs;
}

function writeDelegatedState(state) {
  fs.mkdirSync(path.dirname(swarmStatePath), { recursive: true });
  const payload = {
    taskId: state.taskId,
    status: "building",
    structure: state.structure,
    source: "codex-architect",
    assignedBy: username,
    assignedTo: delegatedDroneName,
    origin: state.origin,
    jobs: state.jobs,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
  const tempPath = `${swarmStatePath}.${process.pid}.tmp`;
  fs.writeFileSync(tempPath, `${JSON.stringify(payload, null, 2)}\n`);
  fs.renameSync(tempPath, swarmStatePath);
}

function fillCommand(x1, y1, z1, x2, y2, z2, block) {
  return `/fill ${x1} ${y1} ${z1} ${x2} ${y2} ${z2} ${block}`;
}

function setBlockCommand(x, y, z, block) {
  return `/setblock ${x} ${y} ${z} ${block}`;
}

function commandBlockName(block) {
  return String(block).replace(/^minecraft:/, "").replace(/\[.*$/, "");
}

async function sendCommandBatch(commands) {
  for (let index = 0; index < commands.length; index += 1) {
    bot.chat(commands[index]);
    if ((index + 1) % 20 === 0) console.log(`sent build command ${index + 1}/${commands.length}`);
    await wait(buildCommandDelayMs);
  }
}

function formatBlockPosition(position) {
  return `${position.x}, ${position.y}, ${position.z}`;
}

function requirePlayer(name) {
  const playerName = findPlayerName(name);
  const player = playerName ? bot.players[playerName]?.entity : null;
  if (!player) throw new Error(`I cannot see ${name}.`);
  return player;
}

async function resolvePlayerPosition(name, options = {}) {
  const targetName = findPlayerName(name) || name;
  const visiblePlayer = bot.players[targetName]?.entity;

  if (options.teleportToPlayer === true) {
    const before = vectorPosition(bot.entity.position);
    bot.chat(`/tp ${username} ${targetName}`);
    await wait(Number(process.env.CODEX_PLAYER_TP_WAIT_MS || 700));
    const after = vectorPosition(bot.entity.position);
    if (positionMoved(before, after)) {
      return { ...after, teleported: true };
    }
  }

  if (visiblePlayer?.position) return vectorPosition(visiblePlayer.position);

  if (options.teleportToPlayer !== false) {
    const before = vectorPosition(bot.entity.position);
    bot.chat(`/tp ${username} ${targetName}`);
    await wait(Number(process.env.CODEX_PLAYER_TP_WAIT_MS || 700));
    const after = vectorPosition(bot.entity.position);
    if (positionMoved(before, after)) {
      return { ...after, teleported: true };
    }
  }

  throw new Error(`I cannot resolve ${name}'s position. If /tp is blocked, op ${username} or install/update the Bubble Sky server bridge.`);
}

function blockPosition(position) {
  return {
    x: Math.round(position.x),
    y: Math.round(position.y),
    z: Math.round(position.z),
  };
}

function vectorPosition(position) {
  return { x: position.x, y: position.y, z: position.z };
}

function positionMoved(before, after) {
  return Math.abs(before.x - after.x) > 0.25 ||
    Math.abs(before.y - after.y) > 0.25 ||
    Math.abs(before.z - after.z) > 0.25;
}

async function moveDirect(x, y, z) {
  if (bot.creative) {
    await bot.creative.startFlying();
    await withTimeout(bot.creative.flyTo(new Vec3(x, y, z)), 3000);
    return;
  }
  bot.pathfinder.setGoal(new goals.GoalNear(x, y, z, 2));
}

async function withTimeout(promise, timeoutMs) {
  let timeout = null;
  try {
    await Promise.race([
      promise,
      new Promise((resolve) => {
        timeout = setTimeout(resolve, timeoutMs);
      }),
    ]);
  } finally {
    if (timeout) clearTimeout(timeout);
  }
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
  const entries = filterHistoryForVisibility(readHistory(count * 2)).slice(-count);

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
  const entries = filterHistoryForVisibility(readHistory(10)).slice(-5);
  const target = escort
    ? `escorting ${escort.playerName} to ${formatDestination(escort)}`
    : followTarget
      ? `following ${followTarget}`
      : "idle";
  const delegated = delegatedStatusText();
  say(`I am ${target} at ${formatPosition(bot.entity.position)}; visibility ${visibility}${delegated ? `; ${delegated}` : ""}.`, { to: speaker, record: false });

  if (entries.length) {
    say(`recent: ${entries.map((entry) => `${entry.speaker || entry.bot}: ${entry.text}`).join(" / ")}`, {
      to: speaker,
      record: false,
    });
  }
}

function delegatedStatusText() {
  try {
    if (!fs.existsSync(swarmStatePath)) return "";
    const state = JSON.parse(fs.readFileSync(swarmStatePath, "utf8"));
    if (state.source !== "codex-architect") return "";
    const progressPath = path.join(swarmRuntimeDir, "progress", `${state.assignedTo || delegatedDroneName}.json`);
    const progress = fs.existsSync(progressPath) ? JSON.parse(fs.readFileSync(progressPath, "utf8")) : null;
    if (!progress || progress.taskId !== state.taskId) {
      return `${state.assignedTo || delegatedDroneName} waiting on ${state.structure}`;
    }
    const done = progress.doneIds?.length || 0;
    const failed = progress.failedIds?.length || 0;
    const total = state.jobs?.length || 0;
    const active = progress.activeJob ? `, now ${progress.activeJob}` : "";
    return `${state.assignedTo || delegatedDroneName} ${state.structure}: ${done}/${total} jobs done, ${failed} failed${active}`;
  } catch (error) {
    console.error(`failed to read delegated status: ${error.message}`);
    return "";
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
  const replyTarget = options.to || commandContext.getStore()?.speaker;
  if (visibility === "private" || visibility === "alone") return replyTarget ? [replyTarget] : [];
  if (visibility === "llm") return uniqueTargets([...visibleLlms(), replyTarget]);
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

function shouldRecordAmbientChat(speaker, message) {
  if (!looksLikeLlmConversation(speaker, message)) return false;
  return visibility !== "alone" || !isNoisyBotSpeaker(speaker);
}

function isBotLoopChatter(speaker, command) {
  if (!llmPlayers.includes(speaker.toLowerCase())) return false;

  const lower = command.toLowerCase();
  return botLoopPatterns.some((pattern) => pattern.test(lower));
}

function isIgnoredSpeaker(speaker) {
  const lower = speaker.toLowerCase();
  return ignoredSpeakers.includes(lower) || /^codexdrone\d+$/i.test(speaker) || lower === "codexboss";
}

function isNoisyBotSpeaker(speaker) {
  const lower = speaker.toLowerCase();
  return llmPlayers.includes(lower) || isIgnoredSpeaker(speaker);
}

function filterHistoryForVisibility(entries) {
  if (visibility !== "alone") return entries;
  return entries.filter((entry) => entry.type !== "observed" || !isNoisyBotSpeaker(entry.speaker || ""));
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

function uniqueTargets(values) {
  const seen = new Set();
  const targets = [];
  for (const value of values) {
    if (!value) continue;
    const key = String(value).toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    targets.push(String(value));
  }
  return targets;
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
