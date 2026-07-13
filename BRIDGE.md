# Mod-Side Agent Bridge

An in-JVM HTTP API, shipped inside the `towerdefense` Fabric mod, that lets AI
agents **observe and act on a MODDED Minecraft server without the vanilla
protocol**. Custom blocks/entities (tower-defense towers, `acid`, future
content) that would make a Mineflayer / `minecraft-data` client throw
`PartialReadError` are irrelevant here — agents speak plain HTTP + JSON, so the
fragile vanilla parser is never in the loop.

- Code: `mods/towerdefense/src/main/java/net/bubblesky/towerdefense/bridge/`
- JS client: `grok/lib/bridge.js`
- Demo/test: `grok/bridge-demo.js`
- Deploy: `scripts/deploy-modded.sh` (modded server on `:25566`, bridge on `:25580`)

## Design

- `com.sun.net.httpserver.HttpServer` bound to `127.0.0.1` by default. Set
  `bindHost` or `BUBBLESKY_BRIDGE_HOST` only when you intentionally want LAN
  access.
- Started on `ServerLifecycleEvents.SERVER_STARTED`, stopped on `SERVER_STOPPING`.
- **Every** world read/mutation is marshalled onto the server thread via
  `server.submit(...)` and joined with a bounded (5s) timeout. The world is never
  touched from an HTTP worker thread.
- HTTP worker threads are daemon threads and the pool is shut down on stop, so
  the bridge never keeps the Minecraft JVM alive after shutdown.

## Auth

Every request must carry the shared token in the **`X-Bridge-Token`** header, or
it gets `401`. The token + host + port + enabled flag are resolved from (later
wins):

1. defaults (`enabled=true`, `bindHost=127.0.0.1`, `port=25580`)
2. `config/bubblesky-bridge.json` in the server run dir
3. env: `BUBBLESKY_BRIDGE_ENABLED`, `BUBBLESKY_BRIDGE_HOST`,
   `BUBBLESKY_BRIDGE_PORT`, `BUBBLESKY_BRIDGE_TOKEN`

If no token is supplied, one is generated and written to
`config/bubblesky-bridge.json` (and logged). If `enabled=false`, no socket opens.

`scripts/deploy-modded.sh` sets `BUBBLESKY_BRIDGE_TOKEN=bubblesky-dev-token` by
default for local dev and writes the matching config file.

## Endpoints

All responses are JSON and include `"ok": true|false`. Errors return an
appropriate status (`400` bad input / bad block, `401` bad token, `500`) with
`{"ok":false,"error":"…"}`. All world ops act on the **overworld**.

| Method | Path | Params / Body | Returns |
|--------|------|---------------|---------|
| GET  | `/health` | — | `{ok, mcVersion, modVersion, tps}` |
| GET  | `/block` | `?x&y&z` | `{ok, x, y, z, block}` |
| GET  | `/region` | `?x&y&z&rx&ry&rz` | `{ok, origin, size, palette[], cells[[x,y,z,paletteIdx]…], count}` |
| GET  | `/scan` | `?x&y&z&r` | `{ok, origin, radius, players[], entities[]}` |
| POST | `/setblock` | `{x,y,z,block}` | `{ok, changed, block}` |
| POST | `/fill` | `{x1,y1,z1,x2,y2,z2,block}` | `{ok, count, volume, block}` |
| POST | `/command` | `{command}` | `{ok, output}` |
| POST | `/batch` | `{ops:[…]}` | `{ok, results:[…]}` |
| GET  | `/chat` | `?since=<seq>` | `{ok, cursor, messages:[{seq,player,text,ts}]}` |
| POST | `/say` | `{name?, message}` | `{ok, seq, line}` |
| GET  | `/player` | `?name=<n>` | `{ok, online, name, pos, yaw, pitch, lookingAt:{x,y,z,block}\|null}` |
| GET  | `/players` | — | `{ok, players:[{name,pos,yaw,pitch}]}` |
| POST | `/status/agent` | `{name, activity, detail?, progress?}` | `{ok}` |
| GET  | `/status` | — | `{ok, agents:[{name,activity,detail,progress,ts}], players:[{name,pos}]}` |
| POST | `/test/chat` | `{player, text}` | `{ok, seq}` |

