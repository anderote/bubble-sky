# One-Server Unification (mods + agents) — Design

Date: 2026-07-12
Status: Approved ("yeah build that")

## Goal
One modded server that hosts custom mods (acid, tower-defense) AND lets Grok/agents play —
by having Grok operate entirely over the mod-side HTTP bridge instead of the vanilla
Mineflayer protocol (which can't log in to a modded server).

## Gap today
The bridge does world action + observation (`/setblock /fill /command /region /scan`) but
NOT chat I/O or look-targeting, and `grok/assistant.js` still uses Mineflayer for chat in/out,
"here" look-target, movement, and its hands backend. So Grok can't run on a modded server yet.

## Build

### A. Bridge chat + player endpoints (mod-side, `mods/towerdefense/.../bridge`)
- Capture player chat via `ServerMessageEvents.CHAT_MESSAGE` into a ring buffer with a
  monotonic `seq`. Also capture the bridge's own `/say` outputs so agents see their own lines.
- `GET /chat?since=<seq>` → `{cursor, messages:[{seq,player,text,ts}]}` (new since seq).
- `POST /say {name?, message}` → broadcast to server chat as `<name> message` (Grok speaks).
- `GET /player?name=<n>` → `{pos, yaw, pitch, lookingAt:{x,y,z,block}|null}` (server-side
  raycast ~64 blocks) for "here"/look-targeting. `GET /players` → names+pos.
- Token auth as today. A test-only `POST /test/chat {player,text}` to inject a chat for
  headless verification (gated behind the token; documented).

### B. Grok bridge transport (`grok/`)
- `GROK_TRANSPORT=mineflayer|bridge` (default `mineflayer` — unchanged). Abstract the
  transport-specific bits (chat in/out, look-target, position, hands, move) behind a small
  interface with two impls; the router, architect, skills, and `lib/build` pipeline stay shared.
- Bridge impl: poll `GET /chat?since` (~1s) → filter allowlist (`claudebert`) → same
  router/dispatch; reply via `POST /say {name:'Grok'}`; "here" via `GET /player` lookingAt;
  hands = `grok/lib/bridge.js` (setblock/fill/command); `move/tp` via `/command tp`.
  Follow/pathfinding is unavailable over the bridge (note it) — godmode/architect building,
  flags, saved builds, clarifying questions all work (they only need hands + look + chat).
- Preserve quiet mode, single build start/finish lines, `claudebert`-only allowlist, Fable
  Architect, and the entire build pipeline. Mineflayer mode must remain fully working.

### C. Prove it (do NOT flip the main :25565 yet)
- Deploy mods+bridge to a modded server (`deploy-modded.sh`, :25566, bridge :25580) and run
  Grok with `GROK_TRANSPORT=bridge`. Prove the full loop end-to-end: an injected `claudebert`
  chat ("build a small tower here" / "build a stone hut") → Grok reads it via `/chat` → plans
  + builds via the bridge (with mods ACTIVE — verify it can also place `towerdefense:acid`) →
  replies via `/say`. Verify look-targeting and a saved-build/flag command too.
- Report honestly what could be verified headlessly (chat injection) vs. needs a real client.

## Out of scope / follow-up
- Flipping the main :25565 to modded (breaks Codex's Mineflayer swarm → they adopt the bridge;
  coordinate via issue #9). Done deliberately after this is proven, with the user.
- Builder-fleet manual placement needs vanilla-protocol bots, so on a modded server the fleet
  is a follow-up (godmode/architect building over the bridge is the path there).
- Carpet fake-player embodiment (later).
