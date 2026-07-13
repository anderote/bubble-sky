#!/usr/bin/env node
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { makeBridge } from "./bridge.mjs";

const bridge = makeBridge();
const username = process.env.CODEX_BOT_USERNAME || "Codex";
const aliases = unique([
  username,
  username.replace(/bot$/i, ""),
  ...parseList(process.env.CODEX_BOT_ALIASES || "codex"),
]);
const ignoredSpeakers = new Set(parseList(process.env.CODEX_IGNORED_SPEAKERS || "grok,codexdrone1,codexdrone2,codexdrone3,codexdrone4,codexboss"));
const codexCliPath = process.env.CODEX_COMMAND_CLI || "/opt/homebrew/bin/codex";
const codexTimeoutMs = Number(process.env.CODEX_GODMODE_CLI_TIMEOUT_MS || 18000);
const pollMs = Number(process.env.CODEX_BRIDGE_POLL_MS || 900);
const maxCommands = Number(process.env.CODEX_GODMODE_MAX_COMMANDS || 28);
const commandDelayMs = Number(process.env.CODEX_GODMODE_COMMAND_DELAY_MS || 60);

let busy = false;

log(`starting ${username} bridge godmode bot`);
const health = await bridge.health();
log(`bridge connected: MC ${health.mcVersion} mod ${health.modVersion} tps ${health.tps}`);
await say(`${username} here over the bridge. Try @${aliases[0]} give me armor and weapons and stuff`);
await bridge.postStatus(username, "idle", "");

let since = 0;
try {
  since = (await bridge.chat(0)).cursor || 0;
} catch {
  since = 0;
}
log(`chat poll starting at cursor ${since}`);

for (;;) {
  try {
    const { cursor, messages } = await bridge.chat(since);
    if (typeof cursor === "number") since = cursor;
    for (const message of messages || []) {
      await handleChat(message).catch((error) => log(`chat handler error: ${error.message}`));
    }
  } catch (error) {
    log(`bridge poll error: ${error.message}`);
  }
  await wait(pollMs);
}

