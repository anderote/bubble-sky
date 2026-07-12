# bubble-sky

A local, moddable **Minecraft (Fabric 1.21.6)** server on macOS that doubles as an **AI-agent playground**:

- 🛠️ **Write Java mods heavily** in a Fabric dev workspace.
- 🤖 **Claude Code drives a character** in-world via a Mineflayer-based MCP server.
- 🧠 **Autonomous LLM bots** (mindcraft) live in the world alongside you.

Everything runs on `localhost` — your game client, the MCP-driven bot, and the mindcraft bots all connect to one server.

> **Full design & rationale:** [`docs/superpowers/specs/2026-07-11-bubble-sky-design.md`](docs/superpowers/specs/2026-07-11-bubble-sky-design.md)

---

## Version pin

**Minecraft Java 1.21.6** — the intersection where Fabric, Mineflayer/MCP, and mindcraft all align. Don't bump without re-checking all three.

## Repo layout

```
server/      Fabric server install, config, world, mods/ drop-folder   (Workstream A)
mods/        Java mod dev workspace (Gradle + Java 21)                  (Workstream A)
mcp/         Mineflayer MCP server wiring for Claude Code               (Workstream B)
mindcraft/   Autonomous LLM bots                                        (Workstream B)
client/      Local Prism client helpers, including minimap install      (shared)
docs/        Design spec                                               (shared)
```

## Client helpers

See [`client/README.md`](client/README.md) for local Prism client setup, including
the Fabric/Xaero minimap installer for Minecraft 1.21.6.

## Toolchain

| Tool | Version | Why |
|------|---------|-----|
| Java (Temurin) | **21** | Required by Fabric 1.21.6 |
| Node | **22 LTS** | current Mineflayer packages require 22+; mindcraft warns against 24+ |
| Fabric | server launcher + Fabric API | mod loader |

---

## Work division (2 collaborators)

Two parallel lanes that meet only at the **coexistence rule** (see below).

### Workstream A — Server & Modding
Owns `server/` + `mods/`.
- Stand up the Fabric 1.21.6 server (`online-mode=false`).
- Scaffold + build the first Fabric mod; deploy jar to `server/mods/`.
- Write real mods, tagging each **vanilla-compatible** or **human-facing custom-content**.

### Workstream B — AI Agents
Owns `mcp/` + `mindcraft/`.
- Wire `minecraft-mcp-server` into `.mcp.json` so Claude Code can drive a bot.
- Configure mindcraft with an `ANTHROPIC_API_KEY`; get one autonomous bot acting.

**Parallelism:** B needs a running server, but can develop against a temporary LAN-opened singleplayer world until A's server is ready — so both lanes start immediately.

---

## ⚠️ The coexistence rule (shared contract)

Mineflayer bots speak roughly **vanilla protocol**. Custom-registered blocks/items can confuse them.

- **Bot-facing mods stay vanilla-compatible** — prefer server-side gameplay/logic/command mods.
- Custom-content mods are **human-facing**; bots will ignore/mishandle those blocks.
- If Workstream A ships custom content, flag it "human-facing" so Workstream B knows.

Making bots understand custom content is **out of scope for v1**.

---

## Build phases

| Phase | Done when… | Owner |
|-------|-----------|-------|
| P0 Toolchain | Java 21 + Node 22 installed & pinned | A + B |
| P1 Server up | Offline-mode server runs; human joins & walks around | A |
| P2 MCP agent | Claude Code controls a bot (move/dig verified) | B |
| P3 Autonomous bot | One mindcraft bot joins & acts on its own | B |
| P4 First mod | Example mod built & visible in-game | A |
| P5 Iterate | Real mods written; agents interact | A + B |

---

## Status

Bootstrapping. Toolchain not yet installed. Start at **P0**.
