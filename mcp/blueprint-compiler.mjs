import fs from "node:fs/promises";
import path from "node:path";
import { createRequire } from "node:module";
import { Vec3 } from "vec3";
import { normalizeBuildState } from "../shared/build-protocol/index.mjs";

const require = createRequire(import.meta.url);
const { Schematic } = require("prismarine-schematic");

export const BLUEPRINTS_DIR = path.join("mcp", "blueprints");
export const IMPORTED_BLUEPRINTS_DIR = path.join(BLUEPRINTS_DIR, "imported");
export const COMPILED_BLUEPRINTS_DIR = path.join(BLUEPRINTS_DIR, "compiled");

export async function compileSchematicPlan({
  input,
  name,
  origin,
  workers,
  chunkSize = 32,
  clear = true,
  structure = null,
}) {
  if (!input) throw new Error("missing schematic input path");
  if (!origin) throw new Error("missing origin");
  if (!workers?.length) throw new Error("missing workers");

  const schematic = await Schematic.read(await fs.readFile(input));
  const jobs = [];
  const originVec = new Vec3(origin.x, origin.y, origin.z);
  const start = schematic.start();
  const end = schematic.end();
  const size = schematic.size;
  const structureName = structure || name || path.basename(input, path.extname(input));

  if (clear) {
    for (let y = start.y; y <= end.y; y += 1) {
      for (let z = start.z; z <= end.z; z += 1) {
        for (let x = start.x; x <= end.x; x += 1) {
          const worldPos = schematicPosToWorld(originVec, start, { x, y, z });
          jobs.push(job(worldPos, "air", "clear"));
        }
      }
    }
  }

  await schematic.forEach(async (block, pos) => {
    if (!block || block.name === "air" || block.name === "void_air" || block.name === "cave_air") return;
    const worldPos = schematicPosToWorld(originVec, start, pos);
    jobs.push(job(worldPos, blockToCommandBlock(block), inferPhase(block, pos, start, end)));
  });

  assignJobs(jobs, workers, chunkSize);
  jobs.forEach((entry, index) => {
    entry.id = `${slug(structureName)}-${String(index + 1).padStart(6, "0")}`;
  });

  return normalizeBuildState({
    taskId: `${slug(structureName)}-${Date.now()}`,
    status: "building",
    structure: structureName,
    origin,
    source: {
      type: "schematic",
      path: input,
      version: schematic.version,
      size: { x: size.x, y: size.y, z: size.z },
      offset: { x: start.x, y: start.y, z: start.z },
      clear,
    },
    workers,
    jobs,
    createdAt: new Date().toISOString(),
  });
}

export async function findSchematicByName(name, cwd = process.cwd()) {
  if (!name) return null;
  const candidates = schematicNameCandidates(name)
    .flatMap((file) => [
      path.resolve(cwd, file),
      path.resolve(cwd, IMPORTED_BLUEPRINTS_DIR, file),
    ]);

  for (const candidate of candidates) {
    try {
      const stat = await fs.stat(candidate);
      if (stat.isFile()) return candidate;
    } catch {
      // Try next candidate.
    }
  }
  return null;
}

export async function listImportedSchematics(cwd = process.cwd()) {
  const dir = path.resolve(cwd, IMPORTED_BLUEPRINTS_DIR);
  try {
    const entries = await fs.readdir(dir, { withFileTypes: true });
    return entries
      .filter((entry) => entry.isFile() && /\.(schem|schematic)$/i.test(entry.name))
      .map((entry) => entry.name)
      .sort((a, b) => a.localeCompare(b));
  } catch {
    return [];
  }
}

export function schematicNameCandidates(name) {
  const trimmed = String(name).trim();
  if (!trimmed) return [];
  if (/\.(schem|schematic)$/i.test(trimmed)) return [trimmed];
  return [`${trimmed}.schem`, `${trimmed}.schematic`];
}

function schematicPosToWorld(origin, start, pos) {
  return {
    x: origin.x + (pos.x - start.x),
    y: origin.y + (pos.y - start.y),
    z: origin.z + (pos.z - start.z),
  };
}

function blockToCommandBlock(block) {
  const properties = block.getProperties?.() || {};
  const state = Object.entries(properties)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, value]) => `${key}=${String(value)}`)
    .join(",");
  return state ? `${block.name}[${state}]` : block.name;
}

function inferPhase(block, pos, start, end) {
  const name = block.name;
  if (pos.y === start.y) return "foundation";
  if (/(log|pillar|fence|wall)$/.test(name) || name.includes("_wall")) return "supports";
  if (name.includes("stairs") || name.includes("ladder")) return "stairs";
  if (name.includes("door") || name.includes("trapdoor") || name.includes("gate")) return "openings";
  if (name.includes("glass") || name.includes("pane")) return "windows";
  if (name.includes("torch") || name.includes("lantern") || name.includes("light")) return "lighting";
  if (/(slab|planks|deepslate|stone|bricks|brick|concrete|terracotta|wool)/.test(name)) {
    if (pos.y >= end.y - 1) return "roof";
    return "walls";
  }
  return "detail";
}

function job(pos, block, phase) {
  return { x: pos.x, y: pos.y, z: pos.z, block, phase };
}

function sortJobs(jobs) {
  const phaseOrder = [
    "clear",
    "foundation",
    "floor",
    "base",
    "supports",
    "posts",
    "walls",
    "wall",
    "platform",
    "stairs",
    "ladder",
    "openings",
    "windows",
    "door",
    "lintel",
    "roof",
    "railing",
    "lighting",
    "detail",
  ];
  jobs.sort((a, b) => {
    const phaseDelta = phaseIndex(phaseOrder, a.phase) - phaseIndex(phaseOrder, b.phase);
    if (phaseDelta) return phaseDelta;
    if (a.y !== b.y) return a.y - b.y;
    if (a.z !== b.z) return a.z - b.z;
    return a.x - b.x;
  });
}

function assignJobs(jobs, workers, chunkSize) {
  sortJobs(jobs);
  const safeChunkSize = Number.isFinite(chunkSize) && chunkSize > 0 ? Math.floor(chunkSize) : 32;
  let chunkIndex = 0;
  let lastPhase = null;

  for (let index = 0; index < jobs.length; index += 1) {
    const job = jobs[index];
    if (job.phase !== lastPhase) {
      lastPhase = job.phase;
      chunkIndex = 0;
    }
    const workerIndex = Math.floor(chunkIndex / safeChunkSize) % workers.length;
    job.worker = workers[workerIndex];
    chunkIndex += 1;
  }
}

function phaseIndex(phaseOrder, phase) {
  const index = phaseOrder.indexOf(phase);
  return index === -1 ? phaseOrder.length : index;
}

function slug(text) {
  return String(text)
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "") || "schematic";
}
