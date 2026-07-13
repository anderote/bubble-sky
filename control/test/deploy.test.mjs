import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import test from "node:test";

test("client deployment swaps a verified mod set", async (t) => {
  const fixture = setup(false);
  const station = await startStation(t, fixture);
  const result = await command(["control/deploy-release.mjs", fixture.config, `--bundle=${fixture.bundle}`, "--now"]);
  assert.equal(result.code, 0, result.stderr);
  assert.equal(fs.readFileSync(path.join(fixture.mods, "new.jar"), "utf8"), "approved-new-mod");
  assert.equal(fs.existsSync(path.join(fixture.mods, "old.jar")), false);
  assert.equal(JSON.parse(fs.readFileSync(path.join(fixture.runtime, "applied-release.json"))).release, "play-test-success");
  station.kill("SIGTERM");
});

test("a failed post-swap restart restores previous client mods", async (t) => {
  const fixture = setup(true);
  const station = await startStation(t, fixture);
  const result = await command(["control/deploy-release.mjs", fixture.config, `--bundle=${fixture.bundle}`, "--now"]);
  assert.notEqual(result.code, 0);
  assert.equal(fs.readFileSync(path.join(fixture.mods, "old.jar"), "utf8"), "known-good-mod");
  assert.equal(fs.existsSync(path.join(fixture.mods, "new.jar")), false);
  station.kill("SIGTERM");
});

function setup(failingRestart) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bubble-deploy-"));
  const runtime = path.join(dir, "runtime");
  const prismRoot = path.join(dir, "prism");
  const mods = path.join(prismRoot, "test-instance/.minecraft/mods");
  const bundle = path.join(dir, "bundle");
  fs.mkdirSync(mods, { recursive: true });
  fs.mkdirSync(path.join(bundle, "client/mods"), { recursive: true });
  fs.writeFileSync(path.join(mods, "old.jar"), "known-good-mod");
  fs.writeFileSync(path.join(bundle, "client/mods/new.jar"), "approved-new-mod");
  const relative = "client/mods/new.jar";
  fs.writeFileSync(path.join(bundle, "release.json"), JSON.stringify({
    release: failingRestart ? "play-test-rollback" : "play-test-success", revision: "test", files: { [relative]: sha(path.join(bundle, relative)) }, impact: {},
  }));
  const port = 30000 + Math.floor(Math.random() * 1000);
  const config = path.join(dir, "station.json");
  fs.writeFileSync(config, JSON.stringify({
    nodeId: "deploy-test", repoRoot: process.cwd(), runtimeDir: runtime, sharedToken: "deploy-test-secret",
    listen: { host: "127.0.0.1", port }, providers: [], peers: [], roles: failingRestart ? ["client", "codex-agent"] : ["client"],
    deployment: { prismRoot, prismInstance: "test-instance", autoCloseMinecraft: false, autoLaunchPrism: false, agentRestartCommands: failingRestart ? [["/bin/false"]] : [] },
  }));
  return { dir, runtime, prismRoot, mods, bundle, port, config };
}

async function startStation(t, fixture) {
  const child = spawn(process.execPath, ["control/station.mjs", fixture.config], { cwd: process.cwd(), stdio: "ignore" });
  t.after(() => child.kill("SIGTERM"));
  const end = Date.now() + 5000;
  while (Date.now() < end) {
    try { if ((await fetch(`http://127.0.0.1:${fixture.port}/health`)).ok) return child; } catch {}
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error("station did not start");
}
function command(args) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, args, { cwd: process.cwd() });
    let stdout = "", stderr = "";
    child.stdout.on("data", (chunk) => stdout += chunk);
    child.stderr.on("data", (chunk) => stderr += chunk);
    child.on("close", (code) => resolve({ code, stdout, stderr }));
  });
}
function sha(file) { return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex"); }
