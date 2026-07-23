#!/usr/bin/env node
import path from "node:path";
import { pathToFileURL } from "node:url";
import { clip, fetchJson, loadConfig, parseDurationMinutes } from "./lib/common.mjs";

const config = loadConfig(process.argv[2]);
const mc = config.minecraft || {};
const stationUrl = `http://127.0.0.1:${config.listen.port}`;
const bot = await createBot();
const paired = new Map();
const lastJob = new Map();
const preferredProvider = new Map();
const eventCursors = new Map();

bot.once("spawn", () => {
  console.log(`[dev-chat] ${bot.username} joined ${mc.host || "127.0.0.1"}:${mc.port || 25565}`);
  sendPublic("Dev Station online. Whisper me 'pair <code>', then use @dev help.");
  pollEvents();
});
bot.on("chat", (speaker, message) => handleMessage(speaker, message, false));
bot.on("whisper", (speaker, message) => handleMessage(speaker, message, true));
bot.on("kicked", (reason) => console.error("[dev-chat] kicked", reason));
bot.on("error", (error) => console.error("[dev-chat]", error));

async function createBot() {
  const candidates = [
    path.join(config.repoRoot, "mindcraft/upstream/node_modules/mineflayer/index.js"),
    path.join(config.repoRoot, "node_modules/mineflayer/index.js"),
  ];
  let mineflayer;
  for (const candidate of candidates) {
    try { mineflayer = await import(pathToFileURL(candidate)); break; } catch {}
  }
  if (!mineflayer) throw new Error("mineflayer not found; run mindcraft/bootstrap.sh first");
  return mineflayer.default.createBot({
    host: mc.host || "127.0.0.1",
    port: Number(mc.port || 25565),
    username: mc.username || "DevStation",
    version: mc.version || "1.21.6",
    auth: "offline",
  });
}

async function handleMessage(speaker, raw, whispered) {
  if (speaker === bot.username) return;
  const allowed = (mc.allowedPlayers || []).map((name) => name.toLowerCase());
  if (allowed.length && !allowed.includes(speaker.toLowerCase())) return;
  let command = raw.trim();
  let directProvider = null;
  const directlyAddressed = command.match(/^@(codex|claude)\b\s*(.*)$/i);
  if (directlyAddressed) {
    directProvider = directlyAddressed[1].toLowerCase();
    command = directlyAddressed[2].trim();
  } else if (/^@dev\b/i.test(command)) command = command.replace(/^@dev\s*/i, "");
  else if (!whispered) return;

  try {
    const pair = command.match(/^pair\s+(\d{6})$/i);
    if (pair) {
      const result = await api("/v1/pair", { method: "POST", body: { player: speaker, code: pair[1] } });
      paired.set(speaker.toLowerCase(), Date.parse(result.expiresAt));
      return tell(speaker, "Paired for 12 hours. Try @codex hello, @claude hello, or @dev help.");
    }
    if (!isPaired(speaker)) return tell(speaker, "Pair first: whisper DevStation 'pair <code shown on your laptop>'.");
    if (!command || command === "help") return showHelp(speaker);

    if (directProvider) {
      if (!command) return tell(speaker, `Add a question or change, e.g. @${directProvider} explain the bridge.`);
      const work = command.match(/^(?:work|dev)\s+([\s\S]+)$/i);
      const chat = command.match(/^(?:ask|chat)\s+([\s\S]+)$/i);
      return submit(speaker, {
        kind: work ? "dev" : "chat",
        provider: directProvider,
        prompt: work?.[1] || chat?.[1] || command,
      });
    }
    const preference = command.match(/^(?:use|switch)(?:\s+to)?\s+(codex|claude)$/i);
    if (preference) {
      const provider = preference[1].toLowerCase();
      const fleet = await api("/v1/fleet");
      if (!fleet.nodes.some((node) => !node.offline && node.providers?.some((item) => item.name === provider))) {
        return tell(speaker, `${provider} is not reachable right now. Use @dev agents to check the laptops.`);
      }
      preferredProvider.set(speaker.toLowerCase(), provider);
      return tell(speaker, `Using ${provider}. Short forms now work: @dev ask <question> or @dev work <change>.`);
    }
    if (/^(?:current|agent)$/i.test(command)) {
      return tell(speaker, `Your default is ${defaultProvider(speaker)}. Switch with @dev use codex or @dev use claude.`);
    }
    if (/^(agents|fleet|who)$/i.test(command)) {
      const fleet = await api("/v1/fleet");
      for (const node of fleet.nodes) tell(speaker, node.offline ? `${node.nodeId}: offline` : `${node.displayName}: ${node.providers.map((p) => p.name).join(" + ") || "no agents"} [${node.roles.join(", ")}]`);
      return;
    }
    const status = command.match(/^status(?:\s+(\S+))?$/i);
    if (status) {
      const id = status[1] || lastJob.get(speaker.toLowerCase());
      if (!id) return tell(speaker, "No recent job. Use @dev status <job-id>.");
      const job = await api(`/v1/jobs/${encodeURIComponent(id)}`);
      return tell(speaker, `${job.id}: ${job.status}${job.prUrl ? ` ${job.prUrl}` : ""}${job.result ? ` — ${clip(job.result, 360)}` : ""}`);
    }
    const reply = command.match(/^reply\s+(?:(\S+)\s+)?(.+)$/i);
    if (reply) {
      const parent = reply[1]?.includes("-") ? reply[1] : lastJob.get(speaker.toLowerCase());
      const prompt = reply[1]?.includes("-") ? reply[2] : [reply[1], reply[2]].filter(Boolean).join(" ");
      if (!parent) return tell(speaker, "No conversation to continue.");
      const old = await api(`/v1/jobs/${encodeURIComponent(parent)}`);
      if (old.kind !== "chat") return tell(speaker, "Dev jobs are continued through their PR. Start a new @dev work request if needed.");
      return submit(speaker, { kind: "chat", provider: old.provider, targetNode: old.nodeId, prompt, continuationOf: parent });
    }
    if (/^(?:handoff|app)(?:\s+(\S+))?$/i.test(command)) {
      const id = command.match(/^(?:handoff|app)(?:\s+(\S+))?$/i)?.[1] || lastJob.get(speaker.toLowerCase());
      if (!id) return tell(speaker, "No recent job to hand off.");
      return tell(speaker, `Open Codex or Claude and say 'Continue Minecraft job ${id}', or run ./scripts/station.mjs handoff ${id}.`);
    }
    if (/^(?:later|wait|postpone|delay)\b/i.test(command) || /\b(?:mins?|minutes?|hours?|hrs?|seconds?|secs?)\b/i.test(command)) {
      const minutes = parseDurationMinutes(command);
      if (!minutes) return tell(speaker, "Tell me how long, e.g. @dev later 10mins or @dev postpone for half an hour.");
      const delayed = await api("/v1/deploy/postpone", { method: "POST", body: { minutes, requester: speaker } });
      return tell(speaker, `Okay—deployment postponed ${delayed.minutes} minutes.`);
    }
    const directed = command.match(/^(ask|chat|work|dev)\s+(?:(\S+)\/)?(codex|claude)\s+([\s\S]+)$/i);
    if (directed) return submit(speaker, { kind: /^(work|dev)$/i.test(directed[1]) ? "dev" : "chat", targetNode: directed[2] || undefined, provider: directed[3].toLowerCase(), prompt: directed[4] });
    const short = command.match(/^(ask|chat|work|dev)\s+([\s\S]+)$/i);
    if (short) return submit(speaker, { kind: /^(work|dev)$/i.test(short[1]) ? "dev" : "chat", provider: defaultProvider(speaker), prompt: short[2] });
    tell(speaker, "I didn't understand. Try @dev help.");
  } catch (error) { tell(speaker, `Dev Station error: ${clip(error.message, 240)}`); }
}

