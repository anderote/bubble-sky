import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

test("friendly Station helper advertises the setup and recovery commands", () => {
  const helper = path.resolve("scripts/station.mjs");
  const result = spawnSync(process.execPath, [helper, "help"], { encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /setup andrew/);
  assert.match(result.stdout, /doctor/);
  assert.match(result.stdout, /pair-code/);
  assert.match(result.stdout, /jobs/);
  assert.match(result.stdout, /handoff latest/);
  assert.match(result.stdout, /announce TEXT/);
  assert.match(result.stdout, /logs \[station\|chat\|release\]/);
});

test("non-interactive setup creates a private Andrew configuration", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "bubble-station-helper-"));
  const configPath = path.join(directory, "station.json");
  const result = spawnSync(process.execPath, [
    path.resolve("scripts/station.mjs"), "setup", "andrew", "--defaults", "--config", configPath,
  ], { encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr);
  const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
  assert.equal(config.nodeId, "andrew-mac");
  assert.equal(config.providers[0].name, "claude");
  assert.equal(config.minecraft.defaultProvider, "claude");
  assert.ok(config.roles.includes("server"));
  assert.ok(config.sharedToken.length >= 24);
  assert.equal(fs.statSync(configPath).mode & 0o777, 0o600);
});