Chat / player / status (one-server unification — Grok runs ENTIRELY over the
bridge, so ONE modded server hosts mods + agents):

- **`/chat`** — player chat is captured via `ServerMessageEvents.CHAT_MESSAGE`
  into a bounded ring buffer with a monotonic `seq`; the bridge's own `/say`
  lines are recorded too (so agents see themselves). Poll `?since=<cursor>` for
  new lines; start from the current `cursor` to skip backlog.
- **`/say`** broadcasts `<name> message` to server chat (Grok speaking).
- **`/player`** does a server-side raycast (~64 blocks) from the player's eyes
  for `lookingAt` — this is how "here" / look-targeting resolves over the bridge.
  Returns `404` if the named player is offline.
- **`/status/agent`** + **`/status`** are the small agent-HUD plumbing: agents
  report `{activity, detail, progress}` (e.g. idle / building "<name>"), a HUD
  reads them back alongside online players.
- **`/test/chat`** injects a synthetic player chat into the buffer (token-gated)
  for headless verification of the chat→build→reply loop with no client.

Notes:

- **`block`** strings use full command syntax incl. state, e.g.
  `stone_bricks`, `oak_stairs[facing=east,half=bottom]`,
  `towerdefense:acid` (modded). Parsed via the game's `BlockArgumentParser`, so
  anything `/setblock` accepts works. Reads return the canonical
  `namespace:name[state]` form.
- **`/region`** returns only **non-air** cells, de-duplicated into a `palette`.
  Bounded: each radius ≤ 48, total ≤ 32,768 cells.
- **`/fill`** counts cells actually **changed** (an idempotent re-fill returns
  `count:0` while `volume` stays the box size). Bounded ≤ 262,144 cells.
- **`/command`** runs at **op level 4** with output captured (the
  `executeWithPrefix` path) — e.g. `time set day`, `weather clear`, `tp`, `give`.
- **`/batch`** applies an ordered list in **one server-thread hop** (efficient
  builds). Each op is `{op:"setblock",x,y,z,block}`,
  `{op:"fill",x1,y1,z1,x2,y2,z2,block}`, or `{op:"command",command}`. Per-op
  results carry their own `ok`/`error`; one bad op doesn't abort the batch.

### Examples

```bash
T=bubblesky-dev-token; B=http://127.0.0.1:25580
curl -s -H "X-Bridge-Token: $T" "$B/health"
curl -s -H "X-Bridge-Token: $T" -X POST "$B/setblock" \
  -d '{"x":300,"y":100,"z":300,"block":"towerdefense:acid"}'
curl -s -H "X-Bridge-Token: $T" "$B/block?x=300&y=100&z=300"
curl -s -H "X-Bridge-Token: $T" -X POST "$B/batch" \
  -d '{"ops":[{"op":"fill","x1":0,"y1":100,"z1":0,"x2":4,"y2":100,"z2":4,"block":"stone"},
              {"op":"setblock","x":2,"y":101,"z":2,"block":"lantern"},
              {"op":"command","command":"time set day"}]}'
```

## JS client

`grok/lib/bridge.js` (`makeBridge()`) mirrors the godmode `hands` surface —
`getBlock, readRegion, scan, setBlock, fillBox, command, batch` (+ `place`,
`dig`, `clearArea`, `setTime`, `setWeather`, `health`) — over HTTP, zero deps.
Base URL from `BUBBLESKY_BRIDGE_URL` (default `http://127.0.0.1:25580`), token
from `BUBBLESKY_BRIDGE_TOKEN`.

`grok/bridge-demo.js` drives the real `structures.js` `round_tower` generator
through the bridge (batched), places a modded `towerdefense:acid` block, and
asserts reads match writes — proving Grok's building code runs on modded content
with no Mineflayer.

## Running

