# bubble-sky

A **private, moddable Minecraft server** that doubles as an **AI-agent playground**.
One Fabric **1.21.6** world hosts custom mods (a tower-defense game, custom blocks)
*and* several kinds of AI agents that observe, chat, and build in it.

> **Work in progress / personal project.** Nothing here is public-facing or supported;
> expect rough edges and in-flight designs.

Two collaborators build in parallel lanes on the same repo:

| Who | Agent | Owns | What they build |
|-----|-------|------|-----------------|
| **Andrew** | Claude Code | `grok/` + `mods/` | Grok (the AI builder), the mod-side bridge, the Tower Defense mod, the builder crew |
| **Alli** | Codex | `mcp/` | the multi-agent build **swarm** + bridge drones |

Coordination between the two lanes happens on **issue #9** and in the design docs
under [`docs/superpowers/`](docs/superpowers/).

---

## What's in the box

- **Grok** — a natural-language AI *builder* you talk to in chat.
- **Agent bridge** — an in-JVM HTTP API so agents can act on a *modded* server (custom blocks) without the vanilla protocol. See [`BRIDGE.md`](BRIDGE.md).
- **Builder crew** — LLM-brained, non-godmode player-bots that each reason and build by hand.
- **Tower Defense** — a full in-game game mod (`mods/towerdefense/`): waves, towers, coins, boss waves, a `J` menu.
- **Codex swarm** — Codex's multi-agent build swarm (`mcp/`).
- **Asset mods** — Macaw's + Blockus + Farmer's Delight for richer building material.

---

## Grok — the AI builder

`grok/assistant.js` is our single, personal, in-world assistant. You talk to it in plain
English and it interprets your intent with an LLM and acts. By default it **only responds
to `claudebert`** (an allowlist) and stays otherwise quiet.

**Two transports**, chosen by `GROK_TRANSPORT`:
- `mineflayer` (default) — a real player over the **vanilla protocol**.
- `bridge` — runs *entirely* over the mod-side HTTP bridge, so it can play on a **modded**
  server the vanilla protocol can't log into. Chat in via `GET /chat`, chat out via
  `POST /say`, "here" via a server-side raycast, hands via batched `POST /batch`.

The router runs on the xAI Grok model by default; the **Architect** runs on Claude
(Anthropic) / a Fable model when a key is present. Both are pluggable per role (`lib/llm.js`).

### Agent-native build system (`grok/lib/build/`)
A semantic **blueprint → additive, site-aware compiler**. The Architect expands a terse
request into a rich spec (style, palette, room list + furniture, detailing) and emits a
phased plan; that flows through `author → anchor(fit terrain) → compile → diff(live world)
→ realize`, so builds are **additive** (surroundings preserved), **terrain-fit**, furnished,
and lit. Varied **archetypes** with seeded variation: `cottage · house/manor · keep · castle
· tower/wizard_tower · cathedral/hall · fort`.

### Terrain / earthworks (`grok/lib/terrain.js`)
Carve the world: `tunnel`, `trench`, `pit`, `hollow`, `moat` — all through the same throttled
hands, honoring godmode and the **stop** fast-path.

### Named flags + regions (`grok/lib/flags.js`)
Plant named markers (`A1`, `B2`) and rectangular **regions**, then reference them in
commands: *"build in region 1"*, *"build a wall between A1 and A2"*, `connect_flags`,
*"build X at flag A2"*. When the bridge is reachable, flags/regions live **server-side** —
the in-game **Layout Wand** / **Flag Bow** write to the same store, so wand-planted and
chat-planted markers are one system. Visible in-world markers are placed for each flag.

### Other command surface
Save / list / reuse / edit / delete **saved builds** (`grok/lib/build/library.js`),
**clarifying questions** on vague requests, **web research** for reference ideas
(`grok/lib/research.js`), a hard **stop** command, and quiet mode.

Run it (short form — see [`grok/README.md`](grok/README.md)):

```sh
cd grok && npm install && cp .env.example .env   # add your API key(s)
# server up + bot opped (echo "op Grok" > ../server/console.in), then:
node assistant.js
```

---

## The mod-side agent bridge

An in-JVM HTTP API shipped inside the `towerdefense` mod that lets agents **observe and act
on a MODDED server without the vanilla protocol**. Custom blocks/entities that would make a
Mineflayer / `minecraft-data` client throw are irrelevant here — agents speak plain HTTP +
JSON. This is what makes **"one server for mods + agents"** possible.

- Bound to `127.0.0.1` (localhost only), token-gated (`X-Bridge-Token`).
- World reads/mutations marshalled onto the server thread; started/stopped with the server.
- Endpoints: `/health /block /region /scan /setblock /fill /command /batch /chat /say
  /player /players /status`.
- JS clients: `grok/lib/bridge.js` (Grok) and `mcp/bridge.mjs` (the swarm).

Full detail, endpoints, and examples: [`BRIDGE.md`](BRIDGE.md).

---

