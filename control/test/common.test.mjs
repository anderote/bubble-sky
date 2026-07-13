import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { readJson, safeEqual, writeJson } from "../lib/common.mjs";

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
