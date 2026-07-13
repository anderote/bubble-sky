import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

export function expandHome(value) {
  if (typeof value !== "string") return value;
  return value === "~" ? os.homedir() : value.startsWith("~/") ? path.join(os.homedir(), value.slice(2)) : value;
}

export function readJson(file, fallback = undefined) {
  try { return JSON.parse(fs.readFileSync(file, "utf8")); }
  catch (error) {
    if (fallback !== undefined && error.code === "ENOENT") return fallback;
    throw error;
  }
}

export function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const temp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(temp, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  fs.renameSync(temp, file);
}

export function appendJsonl(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.appendFileSync(file, `${JSON.stringify(value)}\n`, { mode: 0o600 });
}

export function shortId(prefix = "job") {
  return `${prefix}-${Date.now().toString(36)}-${crypto.randomBytes(3).toString("hex")}`;
}

export function safeEqual(left = "", right = "") {
  const a = Buffer.from(String(left));
  const b = Buffer.from(String(right));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

export async function readRequestJson(request, limit = 256_000) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > limit) throw Object.assign(new Error("request body too large"), { statusCode: 413 });
    chunks.push(chunk);
  }
  if (!chunks.length) return {};
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

export function sendJson(response, status, body) {
  response.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  response.end(`${JSON.stringify(body)}\n`);
}

export function clip(text, length = 600) {
  const clean = String(text ?? "").replace(/\s+/g, " ").trim();
  return clean.length <= length ? clean : `${clean.slice(0, length - 1)}…`;
}

export function parseDurationMinutes(input) {
  let text = String(input || "").toLowerCase().trim()
    .replace(/\b(?:please|deployment|deploy|release|restart|delay|postpone|wait|later|for|by|another)\b/g, " ")
    .replace(/\s+/g, " ").trim();
  if (!text) return null;
  text = text
    .replace(/\bhalf\s+(?:an?\s+)?hour\b/g, "30 minutes")
    .replace(/\b(?:a\s+)?quarter\s+(?:of\s+an?\s+|an?\s+)?hour\b/g, "15 minutes")
    .replace(/\b(?:an?|one)\s+and\s+a\s+half\s+hours?\b/g, "90 minutes")
    .replace(/\b(an?|one)\s+hours?\b/g, "60 minutes")
    .replace(/\b(an?|one)\s+minutes?\b/g, "1 minute")
    .replace(/\ba\s+couple(?:\s+of)?\s+minutes?\b/g, "2 minutes")
    .replace(/\ba\s+few\s+minutes?\b/g, "3 minutes");
  text = replaceNumberWords(text);
  let total = 0;
  let matched = false;
  const pattern = /(\d+(?:\.\d+)?)\s*(days?|d|hours?|hrs?|hr|h|minutes?|mins?|min|m|seconds?|secs?|sec|s)\b/g;
  for (const match of text.matchAll(pattern)) {
    matched = true;
    const value = Number(match[1]);
    const unit = match[2];
    if (/^d/.test(unit)) total += value * 1440;
    else if (/^h/.test(unit)) total += value * 60;
    else if (/^s/.test(unit)) total += value / 60;
    else total += value;
  }
  if (!matched) {
    const bare = text.match(/^\d+(?:\.\d+)?$/);
    if (bare) total = Number(bare[0]);
    else return null;
  }
  return Math.max(1, Math.round(total));
}

function replaceNumberWords(text) {
  const small = { zero: 0, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12, thirteen: 13, fourteen: 14, fifteen: 15, sixteen: 16, seventeen: 17, eighteen: 18, nineteen: 19 };
  const tens = { twenty: 20, thirty: 30, forty: 40, fifty: 50, sixty: 60, seventy: 70, eighty: 80, ninety: 90 };
  return text.replace(/\b([a-z]+)(?:[-\s]+([a-z]+))?\s+(?=(?:days?|d|hours?|hrs?|hr|h|minutes?|mins?|min|m|seconds?|secs?|sec|s)\b)/g, (whole, first, second) => {
    if (small[first] !== undefined && !second) return `${small[first]} `;
    if (tens[first] !== undefined && !second) return `${tens[first]} `;
    if (tens[first] !== undefined && small[second] !== undefined) return `${tens[first] + small[second]} `;
    return whole;
  });
}

export function loadConfig(configPath) {
  const resolved = expandHome(configPath || process.env.BUBBLE_STATION_CONFIG || "~/.config/bubble-sky/station.json");
  const config = readJson(resolved);
  config.__path = resolved;
  config.repoRoot = path.resolve(expandHome(config.repoRoot || process.cwd()));
  config.runtimeDir = path.resolve(expandHome(config.runtimeDir || "~/.local/share/bubble-sky"));
  config.listen ||= {};
  config.listen.host ||= "127.0.0.1";
  config.listen.port ||= 25880;
  config.providers ||= [];
  config.peers ||= [];
  config.roles ||= ["client"];
  return config;
}

export async function fetchJson(url, { token, method = "GET", body, timeoutMs = 5000 } = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {
      method,
      signal: controller.signal,
      headers: {
        accept: "application/json",
        ...(body ? { "content-type": "application/json" } : {}),
        ...(token ? { authorization: `Bearer ${token}` } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await response.text();
    const parsed = text ? JSON.parse(text) : {};
    if (!response.ok) throw new Error(parsed.error || `${method} ${url} returned ${response.status}`);
    return parsed;
  } finally { clearTimeout(timer); }
}
