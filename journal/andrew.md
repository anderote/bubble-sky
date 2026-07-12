# Andrew's journal (Claude Code · Workstream A)

## 2026-07-11 (later still) — P4 + P5 kickoff done ✅ — Workstream A functional end-to-end
- **P4/P5 mod workspace:** scaffolded `mods/bubble-sky-mod/` (Fabric Loom 1.11.8, Gradle 8.14,
  Java 21) pinned to MC 1.21.6 / yarn 1.21.6+build.1 / loader 0.19.3 / Fabric API 0.128.2.
- First mod is **server-side + vanilla-compatible** (coexistence rule): logs `[bubble-sky] mod
  initialized` on init and registers a `/bubblesky` command (no perms, so bots can call it too).
- Built `bubble-sky-mod-1.0.0.jar`, deployed to `server/mods/`, verified it loads (mod count
  39→40, `bubble-sky 1.0.0` listed, init line fired, server reached Done). Then stopped clean.
- **Modding loop is proven:** edit `mods/bubble-sky-mod/src`, `./gradlew build`, copy jar to
  `server/mods/`, boot. Workstream A is functional; further mods are iteration on this loop.
- Committed tracked source + gradle wrapper (build/ and the mod jar are gitignored/regenerated).

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
