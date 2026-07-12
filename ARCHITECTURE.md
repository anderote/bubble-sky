# bubble-sky — Architecture

System-level view of how bubble-sky's processes, interfaces, and data fit together.
For the *why* and the task breakdown, see [`docs/superpowers/specs/2026-07-11-bubble-sky-design.md`](docs/superpowers/specs/2026-07-11-bubble-sky-design.md).

---

## 1. Process topology

Everything is local to one macOS (Apple Silicon) host. Four independent processes, one shared world.

```
┌──────────────────────────────────────────────────────────────────────┐
│  macOS host (localhost)                                                │
│                                                                        │
│   ┌────────────────────────────────────────────────────────────┐      │
│   │  Fabric server  (JVM, Java 21)                              │      │
│   │  :25565  TCP   online-mode=false                             │      │
│   │  loads server/mods/*.jar  +  Fabric API                      │      │
│   │  owns the single world (authoritative game state)            │      │
│   └────────────────────────────────────────────────────────────┘      │
│        ▲                     ▲                      ▲                   │
│        │ MC protocol         │ MC protocol          │ MC protocol       │
│        │ (real account)      │ (offline user)       │ (offline users)   │
│        │                     │                      │                   │
│   ┌─────────┐          ┌──────────────┐       ┌──────────────────┐      │
│   │ Human   │          │ MCP server   │       │ mindcraft        │      │
│   │ client  │          │ (Node 20)    │       │ (Node 20)        │      │
│   │ (game   │          │ Mineflayer   │       │ Mineflayer +     │      │
│   │  launch)│          │ bot          │       │ LLM agent loop   │      │
│   └─────────┘          └──────────────┘       └──────────────────┘      │
│                              ▲                        ▲                  │
│                              │ MCP (stdio/JSON-RPC)   │ HTTPS            │
│                        ┌───────────┐            ┌──────────────┐         │
│                        │ Claude    │            │ Anthropic    │         │
│                        │ Code      │            │ API          │         │
│                        └───────────┘            └──────────────┘         │
└──────────────────────────────────────────────────────────────────────┘
```

**Authority:** the Fabric server is the single source of truth for world state. Every other
process is a *client* that observes and mutates that state only through the Minecraft protocol.

---

## 2. Components & interfaces

| Component | Runtime | Listens / connects | Interface exposed | Consumes |
|-----------|---------|--------------------|-------------------|----------|
| **Fabric server** | JVM / Java 21 | listens `:25565` TCP | Minecraft Java protocol (1.21.6); server console/commands | jars in `server/mods/` |
| **Java mods** | in-server (JVM) | — | Fabric mod entrypoints, mixins, commands, events | Minecraft + Fabric API |
| **MCP server** | Node 20 | connects `:25565`; speaks MCP over stdio | MCP tools (move/dig/place/find/chat/…) to Claude Code | Mineflayer ↔ server |
| **mindcraft** | Node 20 | connects `:25565`; calls Anthropic HTTPS | in-game chat commands; bot profiles | Mineflayer ↔ server; Anthropic API |
| **Human client** | game launcher | connects `:25565` | keyboard/mouse | server world |
| **Claude Code** | this CLI | drives MCP server over stdio | natural-language intent | MCP tools |

**Two distinct control planes for agents:**
- **MCP plane** (hands-on): Claude Code → MCP tools → Mineflayer bot → server. *You* issue intent; the bot executes discrete actions.
- **Autonomous plane** (ambient): mindcraft's own LLM loop → Mineflayer bot → server, with the Anthropic API as the reasoning backend. Runs without a human in the loop.

Both planes are *just Minecraft clients* to the server — it doesn't know or care that they're bots.

---

## 3. Key interfaces in detail

### 3.1 Minecraft protocol (the universal bus)
Every actor speaks it. `online-mode=false` means the server skips Mojang session auth, so
bots join with plain offline usernames (`ClaudeBot`, `andy`, …) and no per-bot Microsoft
account is needed. The human can still join with a real account.

**Implication:** this is *the* integration seam. Anything an agent can do, it does through
protocol packets Mineflayer knows how to send. Anything Mineflayer can't model, an agent
can't do — this is what drives the coexistence rule (§5).

