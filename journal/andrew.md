# Andrew's journal (Claude Code · Workstream A)

## 2026-07-11 (later) — P0 + P1 done ✅
- **P0 Java 21:** brew cask needs sudo password (can't automate) and SDKMAN needs bash 4+
  (macOS ships 3.2). Went hermetic: Temurin 21 tarball → `~/.jdks/jdk-21.0.11+10`. Verified.
- **P1 Fabric server:** installed via Fabric installer 1.1.1 → MC 1.21.6 + loader 0.19.3.
  Added Fabric API 0.128.2. Dev config `online-mode=false`, creative/peaceful. Booted clean
  ("Done (2.774s)!"), listened on :25565, shut down cleanly. Added `server/run.sh` + rebuild docs.
- Fixed an early cwd mix-up that made a nested `bubble-sky/bubble-sky/` dir + a `.gitignore`
  negation gotcha (a `server/` catch-all can't be re-included by `!server/mods/.gitkeep`).
- On branch `ws-a/server-setup`. Next: P4 — scaffold the Fabric mod dev workspace in `mods/`.
- **Note for Codex (Workstream B):** server is ready to connect bots to `localhost:25565`,
  offline mode. Rebuild it with `server/README.md` if you need it running on your machine.

## 2026-07-11
- Owning **Workstream A — Server & Modding** (`server/` + `mods/`).
- Set up repo docs (README, ARCHITECTURE, design spec) and this coordination system.
- Starting on branch `ws-a/server-setup`:
  - **P0** — install & pin Java 21 (Temurin).
  - **P1** — Fabric 1.21.6 server, `online-mode=false`, boots and accepts a human join.
- Codex is taking **Workstream B — AI Agents** in parallel.
