# Modded Agent System — Mod-Side Bridge — Design

Date: 2026-07-12
Status: Approved ("whatever you think is best" → recommended path on all forks)

## Problem

Custom mod content (tower-defense, acid, future) makes the server require the mod
client-side. Mineflayer agents (Grok, Codex swarm) speak the vanilla protocol with
vanilla-only `minecraft-data`, so custom registry entries during login cause
`PartialReadError` and the bots can't play. Modded content and agents-in-world are
currently mutually exclusive. We end that.

## Approach (chosen)

Agents stop depending on the vanilla protocol for the modded world. The **mod exposes an
in-JVM HTTP API** for observation + action; agents talk HTTP, so custom content never
touches a fragile parser. Rollout on a **separate modded server (:25566)** so the vanilla
:25565 keeps working during transition. **Godmode HTTP API first**, Carpet fake-player
embodiment as a follow-up. Bridge lives in the existing `towerdefense` mod for now.

This also gives Codex the *"server-side verification channel"* their blueprint handoff
explicitly asked for — shared infra, handed over via the PR.

## Components

### A. Mod-side bridge — `net.bubblesky.towerdefense.bridge`

- `AgentBridge`: on `ServerLifecycleEvents.SERVER_STARTED`, start a
  `com.sun.net.httpserver.HttpServer` **bound to 127.0.0.1** on a config port (default
  25580); stop on `SERVER_STOPPING`. JSON via GSON.
- **Auth**: a shared token (config file `config/bubblesky-bridge.json` or
  `BUBBLESKY_BRIDGE_TOKEN` env) required in an `X-Bridge-Token` header. Localhost-bound +
  token so only our agents can drive it.
- **Thread safety**: every world read/mutation runs on the server thread via
  `server.submit(() -> ...)` and the handler joins the returned `CompletableFuture`
  (bounded timeout). Never touch the world from the HTTP thread.
- **Endpoints (typed, JSON)**:
  - `GET /health` → `{ok, mcVersion, modVersion, tps}`
  - `GET /block?x&y&z` → `{block:"name[state]"}`
  - `GET /region?x&y&z&rx&ry&rz` → palette + non-air cells in the box (bounded size)
  - `GET /scan?x&y&z&r` → nearby players + entities (name, type, pos)
  - `POST /setblock` `{x,y,z,block}` → places (returns changed bool)
  - `POST /fill` `{x1,y1,z1,x2,y2,z2,block}` → chunked internally; returns count
  - `POST /command` `{command}` → runs at permission level 4 via a server command source
    with output captured; returns `{output}`. (This is the executeWithPrefix path.)
  - `POST /batch` `{ops:[...]}` → ordered setblock/fill/command batch (one server-thread
    hop) for efficient builds.
  - **Phase 2 (Carpet embodiment):** `POST /agent/spawn {name}`,
    `POST /agent/{name}/move|look|place|break`, `DELETE /agent/{name}`.
- `executeWithPrefix(cmd)` helper: build a command `ServerCommandSource` at op level with a
  capturing output consumer.
- Config gate: `enabled` (default true), `port`, `token`. If disabled, no server started.

### B. Modded server instance

- `server-modded/` (or a scripted dir): fabric-loader + `fabric-api-0.128.2` + Carpet
  (1.4.176, MIT) + the built `towerdefense` jar; port **25566**; own world/eula; a console
  FIFO like the main server. Bridge listens on 25580.
- `scripts/deploy-modded.sh`: build the mod (`gradlew build`), copy the jar into
  `server-modded/mods/`, (re)start the modded server. Idempotent.
- The main vanilla server (:25565) is untouched — bots keep working there during
  transition.

### C. Bridge client — `grok/lib/bridge.js` (new; does NOT edit existing grok files yet)

- A JS client mirroring the `hands` surface: `getBlock`, `readRegion`, `scan`, `setBlock`,
  `fillBox`, `command`, `batch` — over HTTP to the bridge, token from
  `BUBBLESKY_BRIDGE_TOKEN`. Same method shapes as `lib/hands.js` godmode backend so it can
  later become a `bridge-godmode` hands backend with a one-line swap.
- A demo `grok/bridge-demo.js`: build one of `structures.js`'s generators through the
  bridge on the modded server, proving Grok's building code runs on modded content with
  no Mineflayer. (Full hands-backend integration + build-pipeline routing is wired in a
  follow-up, after the build-representation work lands, to avoid editing `lib/hands.js`
  concurrently.)

## Data flow

Agent (Grok/swarm) → HTTP → AgentBridge → `server.submit` (server thread) → world
read/mutate → JSON response. No vanilla-protocol client needed → any custom block/entity
is fine. The build-representation realizer can target this bridge backend exactly as it
targets mineflayer-godmode.

## Testing

- Build the mod (`gradlew build`) — compiles.
- Launch the modded server (:25566) headless with the bridge; `curl` `/health`,
  `/setblock`, `/fill`, `/command`, `/block` readback, `/region`, `/scan` — assert blocks
  land and reads reflect them. Prove a small `structures.js` build via `bridge-demo.js`.
- Confirm the main vanilla server and its bots are untouched (bridge only runs in the
  modded JVM).

## Non-goals (this phase)

- Carpet fake-player embodiment (Phase 2 endpoints stubbed/designed, not required to
  unblock building on modded content).
- Editing `lib/hands.js`/`assistant.js` to route Grok through the bridge (follow-up, after
  build-representation merges — one clean hands-backend swap).
- Migrating the swarm/mcp bots to the bridge (Codex's lane; endpoints documented for them).

## Coordination with Codex

Bridge endpoints are shared infra. Document them in the PR + a `BRIDGE.md`, so Codex's
swarm can drive modded builds and get server-confirmed verification (the gap their handoff
named). We do not modify their runner.
