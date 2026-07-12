# Modded Agent Bridge — Implementation Plan

Spec: `docs/superpowers/specs/2026-07-12-modded-agent-bridge-design.md`
Branch: `grok`. Ship in the big PR (or a sibling PR). Do NOT edit existing `grok/` files
concurrently with the build-representation work — only ADD `grok/lib/bridge.js` +
`grok/bridge-demo.js`. Mod-side is all in `mods/towerdefense/` (no conflict).

## Steps

1. **Bridge core (Java)** — `mods/towerdefense/src/main/java/net/bubblesky/towerdefense/bridge/`:
   - `AgentBridge.java`: HttpServer on 127.0.0.1:port; start on SERVER_STARTED, stop on
     SERVER_STOPPING; token auth (`X-Bridge-Token`); GSON; a `runOnServer(Supplier)` helper
     using `server.submit(...)` + bounded `.get()`.
   - `BridgeHandlers.java`: the endpoints (`/health /block /region /scan /setblock /fill
     /command /batch`). `/command` uses an op-level `ServerCommandSource` with captured
     output. Validate + bound region/fill sizes.
   - `BridgeConfig.java`: read `config/bubblesky-bridge.json` / env (enabled, port, token).
   - Hook init from the mod entrypoint (register the lifecycle listeners). Add GSON dep if
     not already present; `com.sun.net.httpserver` is in the JDK.
2. **Build** — `JAVA_HOME=~/.jdks/jdk-21.0.11+10/Contents/Home timeout 420 ./gradlew build
   --console=plain` (non-blocking, capture output, fix errors).
3. **Modded server + deploy** — `scripts/deploy-modded.sh`: create/populate
   `server-modded/` (fabric-loader install or reuse launch infra), drop in fabric-api +
   Carpet + the towerdefense jar, `eula=true`, port 25566, console FIFO; build+copy+restart
   idempotently. Launch it headless (non-blocking) for testing on 25566, bridge on 25580.
   Do NOT touch the main `server/` dir.
4. **Bridge client** — `grok/lib/bridge.js`: HTTP client mirroring the `hands` godmode
   surface (`getBlock,readRegion,scan,setBlock,fillBox,command,batch`), token from
   `BUBBLESKY_BRIDGE_TOKEN`. New file only.
5. **Demo + test** — `grok/bridge-demo.js`: build a `structures.js` generator (e.g. a small
   tower) through the bridge on :25566. Test via `curl`: `/health`, place + readback a
   block, `/fill` a small box + count, `/command time set day`, `/region` reflects
   placements, `/scan`. Assert reads match writes. Capture the transcript.
6. **Docs** — `BRIDGE.md` (endpoint reference for Codex) + note the follow-up wiring
   (hands `bridge-godmode` backend + routing Grok's build pipeline to the bridge) to be
   done after the build-representation module merges.

## Verify / isolation
- Main vanilla `server/` (:25565) and its bots untouched (bridge only runs in the modded
  JVM on :25566).
- `node --check` the new JS; `gradlew build` green; bridge endpoints proven via curl +
  the structures.js demo.
- No commit (main thread assembles the PR).

## Follow-up (not this task)
- Carpet fake-player embodiment endpoints.
- `lib/hands.js` `bridge-godmode` backend + route `build/index.js` realizer + assistant to
  the bridge (one clean swap, after build-representation lands).
- Swarm adoption (Codex's lane).
