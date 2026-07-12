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

- `com.sun.net.httpserver.HttpServer` **bound to `127.0.0.1`** (localhost only).
- Started on `ServerLifecycleEvents.SERVER_STARTED`, stopped on `SERVER_STOPPING`.
- **Every** world read/mutation is marshalled onto the server thread via
  `server.submit(...)` and joined with a bounded (5s) timeout. The world is never
  touched from an HTTP worker thread.
- HTTP worker threads are daemon threads and the pool is shut down on stop, so
  the bridge never keeps the Minecraft JVM alive after shutdown.

## Auth

Every request must carry the shared token in the **`X-Bridge-Token`** header, or
it gets `401`. The token + port + enabled flag are resolved from (later wins):

1. defaults (`enabled=true`, `port=25580`)
2. `config/bubblesky-bridge.json` in the server run dir
3. env: `BUBBLESKY_BRIDGE_ENABLED`, `BUBBLESKY_BRIDGE_PORT`, `BUBBLESKY_BRIDGE_TOKEN`

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

The modded server (`server-modded/`, `:25566`) has its own mods/world/config but
symlinks the heavy read-only launch infra from `server/`. The vanilla server
(`:25565`) and its bots are untouched — the bridge only runs in the modded JVM.

## Follow-up (intentionally NOT done here)

These touch files another task is concurrently editing, so they're deferred to a
clean follow-up:

1. **`bridge-godmode` hands backend** — add a third backend to `grok/lib/hands.js`
   that delegates to `grok/lib/bridge.js`, selected by env (e.g.
   `HANDS_BACKEND=bridge`). Same method shapes, so it's ~a one-block addition.
2. **Route the build pipeline** — point the build-representation realizer /
   `assistant.js` at the bridge backend so Grok builds land on the modded server.
   Do this after the build-representation module merges.
3. **Carpet fake-player embodiment (Phase 2)** — Carpet is intentionally **not**
   installed by `deploy-modded.sh`; the godmode bridge doesn't need it for this
   phase. Adds `POST /agent/spawn`, `/agent/{name}/move|look|place|break`,
   `DELETE /agent/{name}` for an embodied agent.
4. **Swarm adoption (Codex's lane)** — the swarm/MCP bots can drive modded builds
   and get server-confirmed verification via these endpoints; their runner is not
   modified here.
