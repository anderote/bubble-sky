// mcp/bridge.mjs — ESM HTTP client for the mod-side AgentBridge.
//
// The `towerdefense` Fabric mod exposes an in-JVM HTTP API (see BRIDGE.md and
// mods/.../bridge/) so agents can observe + act on a MODDED server WITHOUT the
// vanilla protocol. Custom blocks/entities (towers, `towerdefense:acid`, …) that
// would make a Mineflayer / minecraft-data client throw `PartialReadError` never
// touch a fragile vanilla parser here — agents speak plain HTTP + JSON.
//
// This is the ESM twin of grok/lib/bridge.js (which is CommonJS). The mcp/ tools
// are ESM, so Codex's swarm can `import { makeBridge } from "./bridge.mjs"`.
// Zero external dependencies — uses Node's built-in global `fetch` (Node >= 18).
//
// Config from env:
//   BUBBLESKY_BRIDGE_URL    base URL   (default http://127.0.0.1:25580)
//   BUBBLESKY_BRIDGE_TOKEN  shared token sent as the X-Bridge-Token header

// Keep block-state syntax intact (e.g. oak_stairs[facing=east]); only strip
// whitespace/quotes and lowercase. Mirrors grok's hands.cleanBlock so the same
// block strings (incl. modded namespaces) round-trip identically.
export function cleanBlock(s, def) {
  const x = String(s == null ? "" : s)
    .trim()
    .toLowerCase()
    .replace(/^minecraft:/, "")
    .replace(/[^a-z0-9_:\[\]=,]/g, "");
  return x || def || "stone";
}

export function makeBridge(opts = {}) {
  const base = (opts.baseUrl || process.env.BUBBLESKY_BRIDGE_URL || "http://127.0.0.1:25580").replace(/\/$/, "");
  const token = opts.token || process.env.BUBBLESKY_BRIDGE_TOKEN || "";
  const timeoutMs = opts.timeoutMs || 8000;
  const r = (n) => Math.round(n);

  async function request(method, path, body) {
    const url = base + path;
    const payload = body == null ? undefined : JSON.stringify(body);
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    let res;
    try {
      res = await fetch(url, {
        method,
        headers: {
          "X-Bridge-Token": token,
          ...(payload ? { "Content-Type": "application/json" } : {}),
        },
        body: payload,
        signal: ctrl.signal,
      });
    } catch (e) {
      throw new Error(`bridge ${method} ${path} failed: ${e.message}`);
    } finally {
      clearTimeout(timer);
    }
    const text = await res.text();
    let json;
    try {
      json = text ? JSON.parse(text) : {};
    } catch {
      throw new Error(`bad JSON from ${path}: ${text.slice(0, 200)}`);
    }
    if (res.status >= 400 || json.ok === false) {
      throw new Error(`bridge ${method} ${path} ${res.status}: ${json.error || text}`);
    }
    return json;
  }

  const qs = (o) =>
    "?" +
    Object.entries(o)
      .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
      .join("&");

  return {
    name: "bridge",
    baseUrl: base,
    cleanBlock,

    // ---- observation ----
    health() {
      return request("GET", "/health");
    },
    getBlock(x, y, z) {
      return request("GET", "/block" + qs({ x: r(x), y: r(y), z: r(z) }));
    },
    region(x, y, z, rx = 4, ry = 4, rz = 4) {
      return request("GET", "/region" + qs({ x: r(x), y: r(y), z: r(z), rx: r(rx), ry: r(ry), rz: r(rz) }));
    },
    scan(x, y, z, radius = 16) {
      return request("GET", "/scan" + qs({ x: r(x), y: r(y), z: r(z), r: r(radius) }));
    },

    // ---- action (mirrors the godmode hands primitives) ----
    setBlock(x, y, z, block) {
      return request("POST", "/setblock", { x: r(x), y: r(y), z: r(z), block: cleanBlock(block, "stone") });
    },
    fill(x1, y1, z1, x2, y2, z2, block) {
      return request("POST", "/fill", {
        x1: r(x1), y1: r(y1), z1: r(z1),
        x2: r(x2), y2: r(y2), z2: r(z2),
        block: cleanBlock(block, "stone"),
      });
    },
    command(cmd) {
      return request("POST", "/command", { command: String(cmd).replace(/^\//, "") });
    },

    // ---- batched build (one server-thread hop) ----
    // ops: [{op:'setblock',x,y,z,block}, {op:'fill',x1..z2,block}, {op:'command',command}]
    batch(ops) {
      return request("POST", "/batch", { ops });
    },

    // ---- chat + players + status ----
    chat(since = 0) {
      return request("GET", "/chat" + qs({ since: since | 0 }));
    },
    say(name, message) {
      return request("POST", "/say", { name: name || "", message: String(message) });
    },
    player(name) {
      return request("GET", "/player" + qs({ name }));
    },
    players() {
      return request("GET", "/players");
    },
    testChat(player, text) {
      return request("POST", "/test/chat", { player, text: String(text) });
    },
    postStatus(name, activity, detail, progress) {
      const update = { name, activity };
      if (detail != null) update.detail = detail;
      if (progress != null) update.progress = progress;
      return request("POST", "/status/agent", update);
    },
    getStatus() {
      return request("GET", "/status");
    },
  };
}

export default makeBridge;