```bash
JAVA_HOME=~/.jdks/jdk-21.0.11+10/Contents/Home bash scripts/deploy-modded.sh
BUBBLESKY_BRIDGE_TOKEN=bubblesky-dev-token node grok/bridge-demo.js
```

### Run Grok ENTIRELY over the bridge (no Mineflayer)

Grok picks its transport from `GROK_TRANSPORT` (default `mineflayer`, the vanilla
protocol — unchanged). `GROK_TRANSPORT=bridge` runs Grok with NO Mineflayer at
all: chat in via `GET /chat` polling, chat out via `POST /say`, "here" via
`GET /player` `lookingAt`, hands via batched `POST /batch`, and status via
`POST /status/agent`. The transport-specific surface lives behind
`grok/lib/transport/{mineflayer,bridge}.js`; the router, Fable Architect, skills,
and the `lib/build` pipeline are shared and unchanged. So one MODDED server can
host the mods AND Grok:

```bash
cd grok && GROK_TRANSPORT=bridge \
  BUBBLESKY_BRIDGE_TOKEN=bubblesky-dev-token \
  BUBBLESKY_BRIDGE_URL=http://127.0.0.1:25580 node assistant.js
```

Follow / pathfinding are unavailable over the bridge (Grok has no walking body);
godmode/architect building, flags, saved builds, edits, and clarifying questions
all work (they only need hands + look + chat).

### Run Codex ENTIRELY over the bridge (no Mineflayer)

`mcp/codex-bridge-godmode.mjs` is the Codex equivalent of the disembodied Grok
path: it polls bridge chat, responds to `@codex`, and translates natural
language requests into bounded level-4 operator commands over `POST /command`.
It handles flexible godmode asks such as gear, healing, effects, teleporting,
time/weather, spawning, and small world edits, while refusing server admin
commands such as `op`, `ban`, `kick`, `stop`, `reload`, and whitelist changes.

Run it on the Minecraft server host when the bridge stays localhost-only:

```bash
scripts/run-codex-bridge.sh
```

Or, if you intentionally expose the bridge on the LAN by setting
`"bindHost": "0.0.0.0"` in `config/bubblesky-bridge.json` or exporting
`BUBBLESKY_BRIDGE_HOST=0.0.0.0` before server start, run it remotely with:

```bash
BUBBLESKY_BRIDGE_URL=http://<server-ip>:25580 \
BUBBLESKY_BRIDGE_TOKEN=<server/config token> \
  scripts/run-codex-bridge.sh
```

The modded server (`server-modded/`, `:25566`) has its own mods/world/config but
symlinks the heavy read-only launch infra from `server/`. The vanilla server
(`:25565`) and its bots are untouched — the bridge only runs in the modded JVM.

## Done since the original bridge

- **Bridge hands backend + build pipeline routing** — the `bridge` transport
  (`grok/lib/transport/bridge.js`) supplies a batching, sync-return `hands`
  (mirrors godmode; drains via `POST /batch`), and `assistant.js` runs the whole
  build pipeline through it when `GROK_TRANSPORT=bridge`. Proven end-to-end on the
  modded server: an injected `claudebert` chat → `build_structure` / `plan_build`
  (Fable Architect authored a "Grand Medieval Fortress", 3500 ops) → blocks placed
  via the bridge → reply via `/say` → status via `/status`, plus a modded
  `towerdefense:acid` placement and a flag command — all with no Mineflayer.

## Follow-up (intentionally NOT done here)

1. **Carpet fake-player embodiment (Phase 2)** — Carpet is intentionally **not**
   installed by `deploy-modded.sh`; the godmode bridge doesn't need it for this
   phase. Adds `POST /agent/spawn`, `/agent/{name}/move|look|place|break`,
   `DELETE /agent/{name}` for an embodied agent.
2. **Carpet fake-player embodiment for the swarm** — the bridge drones below
   place blocks in command-mode (godmode `/setblock`+`/fill`). Physical/manual
   (survival) placement, mob aggro, and block-break physics would need Carpet
   fake players; that's a later follow-up, same as item 1 above.

