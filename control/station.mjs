#!/usr/bin/env node
import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { AgentRunner } from "./lib/agent-runner.mjs";
import { appendJsonl, fetchJson, loadConfig, readJson, readRequestJson, safeEqual, sendJson, shortId, writeJson } from "./lib/common.mjs";

const config = loadConfig(process.argv[2]);
if (!config.nodeId) throw new Error("station.json must define nodeId");
if (!config.sharedToken || config.sharedToken === "CHANGE_ME") throw new Error("station.json must define a strong sharedToken");

fs.mkdirSync(config.runtimeDir, { recursive: true });
const eventsFile = path.join(config.runtimeDir, "events.jsonl");
const stateFile = path.join(config.runtimeDir, "station-state.json");
const pairingFile = path.join(config.runtimeDir, "pairing.json");
const state = readJson(stateFile, { sequence: 0, postponements: {} });
state.deployments ||= {};
const pairing = { code: String(crypto.randomInt(100000, 999999)), expiresAt: Date.now() + 12 * 60 * 60 * 1000 };
writeJson(pairingFile, pairing);

function publish(type, subject, text, extra = {}) {
  const event = { id: ++state.sequence, type, nodeId: config.nodeId, at: new Date().toISOString(), text, subjectId: subject?.id || null, ...extra };
  appendJsonl(eventsFile, event);
  writeJson(stateFile, state);
  return event;
}

const runner = new AgentRunner(config, { event: publish });

function capabilities() {
  return {
    protocol: 1,
    nodeId: config.nodeId,
    displayName: config.displayName || config.nodeId,
    roles: config.roles,
    providers: config.providers.map(({ name, model, profile }) => ({ name, model: model || null, profile: profile || null })),
    address: config.publicUrl || `http://${config.listen.host}:${config.listen.port}`,
    startedAt,
  };
}

function authorized(request) {
  return safeEqual(request.headers.authorization?.replace(/^Bearer\s+/i, "") || "", config.sharedToken);
}

async function locate(provider, targetNode) {
  const self = capabilities();
  if ((!targetNode || targetNode === self.nodeId) && self.providers.some((item) => item.name === provider)) return { local: true, capability: self };
  const candidates = targetNode ? config.peers.filter((peer) => peer.nodeId === targetNode) : config.peers;
  for (const peer of candidates) {
    try {
      const remote = await fetchJson(`${peer.url.replace(/\/$/, "")}/v1/capabilities`, { token: peer.token || config.sharedToken });
      if (remote.providers?.some((item) => item.name === provider)) return { local: false, peer, capability: remote };
    } catch (error) { console.warn(`[station] peer ${peer.nodeId || peer.url}: ${error.message}`); }
  }
  throw Object.assign(new Error(`no reachable ${provider} provider${targetNode ? ` on ${targetNode}` : ""}`), { statusCode: 503 });
}

async function routeJob(body) {
  if (!body.provider || !body.prompt) throw Object.assign(new Error("provider and prompt are required"), { statusCode: 400 });
  const destination = await locate(body.provider, body.targetNode);
  if (destination.local) return runner.submit(body);
  return fetchJson(`${destination.peer.url.replace(/\/$/, "")}/v1/jobs`, {
    token: destination.peer.token || config.sharedToken,
    method: "POST",
    body: { ...body, routedBy: config.nodeId },
    timeoutMs: 15_000,
  });
}

function recentEvents(since = 0) {
  if (!fs.existsSync(eventsFile)) return [];
  return fs.readFileSync(eventsFile, "utf8").trim().split("\n").filter(Boolean)
    .map((line) => JSON.parse(line)).filter((event) => event.id > since).slice(-100);
}

