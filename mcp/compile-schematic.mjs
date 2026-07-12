#!/usr/bin/env node
import fs from "node:fs/promises";
import path from "node:path";
import {
  COMPILED_BLUEPRINTS_DIR,
  compileSchematicPlan,
  findSchematicByName,
} from "./blueprint-compiler.mjs";

const args = process.argv.slice(2);

try {
  const options = parseArgs(args);
  if (!options.input) usage("missing input schematic");

  const input = await findSchematicByName(options.input) || path.resolve(options.input);
  const workers = Array.from({ length: options.workerCount }, (_, index) => `${options.workerPrefix}${index + 1}`);
  const plan = await compileSchematicPlan({
    input,
    name: options.name || path.basename(input, path.extname(input)),
    origin: options.origin,
    workers,
    clear: options.clear,
  });

  const output = path.resolve(options.output || path.join(COMPILED_BLUEPRINTS_DIR, `${plan.structure}.json`));
  await fs.mkdir(path.dirname(output), { recursive: true });
  await fs.writeFile(output, `${JSON.stringify(plan, null, 2)}\n`);

  console.log(`compiled ${input}`);
  console.log(`output ${output}`);
  console.log(`jobs ${plan.jobs.length} (${plan.source.clear ? "including clear pass" : "no clear pass"})`);
  console.log(`size ${plan.source.size.x}x${plan.source.size.y}x${plan.source.size.z}`);
} catch (error) {
  console.error(`compile failed: ${error.message}`);
  process.exit(1);
}

function parseArgs(values) {
  const options = {
    origin: { x: 0, y: 64, z: 0 },
    workerCount: Number(process.env.CODEX_SWARM_COUNT || 4),
    workerPrefix: process.env.CODEX_SWARM_PREFIX || "CodexDrone",
    clear: true,
  };

  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (value === "--help" || value === "-h") usage();
    if (value === "--origin") {
      options.origin = parseOrigin(requireValue(values, index += 1, "--origin"));
    } else if (value === "--out" || value === "--output") {
      options.output = requireValue(values, index += 1, value);
    } else if (value === "--name") {
      options.name = requireValue(values, index += 1, "--name");
    } else if (value === "--workers") {
      options.workerCount = Number(requireValue(values, index += 1, "--workers"));
    } else if (value === "--worker-prefix") {
      options.workerPrefix = requireValue(values, index += 1, "--worker-prefix");
    } else if (value === "--no-clear") {
      options.clear = false;
    } else if (!options.input) {
      options.input = value;
    } else {
      usage(`unknown argument: ${value}`);
    }
  }

  if (!Number.isFinite(options.workerCount) || options.workerCount < 1) {
    usage("--workers must be a positive number");
  }
  return options;
}

function parseOrigin(text) {
  const numbers = String(text).match(/-?\d+(?:\.\d+)?/g)?.map(Number) || [];
  if (numbers.length < 3) usage("--origin expects x,y,z");
  return { x: Math.round(numbers[0]), y: Math.round(numbers[1]), z: Math.round(numbers[2]) };
}

function requireValue(values, index, flag) {
  if (index >= values.length || values[index].startsWith("--")) usage(`${flag} expects a value`);
  return values[index];
}

function usage(error = null) {
  if (error) console.error(error);
  console.error(`Usage:
  node ./mcp/compile-schematic.mjs <name-or-path> --origin x,y,z [--out plan.json]

Examples:
  node ./mcp/compile-schematic.mjs castle --origin -40,72,40
  node ./mcp/compile-schematic.mjs ./mcp/blueprints/imported/castle.schem --origin 0,80,0 --no-clear
`);
  process.exit(error ? 1 : 0);
}
