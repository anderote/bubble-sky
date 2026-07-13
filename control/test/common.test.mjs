import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { parseDurationMinutes, readJson, safeEqual, writeJson } from "../lib/common.mjs";

test("writeJson is atomic and readable", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bubble-common-"));
  const file = path.join(dir, "nested/value.json");
  writeJson(file, { hello: "sky" });
  assert.deepEqual(readJson(file), { hello: "sky" });
});

test("safeEqual compares tokens without prefix matches", () => {
  assert.equal(safeEqual("secret", "secret"), true);
  assert.equal(safeEqual("secret", "secret2"), false);
  assert.equal(safeEqual("", "x"), false);
});

test("natural deployment durations become minutes", () => {
  assert.equal(parseDurationMinutes("later 10mins"), 10);
  assert.equal(parseDurationMinutes("postpone deployment for ten minutes"), 10);
  assert.equal(parseDurationMinutes("wait 1h 30m"), 90);
  assert.equal(parseDurationMinutes("delay for half an hour"), 30);
  assert.equal(parseDurationMinutes("another twenty-five minutes please"), 25);
  assert.equal(parseDurationMinutes("an hour"), 60);
  assert.equal(parseDurationMinutes("90 seconds"), 2);
  assert.equal(parseDurationMinutes("later"), null);
});
