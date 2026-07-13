#!/usr/bin/env node
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { run } from "../control/lib/process.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const out = path.resolve(process.argv[2] || path.join(root, "dist/bubble-sky-release"));
const lock = JSON.parse(fs.readFileSync(path.join(root, "release/mods.lock.json"), "utf8"));
const impact = JSON.parse(fs.readFileSync(path.join(root, "release/impact.json"), "utf8"));
fs.rmSync(out, { recursive: true, force: true });
for (const side of ["server", "client"]) fs.mkdirSync(path.join(out, side, "mods"), { recursive: true });

for (const project of ["bubble-sky-mod", "towerdefense"]) {
  await run("./gradlew", ["clean", "build", "--console=plain"], { cwd: path.join(root, "mods", project), timeoutMs: 600_000 });
  const libs = path.join(root, "mods", project, "build/libs");
  const jar = fs.readdirSync(libs).filter((name) => name.endsWith(".jar") && !name.includes("sources")).sort().at(-1);
  if (!jar) throw new Error(`no jar produced for ${project}`);
  const sides = project === "bubble-sky-mod" ? ["server"] : ["server", "client"];
  for (const side of sides) fs.copyFileSync(path.join(libs, jar), path.join(out, side, "mods", jar));
}

for (const mod of lock.mods) {
  const response = await fetch(`https://api.modrinth.com/v2/project/${encodeURIComponent(mod.project)}/version`, { headers: { "user-agent": "bubble-sky-release/1" } });
  if (!response.ok) throw new Error(`Modrinth ${mod.project}: ${response.status}`);
  const versions = await response.json();
  const file = versions.flatMap((version) => version.files || []).find((item) => item.filename === mod.filename);
  if (!file) throw new Error(`${mod.filename} not found in Modrinth project ${mod.project}`);
  const bytes = Buffer.from(await (await fetch(file.url)).arrayBuffer());
  const digest = crypto.createHash("sha256").update(bytes).digest("hex");
  if (digest !== mod.sha256) throw new Error(`${mod.filename} expected ${mod.sha256}, received ${digest}`);
  for (const side of mod.side === "both" ? ["server", "client"] : [mod.side]) fs.writeFileSync(path.join(out, side, "mods", mod.filename), bytes);
}

const runtime = path.join(out, "runtime");
for (const source of ["control", "mcp", "grok", "mindcraft", "scripts"]) {
  fs.cpSync(path.join(root, source), path.join(runtime, source), { recursive: true, filter: (item) => !item.includes("node_modules") && !item.includes("upstream") });
}
const revision = (await run("git", ["rev-parse", "HEAD"], { cwd: root })).stdout.trim();
const files = {};
walk(out, (file) => { files[path.relative(out, file)] = sha(file); });
const manifest = {
  schema: 1,
  release: process.env.BUBBLE_SKY_RELEASE || `play-${revision.slice(0, 12)}`,
  revision,
  builtAt: new Date().toISOString(),
  platform: { minecraft: lock.minecraft, fabricLoader: lock.fabricLoader, java: lock.java, node: 22 },
  impact,
  files,
};
fs.writeFileSync(path.join(out, "release.json"), `${JSON.stringify(manifest, null, 2)}\n`);
console.log(`${manifest.release} -> ${out} (${Object.keys(files).length} locked files)`);

function walk(dir, visit) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const target = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(target, visit); else visit(target);
  }
}
function sha(file) { return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex"); }
