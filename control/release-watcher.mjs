#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { loadConfig, readJson, fetchJson } from "./lib/common.mjs";
import { run } from "./lib/process.mjs";

const configPath = process.argv[2];
const config = loadConfig(configPath);
const once = process.argv.includes("--once");
const pollMs = Number(config.deployment?.pollMs || 30_000);
const repo = config.githubRepo || "anderote/bubble-sky";

do {
  try { await check(); }
  catch (error) { console.error(`[release-watcher] ${error.stack || error.message}`); }
  if (once) break;
  await new Promise((resolve) => setTimeout(resolve, pollMs));
} while (true);

async function check() {
  const viewed = await run("gh", ["release", "view", "--repo", repo, "--json", "tagName,isDraft,isPrerelease,assets"], { allowFailure: true, timeoutMs: 30_000 });
  if (viewed.code !== 0 || !viewed.stdout.trim()) return;
  const release = JSON.parse(viewed.stdout);
  if (release.isDraft || release.isPrerelease || !release.tagName.startsWith("play-")) return;
  const applied = readJson(path.join(config.runtimeDir, "applied-release.json"), {});
  if (applied.release === release.tagName) return;
  const staging = path.join(config.runtimeDir, "staging", release.tagName);
  const bundle = path.join(staging, "bundle");
  if (!fs.existsSync(path.join(bundle, "release.json"))) {
    fs.rmSync(staging, { recursive: true, force: true });
    fs.mkdirSync(bundle, { recursive: true });
    await run("gh", ["release", "download", release.tagName, "--repo", repo, "--pattern", "bubble-sky-release.tgz", "--dir", staging], { timeoutMs: 120_000 });
    await run("tar", ["-xzf", path.join(staging, "bubble-sky-release.tgz"), "-C", bundle], { timeoutMs: 120_000 });
  }
  await station("/v1/deploy/ready", { method: "POST", body: { release: release.tagName, state: "ready" } });
  if (!(await fleetReady(release.tagName))) return;
  await station("/v1/deploy/ready", { method: "POST", body: { release: release.tagName, state: "applying" } });
  await run(process.execPath, [path.join(config.repoRoot, "control/deploy-release.mjs"), configPath, `--bundle=${bundle}`], { cwd: config.repoRoot, timeoutMs: 900_000 });
  await station("/v1/deploy/ready", { method: "POST", body: { release: release.tagName, state: "live" } });
}

async function fleetReady(release) {
  const required = [{ nodeId: config.nodeId, url: `http://127.0.0.1:${config.listen.port}`, token: config.sharedToken }, ...config.peers.map((peer) => ({ ...peer, token: peer.token || config.sharedToken }))];
  for (const node of required) {
    try {
      const status = await fetchJson(`${node.url.replace(/\/$/, "")}/v1/deploy/ready/${encodeURIComponent(release)}`, { token: node.token });
      if (!["ready", "applying", "live"].includes(status.state)) return false;
    } catch { return false; }
  }
  return true;
}

function station(route, options) { return fetchJson(`http://127.0.0.1:${config.listen.port}${route}`, { token: config.sharedToken, ...options }); }