## LLM-embodied builder crew (`grok/agents/` + `grok/builders/`)

Where Grok builds in *godmode* (instant `/fill`), the crew is a fleet of **non-godmode
player-bots** that place every block **by hand** (`bot.placeBlock`, bottom-up, support-aware).

- `builders/builder.js` / `builders/fleet.js` — "dumb" builders that claim a partition of the
  shared job state and physically place blocks.
- `agents/agent.js` — each bot has a **brain**: it observes its plot, makes its *own* LLM call
  to decide what to build, authors a blueprint via the Architect, claims its jobs, and builds them.
- `agents/crew.js` — lays out a ring of plots around a central plaza and runs one agent per plot
  concurrently, coordinating **entirely** through the shared `state.json` (Codex's schema, reused).

Currently vanilla-server (Mineflayer) bots; manual placement on a modded server via Carpet
fake-players is a planned follow-up.

---

## Tower Defense game (`mods/towerdefense/`)

A human-facing custom-content Fabric mod — the game that lives in the world.

- **Gameplay:** set an arena/base (`/td arena`), start **waves** (`/td wave`), defend the base.
- **Towers:** buildable **Arrow / Cannon / Frost** towers that auto-target enemies; buy /
  upgrade from a **shop** priced in **coins** (dropped on kill). Hire **ally** units (footman /
  archer) too.
- **Enemies:** a roster of marching foes (footmen, melee, archers) with a **boss** every 5th wave.
- **UI:** an in-game **HUD** (coins / wave / enemies) and a menu bound to **`J`**; also driven by
  `/td` (`arena`, `wave`, `buy`, `upgrade`, `hire`, `shop`, `status`, `restart`, …).
- **Extras shipped in the same mod:** the **acid** block + acid bucket, the **Layout Wand** and
  **Flag Bow** (plant flags/regions that Grok reads), and the **agent bridge** (above).

Build the jar: `cd mods/towerdefense && JAVA_HOME=<jdk21> ./gradlew build`.

---

## Asset mods in use

From [`MODS.md`](MODS.md) (must match on client + server): **Macaw's Furniture / Windows /
Doors / Fences & Walls**, **Blockus**, **Farmer's Delight** — plus Fabric API and our
**Tower Defense** jar. All Fabric 1.21.6-compatible.

---

## Codex swarm (`mcp/`) — the collaborator's lane

Codex owns a **multi-agent build swarm**: a planner/boss compiles a build request into a
shared `.codex-runtime/swarm/state.json` job plan, and N drone workers execute their assigned
block jobs. Addressable in game as `@swarm` (and `@codex` for the command bot). It can run on
a vanilla server via Mineflayer workers, or on a **modded** server via **`mcp/bridge-drone.mjs`**
(same job format, blocks placed over the bridge). The shared `state.json` schema is the
integration seam between Codex's swarm and Grok's builder crew.

Details: [`mcp/README.md`](mcp/README.md) and [`BRIDGE.md`](BRIDGE.md) ("Bridge for the swarm").

---

## Running the server & joining

**Versions (pinned):** Minecraft **1.21.6** · Fabric Loader 0.19.3 · Fabric API 0.128.2 ·
Java (Temurin) **21** · Node **22**. Don't bump 1.21.6 without re-checking every agent lane.

- **Server:** `./server/run.sh` (first run generates the world; `online-mode=false` so bots
  join with plain offline usernames). Rebuild-from-clone steps: [`server/README.md`](server/README.md).
- **Console:** commands can be piped in via the FIFO `server/console.in`
  (e.g. `echo "op Grok" > server/console.in`).
- **Modded dev server:** `scripts/deploy-modded.sh` stands up a modded instance
  (`server-modded/`) with the bridge, used to prove Grok/agents-on-mods without touching the
  main server. See [`BRIDGE.md`](BRIDGE.md).
- **Joining as a human:** your client must run the **same mod set** — full client setup and the
  join target are in [`MODS.md`](MODS.md).

## Where things live

```
grok/                Grok assistant, build pipeline, terrain, flags, builder crew   (Andrew/Claude)
  lib/build/         semantic blueprint → additive site-aware compiler + archetypes
  agents/ builders/  LLM-embodied + "dumb" by-hand builder bots
mods/towerdefense/   Tower Defense game + acid + Layout Wand/Flag Bow + agent bridge (Andrew/Claude)
mcp/                 Codex's build swarm, command bot, bridge drones               (Alli/Codex)
server/              Fabric 1.21.6 server runtime (run.sh, console.in)
server-modded/       modded dev server for the bridge (:25566, bridge :25580)
docs/superpowers/    design specs + implementation plans
BRIDGE.md            the mod-side agent bridge (endpoints, examples)
MODS.md              client/server mod set + how to join
```

Design specs and plans (the *why* + the roadmap) live in
[`docs/superpowers/specs/`](docs/superpowers/specs/) and
[`docs/superpowers/plans/`](docs/superpowers/plans/); cross-lane coordination is on **issue #9**.
</content>
</invoke>
