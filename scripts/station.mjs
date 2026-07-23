#!/usr/bin/env node
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import readline from "node:readline/promises";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { expandHome, fetchJson, readJson, writeJson } from "../control/lib/common.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const args = process.argv.slice(2);
const command = args.shift() || "help";
const configFlag = takeOption("--config");
const configPath = path.resolve(expandHome(configFlag || process.env.BUBBLE_STATION_CONFIG || "~/.config/bubble-sky/station.json"));
const serviceLabels = ["dev.bubblesky.station", "dev.bubblesky.minecraft-chat", "dev.bubblesky.release-watcher"];
const profiles = {
  alli: {
    nodeId: "alli-mac", displayName: "Alli's Mac", peerNode: "andrew-mac", minecraftHost: "andrew-mac.local",
    provider: "codex", roles: ["client", "codex-agent", "swarm"],
  },
  andrew: {
    nodeId: "andrew-mac", displayName: "Andrew's Mac", peerNode: "alli-mac", minecraftHost: "127.0.0.1",
    provider: "claude", roles: ["server", "client", "claude-agent", "grok"],
  },
};

switch (command) {
  case "setup": await setup(args[0] || "andrew"); break;
  case "doctor": await doctor(); break;
  case "status": await status(); break;
  case "restart": runOrExit(path.join(root, "scripts/install-station.sh"), [], { env: { ...process.env, BUBBLE_STATION_CONFIG: configPath } }); break;
  case "logs": logs(args[0]); break;
  case "pair-code": pairCode(); break;
  case "test": runOrExit(process.execPath, ["--test", "control/test/*.test.mjs"], { cwd: root, shell: true }); break;
  case "help":
  case "--help":
  case "-h": help(); break;
  default: fail(`Unknown command: ${command}\n\n${usage()}`);
}

function takeOption(name) {
  const equals = args.findIndex((arg) => arg.startsWith(`${name}=`));
  if (equals !== -1) return args.splice(equals, 1)[0].slice(name.length + 1);
  const index = args.indexOf(name);
  if (index === -1) return null;
  args.splice(index, 1);
  return args.splice(index, 1)[0];
}

async function setup(profileName) {
  if (!["alli", "andrew"].includes(profileName)) fail("Setup profile must be 'alli' or 'andrew'.");
  const existing = fs.existsSync(configPath);
  if (existing && !args.includes("--force")) {
    fail(`${configPath} already exists. It was not changed.\nRun setup with --force only if you intend to replace it.`);
  }
  const profile = profiles[profileName];
  const useDefaults = args.includes("--defaults");
  const terminal = useDefaults ? null : readline.createInterface({ input: process.stdin, output: process.stdout });
  console.log(`\nBubble Sky Station setup for ${profile.displayName}`);
  console.log(useDefaults ? "Using suggested values.\n" : "Press Return to accept a suggested value.\n");
  try {
    const nodeId = await ask(terminal, "This Mac's node name", profile.nodeId);
    const displayName = await ask(terminal, "Friendly name", profile.displayName);
    const publicUrl = await ask(terminal, "This Mac's LAN/Tailscale Station URL", `http://${nodeId}.local:25880`);
    const peerNode = await ask(terminal, "Other Mac's node name", profile.peerNode);
    const peerUrl = await ask(terminal, "Other Mac's LAN/Tailscale Station URL", `http://${peerNode}.local:25880`);
    const minecraftHost = await ask(terminal, "Minecraft server host", profile.minecraftHost);
    const minecraftUser = await ask(terminal, "Station bot username", "DevStation");
    const players = await ask(terminal, "Allowed Minecraft players (comma separated)", "allibell,claudebert");
    const providerCommand = await ask(terminal, `${profile.provider} executable`, findExecutable(profile.provider) || profile.provider);
    const prismInstance = await ask(terminal, "Prism Launcher instance", "bubble-sky-1.21.6");
    const suppliedToken = process.env.BUBBLE_STATION_TOKEN || await ask(terminal, "Shared Station token (paste the token from the other Mac; blank creates one)", "");
    const sharedToken = suppliedToken || crypto.randomBytes(32).toString("base64url");
    const config = {
      nodeId,
      displayName,
      repoRoot: root,
      runtimeDir: "~/.local/share/bubble-sky",
      sharedToken,
      listen: { host: "0.0.0.0", port: 25880 },
      publicUrl,
      roles: profile.roles,
      providers: [provider(profile.provider, providerCommand)],
      peers: [{ nodeId: peerNode, url: peerUrl }],
      minecraft: {
        host: minecraftHost,
        port: 25565,
        username: minecraftUser,
        allowedPlayers: players.split(",").map((value) => value.trim()).filter(Boolean),
      },
      deployment: {
        releaseChannel: "play",
        noticeMinutes: 5,
        prismInstance,
        serverAddress: `${minecraftHost}:25565`,
        ...(profileName === "andrew" ? { serverDir: path.join(root, "server"), screenSession: "bubble-sky-play" } : {}),
        agentRestartCommands: [],
      },
    };
    writeJson(configPath, config);
    fs.chmodSync(configPath, 0o600);
    console.log(`\nWrote ${configPath} (mode 0600).`);
    if (!suppliedToken) {
      console.log("A new shared token was created. Copy sharedToken from this config to the other Mac's config over a private channel.");
    }
    const install = args.includes("--install") ? "yes" : await ask(terminal, "Install and start the background services now? (yes/no)", useDefaults ? "no" : "yes");
    if (/^y(?:es)?$/i.test(install)) {
      runOrExit(path.join(root, "scripts/install-station.sh"), [], { env: { ...process.env, BUBBLE_STATION_CONFIG: configPath } });
    } else {
      console.log(`When ready: ${path.join(root, "scripts/station.mjs")} restart`);
    }
  } finally {
    terminal?.close();
  }
}

