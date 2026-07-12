# Master Hit List — 2026-07-12 (autonomous session)

User stepped out; work through ALL of this without stopping, good judgment, ship coherent
PRs for Codex. Authorized to commit/push/PR this body of work.

## The list (mapped to workstreams)

1. **Mod infrastructure** — repeatable mod build + a modded server. → Bridge workstream
   (server-modded + deploy script) + existing towerdefense scaffold.
2. **Consistent agent interface for mods** — the mod-side HTTP bridge. → Bridge workstream.
3. **Build representation for naturally-built stuff** — semantic blueprint → additive,
   site-aware compiler. → Build-rep workstream.
4. **Online research tool** — agents search the web for references to build better
   schematics (e.g. "cozy cottage", "village") → `grok/lib/research.js`, architect uses it.
5. **Clarifying questions** — vague requests ("cozy cottage", "a village") → ask 1–2
   questions in chat, wait, then build. Router/architect enhancement.
6. **Flags (named markers)** — plant a named flag (e.g. A2, B1); reference in commands
   ("build a wall between A2 and B1"). `grok/lib/flags.js` + spatial-ref build ops.
7. **Schematic library** — save liked builds to a folder; list/build/edit/reuse. Uses the
   blueprint/voxel/.schem from the build-rep. `grok/lib/build/library.js` + commands.
8. **Architect + dumb builders** — smart architect plans the schematic; a fleet of dumber
   builder agents place blocks MANUALLY (not godmode), "naturally", unlimited blocks for
   now. A mode switch: godmode ↔ builder-fleet. Consumes the shared job state (interops
   with Codex drones).

## Execution order (dependency-aware; grok/-file edits sequenced to avoid conflicts)

**Wave 0 (running):** build-representation (`grok/lib/build/`) + mod bridge (`mods/…/bridge`,
`server-modded`, `grok/lib/bridge.js`).

**Wave 1 — integrate Wave 0 + ship PRs:**
- Verify build-rep; verify bridge; wire the bridge as a `hands` backend (`bridge-godmode`)
  so the build pipeline can target the modded server (one clean swap, done by me post-landing).
- PR A "Grok building system": building-intelligence + build-representation + docs.
- PR B "Modded agent system": acid + bridge + server-modded + docs.

**Wave 2 — builder fleet (#8):** `grok/builders/` — a runner that spins up N simple bots
(mineflayer on vanilla server, or bridge agents on modded) that claim regions from the
shared job state and place blocks manually (throttled, natural order: bottom-up, adjacency
so they don't place floating blocks). Architect/assistant gets a build-mode switch
`godmode | fleet`. Interops with Codex's `state.json`/drones. Ship PR C.

**Wave 3 — building UX (grok/, sequential to avoid assistant.js churn):**
- #6 Flags: `grok/lib/flags.js` (named coords + visible marker) + build ops referencing
  flags ("wall between A2 and B1", "build X at flag A2").
- #7 Schematic library: `grok/lib/build/library.js` — save/list/load/edit named schematics
  (blueprint JSON + .schem) under `grok/blueprints/saved/`; commands "save this as <name>",
  "build <name> here", "list builds".
- #5 Clarifying questions: architect/router asks 1–2 questions on vague requests, waits,
  then proceeds.
- #4 Research tool: `grok/lib/research.js` — web search (pluggable; use available search
  API or the model's live-search if present) that gathers reference ideas/palettes/layouts
  and feeds the architect's spec. Architect calls it for open-ended requests.
- Ship PR D "Grok building UX".

## Standing decisions (my judgment, per user's "trust your best judgment")
- Builders can place unlimited blocks (creative) for now; "natural" = throttled manual
  placement in a sensible order, not instant /fill.
- Keep Grok `claudebert`-only, quiet mode, Fable architect throughout.
- Modded content stays off the main vanilla :25565 (bots depend on it); modded server is
  :25566 with the bridge on :25580.
- Ship in a few coherent big PRs (not many tiny ones); report each push.
- Don't disrupt the user's running real Grok or the vanilla server. Test with separate dev
  instances / the modded server.
- Coordinate with Codex by handing shared formats/endpoints over via PRs + BRIDGE.md /
  BLUEPRINT handoff notes; don't modify their swarm runner.

## Progress log
- [in progress] Wave 0: build-rep + bridge subagents running.
- (append as waves complete)
