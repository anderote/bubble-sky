# bubble-sky — Design Spec

**Date:** 2026-07-11
**Status:** Approved for build
**Repo:** https://github.com/anderote/bubble-sky

A local, persistent **Fabric 1.21.6** Minecraft server on Apple Silicon macOS that doubles as
(a) a **mod-development sandbox** for writing Java mods heavily, and
(b) an **AI-agent playground** where Claude Code drives a character directly (via MCP) *and* autonomous LLM bots (mindcraft) live in the world.

Everything runs on `localhost`: the human game client, the MCP-driven bot, and the mindcraft bots all connect to one server.

---

## 1. Goals & non-goals

**Goals**
- Persistent, moddable Fabric server we can iterate on daily.
- First-class Java mod-writing workflow (heavy modding is a primary goal, not an afterthought).
- Claude Code can drive an in-world character on demand (hands-on agent control).
- Autonomous LLM bots can live in the world and act on their own (ambient agent activity).
- Two people can work in parallel with minimal coordination.

**Non-goals (v1)**
- Public/internet-hosted multiplayer (local only for now).
- Bots understanding custom-registered modded blocks/items (see §4 — deferred; needs a mod-side agent bridge).
- Bedrock edition (Java only — modding requires it).

---

## 2. Architecture

```
                 ┌─────────────────────────────────────┐
                 │  Fabric 1.21.6 server (localhost)    │
                 │  online-mode=false, Java 21          │
                 │  server/mods/  ← compiled Java mods   │
                 └─────────────────────────────────────┘
                     ▲            ▲              ▲
        human client        MCP bot            mindcraft bots
        (you walk around)   (Mineflayer,       (autonomous LLM,
                             driven by          Anthropic API)
                             Claude Code)
```

**Version pin:** Minecraft **Java 1.21.6**. Chosen as the intersection sweet spot:
mindcraft recommends 1.21.6; Mineflayer/MCP-server support 1.21.8+; Fabric supports it.
Do not bump this without checking all three tools still align.

**Four components** (each independently understandable and testable):

| Component | Path | Purpose |
|-----------|------|---------|
| Server | `server/` | Fabric server install, config, world, `mods/` drop-folder. Runs `online-mode=false`. |
| Mod dev workspace | `mods/` | Fabric mod project (example-mod template + Gradle + Java 21). `./gradlew build` → jar → `server/mods/`. |
| MCP control | `mcp/` + `.mcp.json` | `minecraft-mcp-server` (Mineflayer) registered with Claude Code. Claude gets movement/dig/place/find/chat tools. |
| Autonomous bots | `mindcraft/` | Cloned mindcraft; `keys.json` with `ANTHROPIC_API_KEY`; profiles set to a Claude model. |

---

## 3. Data flow

1. Server boots with Java 21, loads Fabric API + any jars in `server/mods/`.
2. Human client connects over localhost:25565 and plays normally.
3. When Claude Code needs to act in-world, the MCP server spawns a Mineflayer bot that joins with an offline username and exposes tools to Claude.
4. mindcraft bots join with their own offline usernames and run their autonomous LLM loops against the Anthropic API.
5. All three client types share one world; `online-mode=false` lets bots authenticate with plain usernames (no per-bot Microsoft account).

---

## 4. The coexistence rule (shared contract — READ THIS)

Mineflayer speaks approximately **vanilla protocol**. Custom-registered blocks/items from heavy content mods can confuse the bots (unknown block/item IDs → mis-parsed world state).

**Rule for v1:**
- **Bot-facing mods stay vanilla-compatible.** Prefer **server-side gameplay / logic / command mods** over mods that register new blocks/items.
- Mods that *do* add custom content are treated as **human-facing**; expect bots to ignore or mishandle those blocks.
- This is the single interface between the two workstreams (§6). If Workstream A ships a custom-content mod, it must be flagged "human-facing" so Workstream B knows the bots won't understand it.

Revisiting this (making bots understand custom content via a mod-side agent bridge) is explicitly **out of scope for v1**.

---

## 5. Toolchain

- **Java 21** (Temurin) — required by Fabric 1.21.6. Install via SDKMAN or Homebrew.
- **Node 22 LTS** — current Mineflayer packages require Node 22+; mindcraft warns against Node 24+. Use nvm/fnm so it doesn't disturb system Node.
- **Fabric server launcher** + **Fabric API** mod.
- **Gradle** (via the mod template's wrapper — no separate install).

---

## 6. Work division — two parallel workstreams

The four components split cleanly into two ownership lanes that only touch at the §4 contract.

### Workstream A — Server & Modding ("Java/Minecraft" owner)
Owns `server/` and `mods/`.
- **A0** Install Java 21; pin it for this project.
- **A1** Stand up Fabric 1.21.6 server, `online-mode=false`, first world boots and a human can join and walk around.
- **A4** Scaffold Fabric mod from the example-mod template; `./gradlew build`; drop jar in `server/mods/`; confirm it loads in-game.
- **A5** Write real mods. Tag each as *server-side/vanilla-compatible* or *human-facing custom-content* per §4.
- **Deliverable:** documented `server/` run command + a working "hello world" mod others can copy.

### Workstream B — AI Agents ("AI/agents" owner)
Owns `mcp/` and `mindcraft/`.
- **B0** Install Node 22 LTS (pinned via nvm/fnm).
- **B2** Wire `minecraft-mcp-server` into `.mcp.json`; verify Claude Code can drive a bot ("go to X", "dig this block").
- **B3** Clone + configure mindcraft with `ANTHROPIC_API_KEY`; one autonomous bot joins and acts.
- **Deliverable:** documented steps so anyone can launch the MCP bot and a mindcraft bot against a running server.

### Shared / sequencing
- **B depends on A1** (a running server to connect to) — but B can develop against a temporary LAN-opened singleplayer world in the meantime, so the two lanes truly run in parallel.
- The **§4 coexistence rule** is the only cross-lane contract.

---

## 7. Build phases (verification checkpoints)

| Phase | Done when… | Owner |
|-------|-----------|-------|
| P0 Toolchain | Java 21 + Node 22 installed and pinned | A + B |
| P1 Server up | Fabric 1.21.6 offline-mode server runs; human joins and walks around | A |
| P2 MCP agent | Claude Code controls a bot in-world (move/dig verified) | B |
| P3 Autonomous bot | One mindcraft bot joins and acts on its own | B |
| P4 First mod | Example mod built and visible in-game | A |
| P5 Iterate | Real mods written; agents interact with vanilla-compatible parts | A + B |

---

## 8. Open items (non-blocking)

- **Minecraft Java Edition ownership** — required to join *as a human*. Bots don't need it (offline mode). If not owned yet, the server + bots still stand up; grab the game to join personally.
- **Anthropic API key for mindcraft** — its bots call the API directly. Separate from the Claude Code subscription (the MCP path uses Claude Code itself, no extra key). Store in `mindcraft/keys.json` (gitignored).