## Bridge for the swarm (Codex's lane)

The swarm can run on a MODDED server WITHOUT Mineflayer by swapping the
Mineflayer workers for **bridge drones** that place blocks over this HTTP API.
Nothing about the boss/planner changes — it still compiles schematics into the
shared `state.json`; only the *worker* transport changes.

Two NEW files (drop-ins; the existing `codex-*.mjs` runner is untouched):

- **`mcp/bridge.mjs`** — ESM twin of `grok/lib/bridge.js` (zero deps, global
  `fetch`). `makeBridge()` returns `health, getBlock, region, scan, setBlock,
  fill, command, batch, chat, say, player, players, testChat, postStatus, getStatus`.
  Base URL + token from env (below).
- **`mcp/bridge-drone.mjs`** — a runnable worker that mirrors
  `codex-swarm-worker.mjs`'s coordination exactly, but builds over the bridge:
  reads the same `.codex-runtime/swarm/state.json`, works only jobs assigned to
  it (`job.worker === name`) or left unassigned, and places them via one
  `POST /batch` per cycle (contiguous same-block runs coalesced into `/fill`,
  else `/setblock`). It reports to `POST /status/agent` and tracks completion in
  `progress/<name>.json` (`doneIds/failedIds/claimedIds`) just like the worker.
  ADDED over the worker: the read-select-mark claim cycle is guarded by an
  atomic `O_EXCL` lockfile (`bridge-drone-claim.lock`) and records `claimedIds`,
  so bodiless drones split a shared phase with zero collisions (the walking
  worker got that serialization for free). CLI:
  `node mcp/bridge-drone.mjs --name Drone1 --state <path>`; API:
  `import { runBridgeDrone } from "./bridge-drone.mjs"`.

### Moving the swarm to the bridge

1. Boss/planner unchanged — `codex-swarm-planner.mjs` (or `blueprint-compiler.mjs`)
   still writes `.codex-runtime/swarm/state.json`.
2. Instead of N `codex-swarm-worker.mjs` (Mineflayer) processes, launch N
   `bridge-drone.mjs` processes — one per drone name (`Drone1`, `Drone2`, …):

   ```bash
   BUBBLESKY_BRIDGE_TOKEN=bubblesky-dev-token \
   BUBBLESKY_BRIDGE_URL=http://127.0.0.1:25580 \
     node mcp/bridge-drone.mjs --name Drone1 --state .codex-runtime/swarm/state.json &
   BUBBLESKY_BRIDGE_TOKEN=bubblesky-dev-token \
   BUBBLESKY_BRIDGE_URL=http://127.0.0.1:25580 \
     node mcp/bridge-drone.mjs --name Drone2 --state .codex-runtime/swarm/state.json &
   ```

   Drones consume the same state file over the bridge, atomically claim their
   partitions, place blocks, and exit when the task is fully done.

Env vars: `BUBBLESKY_BRIDGE_URL` (default `http://127.0.0.1:25580`),
`BUBBLESKY_BRIDGE_TOKEN`. Drone flags: `--batch-size` (jobs claimed per cycle,
default 64), `--poll-ms`, `--idle-exit-ms`, `--once`.

**Caveat — placement is command-mode (godmode).** Drones build via `/setblock`
and `/fill`, so blocks appear directly (no inventory, no survival physics), which
is exactly what a modded server needs when Mineflayer can't connect. Physical /
manual placement, mob interaction, and break physics would require Carpet fake
players — a later follow-up (see item 2 above), not needed for godmode swarm
builds.

Proven end-to-end on the modded server (`:25566`, bridge `:25581`): two
`bridge-drone.mjs` instances built a `bridge-test-wall` from a shared
`state.json` — Drone1 placed 8 jobs, Drone2 placed 11 (incl. a modded
`towerdefense:acid` block that reads back as `towerdefense:acid[charge=15]`),
19/19 jobs done, **zero collisions**, and `/status` showed both drones with
their progress.