async function handleChat(message) {
  const speaker = String(message.player || "");
  const text = String(message.text || "");
  if (!speaker || speaker.toLowerCase() === username.toLowerCase()) return;
  if (ignoredSpeakers.has(speaker.toLowerCase())) return;

  const command = addressedCommand(text);
  if (!command) return;
  log(`<${speaker}> ${text} -> ${command}`);

  if (busy) {
    await say("I am finishing another request; try again in a moment.");
    return;
  }

  busy = true;
  await bridge.postStatus(username, "thinking", command.slice(0, 80)).catch(() => {});
  try {
    if (/^(?:help|what can you do)$/i.test(command)) {
      await say("Ask naturally: gear us up, make it day, summon mounts, buff me, clear mobs, place blocks, or run bounded world edits.");
      return;
    }

    const plan = localPlan(speaker, command) || await planWithCodex(speaker, command);
    if (!plan || !Array.isArray(plan.commands) || plan.commands.length === 0) {
      await say("I did not turn that into an in-game action.");
      return;
    }

    const commands = plan.commands
      .map((cmd) => String(cmd || "").trim().replace(/^\//, ""))
      .filter(isSafeCommand)
      .slice(0, maxCommands);

    if (!commands.length) {
      await say("That mapped to blocked server-admin commands, so I skipped it.");
      return;
    }

    await say(plan.reply || "On it.");
    await bridge.postStatus(username, "acting", `${commands.length} commands`).catch(() => {});
    for (const commandText of commands) {
      await bridge.command(commandText);
      await wait(commandDelayMs);
    }
    await say("Done.");
  } catch (error) {
    log(`godmode error: ${error.stack || error.message}`);
    await say(`I hit an error: ${error.message.slice(0, 140)}`);
  } finally {
    busy = false;
    await bridge.postStatus(username, "idle", "").catch(() => {});
  }
}

function addressedCommand(message) {
  const trimmed = message.trim();
  for (const alias of aliases) {
    const escaped = alias.replace(/[.*+?^${}()|[\]\\]/g, "\\$&").replace(/\\ /g, "\\s+");
    const pattern = new RegExp(`^(?:hey|hi|yo)?\\s*@?${escaped}(?=$|\\s|[:,>\\-])\\s*(?:[:,>\\-])?\\s*(.*)$`, "i");
    const match = trimmed.match(pattern);
    if (match) return normalize(match[1] || "help");
  }
  return null;
}

function localPlan(speaker, command) {
  const lower = command.toLowerCase();
  const target = lower.includes(" us ") || /\b(everyone|all of us|all players|the team)\b/.test(lower) ? "@a" : speaker;
  if (/\b(?:gear|kit|armor|armour|weapons?|sword|bow|stuff)\b/.test(lower) && /\b(?:give|need|want|hook|load|set)\b/.test(lower)) {
    return {
      reply: "Combat kit coming up.",
      commands: [
        `give ${target} netherite_helmet 1`,
        `give ${target} netherite_chestplate 1`,
        `give ${target} netherite_leggings 1`,
        `give ${target} netherite_boots 1`,
        `give ${target} netherite_sword 1`,
        `give ${target} bow 1`,
        `give ${target} shield 1`,
        `give ${target} arrow 64`,
        `give ${target} cooked_beef 64`,
        `give ${target} golden_apple 16`,
        `give ${target} netherite_pickaxe 1`,
        `give ${target} torch 64`,
        `effect give ${target} minecraft:strength 600 1 true`,
        `effect give ${target} minecraft:resistance 600 1 true`,
      ],
    };
  }
  if (/\b(?:heal|fix me|save me)\b/.test(lower)) {
    return {
      reply: "Healing now.",
      commands: [
        `effect give ${target} minecraft:instant_health 1 10 true`,
        `effect give ${target} minecraft:regeneration 30 2 true`,
        `effect give ${target} minecraft:saturation 5 5 true`,
      ],
    };
  }
  return null;
}

async function planWithCodex(speaker, command) {
  const players = await bridge.players().catch(() => ({ players: [] }));
  const player = await bridge.player(speaker).catch(() => null);
  const outputPath = path.join(os.tmpdir(), `codex-bridge-godmode-${process.pid}-${Date.now()}.json`);
  try {
    await runCodexCli(godmodePrompt({ speaker, command, players: players.players || [], player }), outputPath, codexTimeoutMs);
    const parsed = parseJsonObject(fs.readFileSync(outputPath, "utf8"));
    if (parsed && Array.isArray(parsed.commands)) return parsed;
    return null;
  } finally {
    fs.rmSync(outputPath, { force: true });
  }
}

function godmodePrompt({ speaker, command, players, player }) {
  return [
    "Return only compact JSON. No markdown.",
    "You are Codex, an operator-level Minecraft assistant on a private creative Fabric server.",
    "Convert the player's natural-language request into useful Minecraft commands.",
    "JSON shape: {\"reply\":\"short acknowledgement under 100 chars\",\"commands\":[\"command without leading slash\"]}",
    "Default target is the speaker. Use @a only when the player says us/everyone/all players/team.",
    "Prefer complete, useful bundles over literal one-item output. For gear requests, include armor, weapons, shield, arrows, food, tools, torches, and useful effects.",
    "Support broad godmode asks: give items, buffs, heal/feed, enchant, gamemode, xp, summon mobs/mounts, teleport, time/weather, gamerules, clear mobs, setblock/fill/place bounded structures.",
    `Never output more than ${maxCommands} commands.`,
    "Avoid huge destructive edits unless the user explicitly asks. Keep fill regions modest and near the speaker/look target.",
    "Never output these server-admin commands: op, deop, ban, pardon, kick, stop, save-all, save-off, whitelist, reload.",
    "If this is just conversation or a question, return {\"reply\":\"\",\"commands\":[]}.",
    `Speaker: ${speaker}`,
    `Speaker position/look: ${JSON.stringify(player || {})}`,
    `Visible players: ${players.map((p) => p.name).join(", ") || "unknown"}`,
    `Request: ${JSON.stringify(command)}`,
  ].join("\n");
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
    ], { cwd: process.cwd(), stdio: ["ignore", "pipe", "pipe"] });

    let stderr = "";
    let stdout = "";
    const timeout = setTimeout(() => {
      child.kill("SIGTERM");
      reject(new Error(`codex timed out after ${timeoutMs}ms`));
    }, timeoutMs);

    child.stdout.on("data", (chunk) => { stdout += chunk.toString(); });
    child.stderr.on("data", (chunk) => { stderr += chunk.toString(); });
    child.on("error", (error) => {
      clearTimeout(timeout);
      reject(error);
    });
    child.on("close", (code) => {
      clearTimeout(timeout);
      if (code === 0) resolve({ stdout, stderr });
      else reject(new Error(`codex exited ${code}: ${(stderr || stdout).slice(0, 500)}`));
    });
  });
}

function isSafeCommand(command) {
  const text = String(command || "").trim().replace(/^\//, "");
  if (!text || text.length > 240 || /[\n\r;]/.test(text)) return false;
  return !/^(?:op|deop|ban|ban-ip|pardon|pardon-ip|kick|stop|save-all|save-off|whitelist|reload)\b/i.test(text);
}

async function say(message) {
  const text = normalize(message).slice(0, 220);
  if (text) await bridge.say(username, text);
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

function normalize(text) {
  return String(text || "").replace(/\s+/g, " ").replace(/[.!?]+$/g, "").trim();
}

function parseList(value) {
  return String(value || "").split(",").map((entry) => entry.trim().toLowerCase()).filter(Boolean);
}

function unique(values) {
  return [...new Set(values.map((value) => String(value || "").toLowerCase()).filter(Boolean))];
}

function log(message) {
  console.log(`[${new Date().toISOString()}] ${message}`);
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
