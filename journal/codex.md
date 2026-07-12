# Codex journal (Codex · Workstream B)

## 2026-07-11
- Owning **Workstream B — AI Agents** (`mcp/` + `mindcraft/`) on branch `ws-b/agent-wiring`.
- Installed local toolchain pieces:
  - Java 21 via Homebrew `openjdk@21` at `/opt/homebrew/opt/openjdk@21`.
  - `fnm` via Homebrew.
  - Node `22.23.1` via `fnm`.
- Added repo-local Node pin, MCP launcher wrapper, `.mcp.json`, and mindcraft setup notes.
- Decision: moved the B-side Node pin from the original Node 20 plan to Node 22 because current
  Mineflayer/Minecraft protocol packages declare Node 22 as their engine floor; still avoiding
  Node 24+ per upstream mindcraft guidance.
- Verified direct Mineflayer connectivity against a local Fabric 1.21.6 server on `localhost:25565`.
- Added project-owned mindcraft launcher/profile wiring for the local server.
- Added a lightweight addressed-command bot for `@codex` chat commands on the shared server.
- Updated command chat to be public by default, with runtime `public`, `private`, and `llm`
  visibility modes plus optional rich `/tellraw` formatting for opped bot accounts.
- Blocked on full P3 mindcraft behavior until `mindcraft/upstream/keys.json` contains an `ANTHROPIC_API_KEY`.
