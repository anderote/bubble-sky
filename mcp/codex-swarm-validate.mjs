#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import minecraftData from "../mindcraft/upstream/node_modules/minecraft-data/index.js";

const runtimeDir = process.env.CODEX_SWARM_RUNTIME || ".codex-runtime/swarm";
const version = process.env.MINECRAFT_VERSION || "1.21.6";
const statePath = process.argv[2] || path.join(runtimeDir, "state.json");
const blocksByName = minecraftData(version)?.blocksByName || {};

if (!fs.existsSync(statePath)) {
  fail(`missing swarm state: ${statePath}`);
}

const errors = [];
const state = readJson(statePath);
const jobs = Array.isArray(state.jobs) ? state.jobs : [];
const ids = new Set();
const workers = new Set();

requireString(state.taskId, "taskId");
requireString(state.status, "status");
requireString(state.structure, "structure");
if (state.source != null) requireBoundedString(state.source, "source", 80);
if (state.assignedTo != null) requireBoundedString(state.assignedTo, "assignedTo", 80);
if (state.requestedBy != null) requireBoundedString(state.requestedBy, "requestedBy", 80);
if (state.originalText != null) requireBoundedString(state.originalText, "originalText", 800);
if (state.instructions != null) requireBoundedString(state.instructions, "instructions", 1200);
if (!state.origin || !["x", "y", "z"].every((key) => Number.isFinite(state.origin[key]))) {
  errors.push("origin must contain numeric x/y/z");
}
if (!jobs.length) errors.push("jobs must be a non-empty array");
if (state.source === "codex-architect" && !["complete", "complete_with_failures"].includes(state.status)) {
  if (!state.requestedBy) errors.push("requestedBy must be present for active codex-architect tasks");
  if (!state.originalText) errors.push("originalText must be present for active codex-architect tasks");
  if (!state.instructions) errors.push("instructions must be present for active codex-architect tasks");
}

for (const [index, job] of jobs.entries()) {
  const label = `jobs[${index}]`;
  if (!job || typeof job !== "object") {
    errors.push(`${label} must be an object`);
    continue;
  }
  if (!job.id || typeof job.id !== "string") {
    errors.push(`${label}.id must be a string`);
  } else if (ids.has(job.id)) {
    errors.push(`${label}.id duplicates ${job.id}`);
  } else {
    ids.add(job.id);
  }
  if (!job.worker || typeof job.worker !== "string") errors.push(`${label}.worker must be a string`);
  else workers.add(job.worker);
  if (!job.phase || typeof job.phase !== "string") errors.push(`${label}.phase must be a string`);
  for (const key of ["x", "y", "z"]) {
    if (!Number.isInteger(job[key])) errors.push(`${label}.${key} must be an integer`);
  }
  const block = commandBlockName(job.block);
  if (!block) errors.push(`${label}.block must be a string`);
  else if (!blocksByName[block]) errors.push(`${label}.block is unknown for ${version}: ${job.block}`);
}

if (state.jobCount != null && Number(state.jobCount) !== jobs.length) {
  errors.push(`jobCount ${state.jobCount} does not match jobs.length ${jobs.length}`);
}
if (state.jobCount != null && !Number.isInteger(Number(state.jobCount))) {
  errors.push("jobCount must be an integer");
}

if (errors.length) {
  console.error(`invalid swarm state ${statePath}`);
  for (const error of errors.slice(0, 50)) console.error(`- ${error}`);
  if (errors.length > 50) console.error(`- ... ${errors.length - 50} more`);
  process.exit(1);
}

console.log(`valid swarm state: ${state.taskId}`);
console.log(`status: ${state.status}`);
console.log(`structure: ${state.structure}`);
console.log(`jobs: ${jobs.length}`);
console.log(`workers: ${[...workers].sort().join(", ") || "none"}`);

function requireString(value, field) {
  if (!value || typeof value !== "string") errors.push(`${field} must be a string`);
}

function requireBoundedString(value, field, maxLength) {
  if (typeof value !== "string" || !value.trim()) {
    errors.push(`${field} must be a non-empty string`);
    return;
  }
  if (value.length > maxLength) errors.push(`${field} must be at most ${maxLength} characters`);
}

function commandBlockName(block) {
  return String(block || "").replace(/^minecraft:/, "").replace(/\[.*$/, "");
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (error) {
    fail(`failed to read ${file}: ${error.message}`);
  }
}

function fail(message) {
  console.error(message);
  process.exit(1);
}
