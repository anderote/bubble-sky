import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import test from "node:test";

test("station advertises and executes a routed template provider", async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bubble-station-"));
  const port = 29000 + Math.floor(Math.random() * 1000);
  const config = path.join(dir, "station.json");
  fs.writeFileSync(config, JSON.stringify({
    nodeId: "test-node", repoRoot: process.cwd(), runtimeDir: path.join(dir, "runtime"), sharedToken: "test-secret-long-enough",
    listen: { host: "127.0.0.1", port }, roles: ["test"], providers: [{ name: "echo", command: "/bin/echo", args: ["{prompt}"] }], peers: [],
  }));
  const child = spawn(process.execPath, ["control/station.mjs", config], { cwd: process.cwd(), stdio: "ignore" });
  t.after(() => child.kill("SIGTERM"));
  await eventually(async () => (await request(port, "/health", false)).ok);
  const caps = await request(port, "/v1/capabilities");
  assert.equal(caps.nodeId, "test-node");
  assert.equal(caps.providers[0].name, "echo");
  const job = await request(port, "/v1/route", true, { provider: "echo", kind: "chat", prompt: "hello minecraft", requester: "tester" });
  const complete = await eventually(async () => {
    const current = await request(port, `/v1/jobs/${job.id}`);
    return current.status === "complete" ? current : null;
  });
  assert.equal(complete.result, "hello minecraft");
  const notice = await request(port, "/v1/notices", true, { text: "picked up in Codex", channel: "dev" });
  assert.equal(notice.type, "dev.notice");
  const events = await request(port, "/v1/events");
  assert.ok(events.events.some((event) => event.type === "dev.notice" && event.text === "picked up in Codex"));
  const helper = path.resolve("scripts/station.mjs");
  const listed = spawnSync(process.execPath, [helper, "jobs", "--config", config], { encoding: "utf8" });
  assert.equal(listed.status, 0, listed.stderr);
  assert.match(listed.stdout, new RegExp(job.id));
  const handedOff = spawnSync(process.execPath, [helper, "handoff", job.id, "--config", config], { encoding: "utf8" });
  assert.equal(handedOff.status, 0, handedOff.stderr);
  assert.match(handedOff.stdout, /hello minecraft/);
  assert.match(handedOff.stdout, /Continue in this chat app/);
});

async function request(port, route, auth = true, body) {
  const response = await fetch(`http://127.0.0.1:${port}${route}`, { method: body ? "POST" : "GET", headers: { ...(auth ? { authorization: "Bearer test-secret-long-enough" } : {}), ...(body ? { "content-type": "application/json" } : {}) }, body: body ? JSON.stringify(body) : undefined });
  if (!response.ok) throw new Error(`${response.status}: ${await response.text()}`);
  return response.json();
}
async function eventually(fn, timeout = 6000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { try { const value = await fn(); if (value) return value; } catch {} await new Promise((resolve) => setTimeout(resolve, 80)); }
  throw new Error("timed out");
}