function provider(name, commandPath) {
  if (name === "codex") return { name, adapter: "codex", command: commandPath, timeoutMs: 1_800_000 };
  return {
    name,
    adapter: "template",
    command: commandPath,
    chatArgs: ["-p", "{prompt}"],
    devArgs: ["-p", "--permission-mode", "acceptEdits", "{prompt}"],
    timeoutMs: 1_800_000,
  };
}

async function doctor() {
  const checks = [];
  const required = (ok, label, detail = "") => checks.push({ ok, required: true, label, detail });
  const optional = (ok, label, detail = "") => checks.push({ ok, required: false, label, detail });
  const major = Number(process.versions.node.split(".")[0]);
  required(major >= 22, "Node.js 22+", process.version);
  required(process.platform === "darwin", "macOS", `${process.platform}/${process.arch}`);
  let config;
  try {
    config = readJson(configPath);
    required(true, "Station config", configPath);
  } catch (error) {
    required(false, "Station config", `${configPath}: ${error.message}`);
  }
  if (config) {
    const mode = fs.statSync(configPath).mode & 0o777;
    required(mode === 0o600, "Config permissions", mode.toString(8).padStart(3, "0"));
    required(Boolean(config.nodeId), "Node identity", config.nodeId || "missing nodeId");
    required(Boolean(config.sharedToken && !/CHANGE|SAME_TOKEN/.test(config.sharedToken) && config.sharedToken.length >= 24), "Shared token", "at least 24 characters and not a sample value");
    const repo = path.resolve(expandHome(config.repoRoot || root));
    required(fs.existsSync(path.join(repo, ".git")), "Repository", repo);
    for (const item of config.providers || []) {
      const executable = resolveExecutable(item.command || item.name);
      required(Boolean(executable), `${item.name} provider`, executable || `${item.command || item.name} not found`);
    }
    if ((config.roles || []).includes("client")) {
      const prismRoot = expandHome(config.deployment?.prismRoot || "~/Library/Application Support/PrismLauncher/instances");
      const instance = path.join(prismRoot, config.deployment?.prismInstance || "bubble-sky-1.21.6", ".minecraft");
      optional(fs.existsSync(instance), "Prism instance", instance);
    }
    if ((config.roles || []).includes("server")) {
      required(Boolean(resolveExecutable("screen")), "screen", resolveExecutable("screen") || "install with: brew install screen");
      required(fs.existsSync(path.join(expandHome(config.deployment?.serverDir || path.join(repo, "server")), "run.sh")), "Server launcher", config.deployment?.serverDir || path.join(repo, "server"));
    }
    const gh = spawnSync("gh", ["auth", "status", "--hostname", "github.com"], { encoding: "utf8" });
    required(gh.status === 0, "GitHub CLI login", gh.status === 0 ? "authenticated" : "run: gh auth login");
    try {
      const health = await fetchJson(`http://127.0.0.1:${config.listen?.port || 25880}/health`, { timeoutMs: 800 });
      optional(Boolean(health.ok), "Station service", `${health.nodeId} is running`);
    } catch {
      optional(false, "Station service", "not running yet (normal before install)");
    }
    for (const peer of config.peers || []) {
      try {
        const remote = await fetchJson(`${peer.url.replace(/\/$/, "")}/v1/capabilities`, { token: peer.token || config.sharedToken, timeoutMs: 1200 });
        optional(true, `Peer ${peer.nodeId || peer.url}`, `${remote.displayName || remote.nodeId} reachable`);
      } catch (error) {
        optional(false, `Peer ${peer.nodeId || peer.url}`, `not reachable: ${error.message}`);
      }
    }
  }
  console.log("\nBubble Sky Station doctor\n");
  for (const check of checks) console.log(`${check.ok ? "✓" : check.required ? "✗" : "!"} ${check.label}${check.detail ? ` — ${check.detail}` : ""}`);
  const failures = checks.filter((check) => check.required && !check.ok);
  console.log(failures.length ? `\n${failures.length} required check(s) need attention.` : "\nRequired checks passed.");
  process.exitCode = failures.length ? 1 : 0;
}

