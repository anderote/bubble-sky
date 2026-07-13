import fs from "node:fs";
import path from "node:path";

export const MAX_BLUEPRINT_VOLUME = 32_768;

export function blueprintStore(root = ".codex-runtime/blueprints") {
  const directory = path.resolve(root);

  function save(name, cornerA, cornerB, readBlock, options = {}) {
    const safeName = normalizeBlueprintName(name);
    const bounds = normalizeBounds(cornerA, cornerB);
    if (bounds.volume > MAX_BLUEPRINT_VOLUME) {
      throw new Error(`selection is ${bounds.volume} blocks; maximum is ${MAX_BLUEPRINT_VOLUME}`);
    }
    const excluded = new Set((options.exclude || []).map(positionKey));
    const blocks = [];
    for (let y = bounds.min.y; y <= bounds.max.y; y += 1) {
      for (let z = bounds.min.z; z <= bounds.max.z; z += 1) {
        for (let x = bounds.min.x; x <= bounds.max.x; x += 1) {
          const world = { x, y, z };
          const block = excluded.has(positionKey(world)) ? "air" : String(readBlock(world) || "air");
          blocks.push({ x: x - bounds.min.x, y: y - bounds.min.y, z: z - bounds.min.z, block });
        }
      }
    }
    const blueprint = {
      schema: 1,
      name: safeName,
      size: bounds.size,
      source: { min: bounds.min, max: bounds.max },
      blocks,
      savedAt: new Date().toISOString(),
    };
    fs.mkdirSync(directory, { recursive: true });
    atomicWrite(fileFor(directory, safeName), blueprint);
    return blueprint;
  }

  function load(name) {
    const safeName = normalizeBlueprintName(name);
    const file = fileFor(directory, safeName);
    if (!fs.existsSync(file)) return null;
    return validateBlueprint(JSON.parse(fs.readFileSync(file, "utf8")));
  }

  function list() {
    if (!fs.existsSync(directory)) return [];
    return fs.readdirSync(directory)
      .filter((entry) => entry.endsWith(".json"))
      .map((entry) => entry.slice(0, -5))
      .sort((a, b) => a.localeCompare(b));
  }

  function remove(name) {
    const file = fileFor(directory, normalizeBlueprintName(name));
    if (!fs.existsSync(file)) return false;
    fs.unlinkSync(file);
    return true;
  }

  return { directory, save, load, list, remove };
}

export function blueprintJobs(blueprint, origin, worker) {
  const checked = validateBlueprint(blueprint);
  return checked.blocks.map((entry, index) => ({
    id: `blueprint-${slug(checked.name)}-${String(index + 1).padStart(6, "0")}`,
    worker,
    phase: entry.block === "air" ? "clear" : phaseForBlock(entry.block),
    x: origin.x + entry.x,
    y: origin.y + entry.y,
    z: origin.z + entry.z,
    block: entry.block,
  }));
}

export function parseBlueprintRequest(command) {
  const text = String(command || "").trim();
  if (/\b(?:list|show|what)\b.*\bblueprints?\b/i.test(text)) return { action: "list" };
  const forget = text.match(/\b(?:forget|delete|remove)\s+(?:the\s+)?(?:blueprint\s+)?([a-z][a-z0-9_-]{0,47})\b/i);
  if (forget) return { action: "forget", name: forget[1] };
  const save = text.match(/\b(?:save|remember|capture)\b[\s\S]*?\b(?:as|named)\s+(?:blueprint\s+)?([a-z][a-z0-9_-]{0,47})\s*$/i);
  if (save) {
    const flags = explicitFlagNames(text);
    return { action: "save", name: save[1], flags };
  }
  const recall = text.match(/^\s*(?:build|place|paste|load|recall|rebuild)\s+(?:the\s+)?(?:blueprint\s+)?([a-z][a-z0-9_-]{0,47})(?:\s+(?:here|there|again|near me|where i(?:'m| am) looking))?\s*$/i);
  if (recall) return { action: "build", name: recall[1] };
  return null;
}

export function normalizeBounds(a, b) {
  const min = { x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), z: Math.min(a.z, b.z) };
  const max = { x: Math.max(a.x, b.x), y: Math.max(a.y, b.y), z: Math.max(a.z, b.z) };
  const size = { x: max.x - min.x + 1, y: max.y - min.y + 1, z: max.z - min.z + 1 };
  return { min, max, size, volume: size.x * size.y * size.z };
}

function explicitFlagNames(text) {
  const match = text.match(/\bbetween\s+(?:flags?\s+)?([a-z][a-z0-9_-]*)\s+(?:and|to)\s+([a-z][a-z0-9_-]*)\b/i);
  return match ? [match[1], match[2]] : [];
}

function normalizeBlueprintName(name) {
  const value = String(name || "").trim();
  if (!/^[a-z][a-z0-9_-]{0,47}$/i.test(value)) {
    throw new Error("blueprint names must start with a letter and use only letters, numbers, _ or -");
  }
  return value;
}

function validateBlueprint(value) {
  if (value?.schema !== 1 || !value.name || !Array.isArray(value.blocks)) throw new Error("invalid blueprint file");
  if (!value.size || value.blocks.length !== value.size.x * value.size.y * value.size.z) {
    throw new Error("blueprint block count does not match its dimensions");
  }
  return value;
}

function phaseForBlock(block) {
  const name = String(block).replace(/\[.*$/, "");
  if (/(stairs|ladder|scaffolding)/.test(name)) return "stairs";
  if (/(door|trapdoor|gate)/.test(name)) return "openings";
  if (/(torch|lantern|light)/.test(name)) return "lighting";
  if (/(slab|wall|fence|log|pillar)/.test(name)) return "structure";
  return "blocks";
}

function fileFor(directory, name) { return path.join(directory, `${name}.json`); }
function positionKey(p) { return `${p.x},${p.y},${p.z}`; }
function slug(text) { return String(text).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "build"; }
function atomicWrite(file, value) {
  const temp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(temp, `${JSON.stringify(value, null, 2)}\n`);
  fs.renameSync(temp, file);
}
