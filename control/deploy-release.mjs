#!/usr/bin/env node
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fetchJson, loadConfig, readJson, writeJson } from "./lib/common.mjs";
import { run } from "./lib/process.mjs";

const config = loadConfig(process.argv[2]);
const bundleArg = process.argv.find((arg) => arg.startsWith("--bundle="));
const now = process.argv.includes("--now");
const bundle = bundleArg ? path.resolve(bundleArg.slice(9)) : null;
if (!bundle) throw new Error("usage: node control/deploy-release.mjs <station.json> --bundle=/path/to/extracted-release [--now]");
const manifest = readJson(path.join(bundle, "release.json"));
verify(bundle, manifest);
const releases = path.join(config.runtimeDir, "releases");
const target = path.join(releases, manifest.release);
fs.mkdirSync(releases, { recursive: true });
if (!fs.existsSync(target)) fs.cpSync(bundle, target, { recursive: true });

const noticeMinutes = now ? 0 : Number(config.deployment?.noticeMinutes ?? 5);
if (noticeMinutes) {
  await notice(`Deployment ready! Will restart and auto-setup in ${noticeMinutes} mins, or message e.g. '@dev later 10mins'. Release: ${manifest.release}.`, [{ label: "Delay", command: "@dev later 10mins" }]);
  let deadline = Date.now() + noticeMinutes * 60_000;
  while (Date.now() < deadline) {
    const stationState = readJson(path.join(config.runtimeDir, "station-state.json"), { postponements: {} });
    deadline = Math.max(deadline, stationState.postponements?.[manifest.release] || stationState.postponements?.next || 0);
    await new Promise((resolve) => setTimeout(resolve, Math.min(5000, Math.max(1, deadline - Date.now()))));
  }
}

await notice(`Applying ${manifest.release}; server, clients, and bots may disconnect briefly.`);
const applied = readJson(path.join(config.runtimeDir, "applied-release.json"), null);
try {
  if (config.roles.includes("client")) await deployClient(target, manifest);
  if (config.roles.includes("server")) await deployServer(target, manifest);
  if (config.roles.some((role) => ["codex-agent", "claude-agent", "swarm", "grok"].includes(role))) await restartAgents();
  writeJson(path.join(config.runtimeDir, "applied-release.json"), { release: manifest.release, revision: manifest.revision, at: new Date().toISOString(), previous: applied?.release || null });
  await notice(`Release ${manifest.release} is live on ${config.displayName || config.nodeId}.`);
} catch (error) {
  await notice(`Release ${manifest.release} failed on ${config.nodeId}: ${error.message}. Restoring the previous mods.`);
  await rollback();
  throw error;
}

async function deployClient(releaseDir) {
  const prismRoot = config.deployment?.prismRoot || path.join(process.env.HOME, "Library/Application Support/PrismLauncher/instances");
  const instance = config.deployment?.prismInstance || "bubble-sky-1.21.6";
  const mcDir = path.join(prismRoot, instance, ".minecraft");
  if (!fs.existsSync(mcDir)) throw new Error(`Prism instance not found: ${mcDir}`);
  if (config.deployment?.autoCloseMinecraft !== false) await run("pkill", ["-TERM", "-f", "net.minecraft.client.main.Main"], { allowFailure: true });
  await swapMods(path.join(releaseDir, "client/mods"), path.join(mcDir, "mods"), "client");
  fs.rmSync(path.join(mcDir, ".fabric/processedMods"), { recursive: true, force: true });
  if (config.deployment?.autoLaunchPrism !== false) {
    const server = config.deployment?.serverAddress || "127.0.0.1:25565";
    if (config.deployment?.prismCommand) await run(config.deployment.prismCommand, ["--launch", instance, "--server", server], { allowFailure: true, timeoutMs: 15_000 });
    else await run("open", ["-a", "Prism Launcher", "--args", "--launch", instance, "--server", server], { allowFailure: true, timeoutMs: 15_000 });
  }
}

async function deployServer(releaseDir) {
  const serverDir = path.resolve(config.deployment?.serverDir || path.join(config.repoRoot, "server"));
  if (fs.existsSync(path.join(serverDir, "world"))) {
    const backups = path.join(config.runtimeDir, "world-backups");
    fs.mkdirSync(backups, { recursive: true });
    await run("tar", ["-czf", path.join(backups, `${Date.now()}-${manifest.release}.tgz`), "world"], { cwd: serverDir, timeoutMs: 300_000 });
  }
  const session = config.deployment?.screenSession || "bubble-sky-play";
  await run("screen", ["-S", session, "-p", "0", "-X", "stuff", "stop\n"], { allowFailure: true });
  const stopDeadline = Date.now() + 60_000;
  while (Date.now() < stopDeadline) {
    const listening = await run("lsof", ["-nP", "-iTCP:25565", "-sTCP:LISTEN", "-t"], { allowFailure: true });
    if (!listening.stdout.trim()) break;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  await swapMods(path.join(releaseDir, "server/mods"), path.join(serverDir, "mods"), "server");
  await run("screen", ["-S", session, "-X", "quit"], { allowFailure: true });
  fs.writeFileSync(path.join(serverDir, "server-live.log"), "");
  await run("screen", ["-dmS", session, "/bin/bash", "-lc", "exec ./run.sh >>server-live.log 2>&1"], { cwd: serverDir });
  const timeout = Date.now() + 150_000;
  while (Date.now() < timeout) {
    const log = path.join(serverDir, "server-live.log");
    if (fs.existsSync(log) && /Done \([0-9]/.test(fs.readFileSync(log, "utf8"))) return;
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  throw new Error("server did not become ready within 150 seconds");
}

async function restartAgents() {
  for (const command of config.deployment?.agentRestartCommands || []) await run(command[0], command.slice(1), { cwd: config.repoRoot, timeoutMs: 120_000 });
}

async function swapMods(source, destination, name) {
  if (!fs.existsSync(source)) return;
  const backup = `${destination}.previous`;
  fs.rmSync(backup, { recursive: true, force: true });
  if (fs.existsSync(destination)) fs.renameSync(destination, backup);
  try { fs.cpSync(source, destination, { recursive: true }); }
  catch (error) { if (fs.existsSync(backup)) fs.renameSync(backup, destination); throw error; }
  writeJson(path.join(config.runtimeDir, `${name}-mods-swap.json`), { destination, backup });
}

async function rollback() {
  for (const name of ["client", "server"]) {
    const state = readJson(path.join(config.runtimeDir, `${name}-mods-swap.json`), null);
    if (!state || !fs.existsSync(state.backup)) continue;
    fs.rmSync(state.destination, { recursive: true, force: true });
    fs.renameSync(state.backup, state.destination);
  }
}

function verify(root, release) {
  for (const [relative, expected] of Object.entries(release.files || {})) {
    const file = path.join(root, relative);
    if (!fs.existsSync(file)) throw new Error(`release missing ${relative}`);
    const actual = crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
    if (actual !== expected) throw new Error(`checksum mismatch: ${relative}`);
  }
}
function notice(text, actions = []) {
  return fetchJson(`http://127.0.0.1:${config.listen.port}/v1/notices`, { token: config.sharedToken, method: "POST", body: { text, actions } }).catch((error) => console.warn(`[deploy] notice: ${error.message}`));
}
