# Agent-Native Build Representation — Implementation Plan

Spec: `docs/superpowers/specs/2026-07-12-agent-build-representation-design.md`
Branch: `grok`. Ship as one big PR when done.

## Ground rules
- New module under `grok/lib/build/`. Additive; don't break existing Grok behavior.
- Pure functions where possible (compile/diff/anchor take data, return data) so they're
  unit-testable without a live server.
- Reuse existing code: `structures.js` (gens/alias), `detail.js`, `furniture.js`
  (`makeFurniture`/`furnishRoom`), `palettes.js` (`resolvePalette`), `lib/survey.js`,
  `lib/hands.js` (godmode fill/setBlock), `lib/skills.js`.
- The additive invariant is the whole point: **never emit air except for explicit
  `carve` shapes; index 0 = preserve world.**

## Steps (ordered, each independently testable)

1. **`voxel.js`** — Layer-B target: `createTarget(origin,size)`, `idx(x,y,z)`,
   `set(x,y,z,block)` (interns into palette, index 0 reserved = unset), `get`, iterate
   non-zero cells. `.schem` import/export via `prismarine-schematic` (add dep to
   `grok/package.json`). Unit test: set/get, palette interning, round-trip a tiny grid.

2. **`blueprint.js`** — Layer-A schema + `validate(bp)` + edit ops (`addRegion`,
   `addComponent`, `removeRegion`, `transform`). Normalizes coords, resolves palette via
   `resolvePalette`. Unit test: validate a fixture; edit ops mutate as expected.

3. **`compile.js`** — `compile(bp) -> target`. Walk regions (sorted by phase) →
   components: structural shapes rasterize into the grid (box/wall/cylinder/dome/prism/
   line/slab, honoring `hollow`); `point` sets one cell; `carve` writes air; `skill`
   components call the matching generator/`furnishRoom` through a thin adapter whose
   `set/fillBox` write into the TARGET GRID instead of enqueuing world commands. Unit
   test: fixture blueprint → assert specific cells + hollow interiors empty + carve = air.

4. **`anchor.js`** — `anchorToSite(bp, survey) -> bp'`: from `survey.js` heightmap adjust
   anchor Y, extend foundation region down to the ground contour per column, orient by
   slope; respect `terrainFit` follow|flatten|float. Unit test with a synthetic heightmap.

5. **`diff.js`** — `diff(target, readBlock) -> jobs`: for each non-zero cell, compare to
   `readBlock(x,y,z)`; emit a Codex-shaped job `{id,pos,block,phase,region}` only when
   different. Order by phase then y ascending. `readBlock` is injectable (real =
   mineflayer `bot.blockAt`; tests = a Map). Unit test: partially-built world → only
   missing cells; assert no air outside carve.

6. **`state.js`** — read/write `.codex-runtime/swarm/state.json` in Codex's schema +
   `region`/`claims`; claim/lease helpers (`claimRegion`, `releaseExpired`). Reuse
   Codex's `assignJobs` chunking shape (mirror `mcp/blueprint-compiler.mjs`). Unit test
   round-trip.

7. **`realize.js`** — godmode filler: consume jobs, batch contiguous same-block runs into
   `/fill`, else `/setblock` (via `hands`), throttled through the existing command queue;
   mark done; re-diff to requeue failures; also `exportSchem(target,path)` +
   `writeState(jobs)`. 

8. **`index.js`** — `planAndBuild({request, origin, facing}, ctx)` orchestrator:
   author(architect) → anchor(survey) → compile → diff(bot.blockAt) → realize; returns a
   summary (regions, job count, materials, additive=true). `editBuild(op)` for
   incremental ops.

9. **Wire in**:
   - `lib/architect.js` — emit a Layer-A blueprint (regions/components/palette/anchor)
     via the `submit_build_plan` tool; keep spec-first behavior; the blueprint IS the plan.
   - `lib/skills.js` — register blueprint edit ops as skills (`add_region`, `extend`,
     `raise_roof`, etc.) so incremental edits route through the pipeline.
   - `assistant.js` — `plan_build` and edit requests go through `build/index.js`; keep
     quiet mode + the single start/finish lines; keep `claudebert`-only allowlist.

10. **Tests + live check**: run the unit tests (node, no server). Then a live godmode
    check on the vanilla-compatible server (mineflayer dev bot, `GROK_ALLOW` env): "build
    a furnished medieval keep here" → additive, terrain-fit, furnished; confirm job count
    is bounded and surrounding terrain/trees are untouched; "add a west tower" adds only
    the tower. Capture a transcript.

## Out of scope now (note in PR)
- Live multi-drone claiming loop (Codex's runner consumes our `state.json`); we ship the
  format + claim fields + a godmode reference filler.
- Server-confirmed verification → Phase 2 via mod bridge (task #13).

## Ship
Commit the building-intelligence work + this representation module + spec/plan docs as one
coherent PR titled "Agent-native build representation (semantic blueprint → additive,
site-aware compiler → shared job state)". Exclude the WIP acid mod (separate). Push + open
PR against `main` for Codex review. (Explicitly authorized by user for this work.)
