#!/usr/bin/env node
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { blueprintJobs, blueprintStore, normalizeBounds, parseBlueprintRequest } from "./build-blueprint-memory.mjs";

const root = fs.mkdtempSync(path.join(os.tmpdir(), "codex-blueprints-"));
try {
  assert.deepEqual(parseBlueprintRequest("save this to blueprints as twistedStaircase1"), {
    action: "save", name: "twistedStaircase1", flags: [],
  });
  assert.deepEqual(parseBlueprintRequest("save this between flags A1 and B2 as stairs"), {
    action: "save", name: "stairs", flags: ["A1", "B2"],
  });
  assert.equal(parseBlueprintRequest("make a workable twisted staircase here"), null);
  assert.equal(parseBlueprintRequest("build blueprint twistedStaircase1 here")?.action, "build");

  const bounds = normalizeBounds({ x: 3, y: 5, z: 7 }, { x: 1, y: 4, z: 6 });
  assert.deepEqual(bounds.size, { x: 3, y: 2, z: 2 });
  assert.equal(bounds.volume, 12);

  const store = blueprintStore(root);
  const saved = store.save("twistedStaircase1", bounds.min, bounds.max,
    ({ x, y, z }) => (x === 2 && y === 5 && z === 7 ? "oak_stairs[facing=east,half=bottom]" : "air"),
    { exclude: [{ x: 1, y: 4, z: 6 }] });
  assert.equal(saved.blocks.length, 12);
  assert.deepEqual(store.list(), ["twistedStaircase1"]);
  assert.equal(store.load("twistedStaircase1").blocks.some((entry) => entry.block.includes("oak_stairs")), true);

  const jobs = blueprintJobs(saved, { x: 100, y: 70, z: -20 }, "CodexDrone1");
  const stair = jobs.find((job) => job.block.startsWith("oak_stairs"));
  assert.deepEqual({ x: stair.x, y: stair.y, z: stair.z }, { x: 101, y: 71, z: -19 });
  assert.equal(jobs.every((job) => job.worker === "CodexDrone1"), true);
  assert.equal(store.remove("twistedStaircase1"), true);
  assert.deepEqual(store.list(), []);
  console.log("build blueprint memory tests passed");
} finally {
  fs.rmSync(root, { recursive: true, force: true });
}