const startedAt = new Date().toISOString();
const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url, `http://${request.headers.host || "localhost"}`);
    if (url.pathname === "/health") return sendJson(response, 200, { ok: true, nodeId: config.nodeId });
    if (!authorized(request)) return sendJson(response, 401, { error: "unauthorized" });

    if (request.method === "GET" && url.pathname === "/v1/capabilities") return sendJson(response, 200, capabilities());
    if (request.method === "GET" && url.pathname === "/v1/fleet") {
      const nodes = [capabilities()];
      await Promise.all(config.peers.map(async (peer) => {
        try { nodes.push(await fetchJson(`${peer.url.replace(/\/$/, "")}/v1/capabilities`, { token: peer.token || config.sharedToken })); }
        catch { nodes.push({ nodeId: peer.nodeId || peer.url, offline: true, providers: [], roles: [] }); }
      }));
      return sendJson(response, 200, { nodes });
    }
    if (request.method === "GET" && url.pathname === "/v1/events") return sendJson(response, 200, { events: recentEvents(Number(url.searchParams.get("since") || 0)) });
    if (request.method === "GET" && url.pathname === "/v1/jobs") return sendJson(response, 200, { jobs: runner.list() });
    const jobMatch = url.pathname.match(/^\/v1\/jobs\/([^/]+)$/);
    if (request.method === "GET" && jobMatch) {
      const local = runner.get(jobMatch[1]);
      if (local) return sendJson(response, 200, local);
      for (const peer of config.peers) {
        try { return sendJson(response, 200, await fetchJson(`${peer.url.replace(/\/$/, "")}${url.pathname}`, { token: peer.token || config.sharedToken })); }
        catch {}
      }
      return sendJson(response, 404, { error: "job not found" });
    }
    if (request.method === "POST" && url.pathname === "/v1/route") return sendJson(response, 202, await routeJob(await readRequestJson(request)));
    if (request.method === "POST" && url.pathname === "/v1/jobs") return sendJson(response, 202, runner.submit(await readRequestJson(request)));
    if (request.method === "POST" && url.pathname === "/v1/notices") {
      const body = await readRequestJson(request);
      const notice = publish("notice", { id: body.id || shortId("notice") }, body.text, { actions: body.actions || [] });
      return sendJson(response, 202, notice);
    }
    if (request.method === "POST" && url.pathname === "/v1/deploy/postpone") {
      const body = await readRequestJson(request);
      const minutes = Math.max(1, Math.min(120, Number(body.minutes || 5)));
      state.postponements[body.release || "next"] = Date.now() + minutes * 60_000;
      writeJson(stateFile, state);
      const event = publish("deploy.postponed", { id: body.release || "next" }, `${body.requester || "A player"} postponed deployment ${minutes} minutes.`);
      return sendJson(response, 200, event);
    }
    const readyMatch = url.pathname.match(/^\/v1\/deploy\/ready\/([^/]+)$/);
    if (request.method === "GET" && readyMatch) return sendJson(response, 200, state.deployments[decodeURIComponent(readyMatch[1])] || { state: "unknown", nodeId: config.nodeId });
    if (request.method === "POST" && url.pathname === "/v1/deploy/ready") {
      const body = await readRequestJson(request);
      if (!body.release) return sendJson(response, 400, { error: "release is required" });
      state.deployments[body.release] = { nodeId: config.nodeId, state: body.state || "ready", roles: config.roles, at: new Date().toISOString() };
      writeJson(stateFile, state);
      publish("deploy.state", { id: body.release }, `${config.displayName || config.nodeId} is ${body.state || "ready"} for ${body.release}.`);
      return sendJson(response, 200, state.deployments[body.release]);
    }
    if (request.method === "POST" && url.pathname === "/v1/pair") {
      const body = await readRequestJson(request);
      const current = readJson(pairingFile);
      if (Date.now() > current.expiresAt || !safeEqual(String(body.code || ""), current.code)) return sendJson(response, 403, { error: "invalid or expired pairing code" });
      return sendJson(response, 200, { ok: true, player: body.player, expiresAt: new Date(Date.now() + 12 * 60 * 60 * 1000).toISOString() });
    }
    return sendJson(response, 404, { error: "not found" });
  } catch (error) {
    console.error(error);
    sendJson(response, error.statusCode || 500, { error: error.message });
  }
});

server.listen(config.listen.port, config.listen.host, () => {
  console.log(`[station] ${config.nodeId} listening on ${config.listen.host}:${config.listen.port}`);
  console.log(`[station] providers: ${config.providers.map((item) => item.name).join(", ") || "none"}; roles: ${config.roles.join(", ")}`);
  console.log(`[station] Minecraft pairing code: ${pairing.code} (12 hours)`);
  publish("station.started", { id: config.nodeId }, `${config.displayName || config.nodeId} online with ${config.providers.map((item) => item.name).join(" + ") || "no agents"}.`);
});

for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => server.close(() => process.exit(0)));