async function submit(speaker, payload) {
  const job = await api("/v1/route", { method: "POST", body: { ...payload, requester: speaker } });
  lastJob.set(speaker.toLowerCase(), job.id);
  tell(speaker, `${job.provider} accepted ${job.id} on ${job.nodeId}. I'll report back here.`);
}

function showHelp(player) {
  [
    "@dev agents — see Codex/Claude laptops",
    "@codex <question> / @claude <question> — ask either agent from Minecraft",
    "@codex work <change> / @claude work <change> — create a dev PR",
    "@dev use codex|claude — choose short-command default",
    "@dev ask <question> / @dev work <change> — use your default",
    "@dev reply [job-id] <text> — continue a conversation",
    "@dev handoff — continue the active job in a desktop chat app",
    "@dev status [job-id] — inspect work",
    "@dev later 10mins — postpone an announced deployment (natural durations work)",
  ].forEach((line) => tell(player, line));
}

function isPaired(player) { return (paired.get(player.toLowerCase()) || 0) > Date.now(); }
function defaultProvider(player) { return preferredProvider.get(player.toLowerCase()) || mc.defaultProvider || config.providers[0]?.name || "codex"; }
function api(route, options) { return fetchJson(`${stationUrl}${route}`, { token: config.sharedToken, ...options }); }

async function pollEvents() {
  const endpoints = [{ id: config.nodeId, url: stationUrl, token: config.sharedToken }, ...config.peers.map((peer) => ({ id: peer.nodeId, url: peer.url.replace(/\/$/, ""), token: peer.token || config.sharedToken }))];
  await Promise.all(endpoints.map(async (endpoint) => {
    try {
      const since = eventCursors.get(endpoint.id) || 0;
      const result = await fetchJson(`${endpoint.url}/v1/events?since=${since}`, { token: endpoint.token });
      const firstPoll = !eventCursors.has(endpoint.id);
      for (const event of result.events) {
        eventCursors.set(endpoint.id, Math.max(eventCursors.get(endpoint.id) || 0, event.id));
        if (firstPoll) continue;
        if (event.type === "station.started" || event.type === "job.started") continue;
        announceEvent(event);
      }
    } catch (error) { console.warn(`[dev-chat] event poll ${endpoint.id}: ${error.message}`); }
  }));
  setTimeout(pollEvents, Number(mc.eventPollMs || 3000));
}

function announceEvent(event) {
  const prefix = event.type === "notice" ? "[DEPLOY]" : "[DEV]";
  sendPublic(`${prefix} ${event.text}`);
  for (const action of event.actions || []) sendPublic(`${prefix} ${action.label}: ${action.command}`);
}

function tell(player, text) {
  for (const part of chunks(text, 230)) bot.chat(`/msg ${player} ${part}`);
}
function sendPublic(text) { for (const part of chunks(text, 230)) bot.chat(part); }
function chunks(text, size) {
  const words = String(text).replace(/[\r\n]+/g, " ").split(/\s+/);
  const result = [];
  let current = "";
  for (const word of words) {
    if (`${current} ${word}`.trim().length > size) { if (current) result.push(current); current = word; }
    else current = `${current} ${word}`.trim();
  }
  if (current) result.push(current);
  return result;
}