async function status() {
  let config;
  try { config = readJson(configPath); }
  catch { fail(`No readable config at ${configPath}. Run: ./scripts/station.mjs setup andrew`); }
  console.log(`Config: ${configPath}`);
  try {
    const health = await fetchJson(`http://127.0.0.1:${config.listen?.port || 25880}/health`, { timeoutMs: 1200 });
    console.log(`Station: running (${health.nodeId})`);
    const fleet = await fetchJson(`http://127.0.0.1:${config.listen?.port || 25880}/v1/fleet`, { token: config.sharedToken, timeoutMs: 3000 });
    for (const node of fleet.nodes || []) {
      console.log(`- ${node.displayName || node.nodeId}: ${node.offline ? "offline" : "online"}; agents: ${(node.providers || []).map((item) => item.name).join(", ") || "none"}`);
    }
  } catch (error) {
    console.log(`Station: stopped or unhealthy (${error.message})`);
    process.exitCode = 1;
  }
  for (const label of serviceLabels) {
    const result = spawnSync("launchctl", ["print", `gui/${process.getuid()}/${label}`], { stdio: "ignore" });
    console.log(`${label}: ${result.status === 0 ? "loaded" : "not loaded"}`);
  }
}

function logs(name = "station") {
  const names = { station: "station.log", chat: "minecraft-chat.log", release: "release-watcher.log" };
  if (!names[name]) fail("Log name must be station, chat, or release.");
  runOrExit("tail", ["-n", "100", "-f", path.join(os.homedir(), "Library/Logs/BubbleSky", names[name])]);
}

function pairCode() {
  let config;
  try { config = readJson(configPath); }
  catch { fail(`No readable config at ${configPath}.`); }
  const file = path.join(expandHome(config.runtimeDir || "~/.local/share/bubble-sky"), "pairing.json");
  const pairing = readJson(file);
  if (Date.now() > pairing.expiresAt) fail("The pairing code expired. Run ./scripts/station.mjs restart to make a new one.");
  console.log(pairing.code);
  console.log(`In Minecraft: /msg ${config.minecraft?.username || "DevStation"} pair ${pairing.code}`);
}

function runOrExit(executable, commandArgs, options = {}) {
  const result = spawnSync(executable, commandArgs, { cwd: root, stdio: "inherit", ...options });
  if (result.error) fail(result.error.message);
  if (result.status !== 0) process.exit(result.status ?? 1);
}

function resolveExecutable(executable) {
  if (!executable) return null;
  if (executable.includes("/")) return fs.existsSync(expandHome(executable)) ? path.resolve(expandHome(executable)) : null;
  return findExecutable(executable);
}

function findExecutable(executable) {
  const result = spawnSync("/usr/bin/which", [executable], { encoding: "utf8" });
  return result.status === 0 ? result.stdout.trim() : null;
}

async function ask(terminal, label, suggested) {
  if (!terminal) return suggested;
  const answer = (await terminal.question(`${label}${suggested ? ` [${suggested}]` : ""}: `)).trim();
  return answer || suggested;
}

function help() { console.log(usage()); }
function usage() {
  return `Bubble Sky Station helper

Usage:
  ./scripts/station.mjs setup andrew   guided setup, then install services
  ./scripts/station.mjs doctor         check config and dependencies
  ./scripts/station.mjs status         show this Station and its peer
  ./scripts/station.mjs restart        reinstall/restart background services
  ./scripts/station.mjs pair-code      show the current Minecraft pairing command
  ./scripts/station.mjs logs [station|chat|release]
  ./scripts/station.mjs test

Options:
  --config PATH   use a non-default station.json
  --force         allow setup to replace an existing config
  --defaults      accept every setup suggestion without prompting
  --install       install services after non-interactive setup

Setup also reads BUBBLE_STATION_TOKEN, which avoids putting the shared token in shell history.`;
}
function fail(message) { console.error(message); process.exit(1); }