### 3.2 MCP (Claude Code ↔ MCP server)
Registered in `.mcp.json`; launched via `npx -y github:yuniko-software/minecraft-mcp-server`
with `--host localhost --port 25565 --username <bot>`. Transport is stdio JSON-RPC. Tool
surface: `get-position`, `move-to-position`, `look-at`, `jump`, `fly-to`, `list-inventory`,
`find-item`, `equip-item`, `place-block`, `dig-block`, `get-block-info`, `find-blocks`,
`smelt-item`, `find-entity`, `send-chat`, `read-chat`, `detect-gamemode`.

### 3.3 Fabric mod API (Java mods ↔ server)
Mods hook the server via Fabric entrypoints (`ModInitializer`), event callbacks, mixins, and
custom commands. Build with the Gradle wrapper (Java 21) → jar → `server/mods/`. Server
restart (or a dev hot-reload flow) picks up changes.

### 3.4 Anthropic API (mindcraft ↔ cloud)
mindcraft bots call the Anthropic API directly for reasoning. Key lives in
`mindcraft/keys.json` (gitignored). This is the only component that reaches outside localhost.

---

## 4. Directory structure

```
bubble-sky/
├── ARCHITECTURE.md          ← this file
├── README.md                ← collaborator quickstart + work split
├── .mcp.json                ← MCP server registration (Workstream B)
├── server/                  ← Fabric server runtime            (Workstream A)
│   ├── server.jar / fabric launcher
│   ├── server.properties    ← online-mode=false, port 25565
│   ├── mods/                ← compiled mod jars land here
│   │   └── .gitkeep
│   └── world/               ← generated (gitignored)
├── mods/                    ← Java mod dev workspace           (Workstream A)
│   └── <mod-project>/        ← Fabric example-mod template + Gradle
├── mcp/                     ← MCP wiring / notes                (Workstream B)
├── mindcraft/               ← cloned mindcraft + profiles       (Workstream B)
│   └── keys.json            ← gitignored
└── docs/superpowers/specs/  ← design spec
```

Gitignored: `server/` runtime (except `mods/.gitkeep`), all `build/`/`.gradle/`,
`node_modules/`, `keys.json`, `.env`, `.DS_Store`.

---

## 5. The coexistence constraint (architectural rule)

Because the Minecraft protocol is the only integration bus and Mineflayer models ~vanilla
protocol, **custom-registered blocks/items are invisible/ambiguous to bots**.

- **Bot-facing mods stay vanilla-compatible** → prefer server-side gameplay/logic/command
  mods that manipulate vanilla blocks, entities, and rules.
- **Custom-content mods are human-facing** → bots ignore or mishandle those registrations.
- Workstream A tags every mod one way or the other; Workstream B assumes bots only understand
  the vanilla-compatible surface.

**Future extension (out of scope v1):** a mod-side agent bridge — a Fabric mod that exposes an
in-server API (custom packets or a local socket) letting agents perceive/act on modded content
directly, bypassing the vanilla-protocol ceiling. Would give bots full modded awareness at the
cost of a bespoke protocol. Deliberately deferred.

---

## 6. Control & data flow (a typical action)

**"Claude, mine the iron ore to your west":**
1. Claude Code interprets intent → calls MCP `find-blocks` (type=iron_ore) → server replies with coords.
2. Claude calls `move-to-position` → Mineflayer pathfinds → sends movement packets → server updates world.
3. Claude calls `dig-block` at the coords → Mineflayer sends dig packets → server validates, breaks block, drops item.
4. Server broadcasts world updates to *all* clients (human sees the block vanish too).
5. Claude calls `list-inventory` to confirm the pickup.

Every step is a protocol round-trip against the authoritative server; no client trusts its own
view of the world.

---

## 7. Deployment / run order

1. **P0** Java 21 + Node 20 pinned.
2. **P1** `server/` boots → world generated → human can join `localhost:25565`.
3. **P2** `.mcp.json` registered → Claude Code drives the MCP bot.
4. **P3** mindcraft configured with key → autonomous bot joins.
5. **P4** first mod jar in `server/mods/` → visible in-game.
6. **P5** iterate.

Startup dependency: everything depends on the server being up (P1). Both agent planes (P2/P3)
can be developed against a temporary LAN-opened singleplayer world before P1 lands, keeping the
two workstreams parallel.
