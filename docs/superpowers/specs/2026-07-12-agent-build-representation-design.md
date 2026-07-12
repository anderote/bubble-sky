# Agent-Native Build Representation — Design

Date: 2026-07-12
Status: Approved (build all → ship as one PR for Codex review)

## Problem

Agents (Grok's Architect + Codex's swarm) need a **shared representation of what to
build** that they can (1) reason about semantically, (2) add to incrementally without
destroying surroundings, and (3) fit naturally into terrain. Today two half-solutions
exist:

- **Grok** plans furnished/detailed builds via `lib/architect.js` + procedural
  generators (`structures.js`, `detail.js`, `furniture.js`, `palettes.js`) and realizes
  them with godmode `/fill`/`/setblock`. No persisted, inspectable, diffable target.
- **Codex** (merged PR #7) compiles WorldEdit `.schem` schematics into a
  `.codex-runtime/swarm/state.json` job list that drones consume. Jobs are opaque
  per-block placements with only a coarse `phase`. Its compiler does a **blanket clear**
  (an `air` job for every cell in the bounding box) and `.schem` carries **no semantic
  regions**. Codex's own handoff explicitly asks for "a richer metadata layer" with
  "semantic regions like north wall / great hall" and a `castle.json` metadata file with
  "regions and material palette."

We build the missing layer on top of Codex's system — not a competing one.

## Goals

1. **Reason-able**: a semantic layer of named regions + typed components, not raw voxels.
2. **Incremental & non-destructive**: compile emits jobs ONLY for cells that differ from
   the live world. **No blanket air-clear.** "Add a west tower" touches only the tower.
3. **Site-aware**: builds anchor to terrain (heightmap/slope/water) and fit the landscape.
4. **Collaborative**: output is Codex's exact `state.json` job schema so Grok-godmode OR
   Codex drones fill it; re-diffing auto-requeues missing/failed blocks.
5. **Interoperable**: import/export WorldEdit `.schem` to share Codex's schematic library.

## Non-goals (this phase)

- We do NOT build a parallel drone runner/swarm — filling by multiple bots stays Codex's
  lane; we emit the shared job format and provide a godmode reference filler.
- Robust server-confirmed block verification (needs the mod bridge, task #13) is Phase 2;
  Phase 1 uses command-feedback / re-diff.

## The three-layer representation

### Layer A — Semantic Blueprint (`grok/blueprints/*.blueprint.json`)

What agents reason and plan in. Coordinates are **local** (build-space, relative to the
anchor); world placement is resolved at compile time.

```jsonc
{
  "schemaVersion": 1,
  "name": "Emberwatch Keep",
  "style": "medieval",
  "palette": { "wall": "stone_bricks", "trim": "dark_oak_log", "roof": "dark_oak_stairs",
               "floor": "spruce_planks", "glass": "glass_pane", "light": "lantern", "...": "..." },
  "anchor": { "origin": {"x":100,"y":72,"z":-40}, "facing": "north", "terrainFit": "follow" },
  "bounds": { "min": {"x":0,"y":0,"z":0}, "max": {"x":24,"y":18,"z":24} },
  "regions": [
    { "id": "foundation", "name": "Foundation", "phase": 1, "components": [
        { "kind": "box", "role": "foundation", "a": {"x":0,"y":0,"z":0}, "b": {"x":24,"y":1,"z":24}, "hollow": false } ] },
    { "id": "north_wall", "name": "North Wall", "phase": 3, "components": [
        { "kind": "wall", "role": "wall", "a": {"x":0,"y":2,"z":0}, "b": {"x":24,"y":10,"z":0}, "hollow": false },
        { "kind": "point", "role": "door", "block": "oak_door[facing=south,half=lower]", "at": {"x":12,"y":2,"z":0} } ] },
    { "id": "great_hall", "name": "Great Hall", "phase": 6, "components": [
        { "kind": "skill", "skill": "furnishRoom", "args": { "type": "great_hall", "a": {"x":2,"y":2,"z":2}, "b": {"x":22,"y":8,"z":22} } } ] }
  ]
}
```

Component kinds:
- **structural** — `box | wall | cylinder | dome | prism | line | slab` with `role`
  (palette lookup) or explicit `block`, plus `hollow`. Defined by two local coords `a`,`b`.
- **functional** — `point` with an explicit `block[state]` at a single coord `at`.
- **skill** — invokes an existing generator/detailer/furnisher (`structures.gens`,
  `detail`, `furnishRoom`) which writes into the target grid (NOT the world). Bridges the
  building-intelligence we already have into the blueprint.
- **carve** — the ONLY way air is produced: `role:"carve"` / `block:"air"` shapes emit air
  for just that region (e.g. hollow out a doorway). Absent any carve, nothing is deleted.

### Layer B — Voxel Target (in-memory, world-anchored)

```
{ origin: {x,y,z}, size: {x,y,z},
  palette: ["<unset>", "stone_bricks", "oak_door[facing=south,half=lower]", ...],  // index 0 = UNSET/keep
  grid:    Int16Array(size.x*size.y*size.z) }   // grid[i] = palette index; 0 = don't-touch
```

`0` means "the design says nothing here — preserve the world." This is the additive
principle encoded in the data model: only non-zero cells are ever placed. `.schem`
import/export maps to/from this grid (via `prismarine-schematic`).

### Layer C — Job State (Codex's `state.json`, extended)

Compile diffs the target against the live world and emits Codex's exact job shape, plus a
`region` tag and optional lease fields:

```jsonc
{ "taskId": "...", "status": "building", "structure": "...", "origin": {...},
  "source": { "type": "blueprint", "name": "...", "size": {...}, "additive": true },
  "workers": [...],
  "jobs": [ { "id": "...", "pos": {"x":.,"y":.,"z":.}, "block": "stone_bricks[...]",
              "phase": "walls", "region": "north_wall", "worker": 1 } ],
  "claims": { "north_wall": { "agent": "GrokDev", "ts": 0, "ttl": 60000 } } }
```

## Pipeline (`grok/lib/build/`)

1. **author** — Architect (Fable) writes/edits a Layer-A blueprint from a request, using
   the palettes/furniture/detail catalog. (Extends `lib/architect.js`.)
2. **anchor (site-aware)** — `survey.js` heightmap/slope/water → adjust anchor Y, extend
   foundations down to the ground contour, orient the entrance downhill; `terrainFit` =
   `follow` (default, hug terrain) | `flatten` (only where explicitly allowed) | `float`.
3. **compile** — walk regions/components → write palette indices into the Layer-B grid
   (structural shapes fill; skill components call generators that write the grid; carve
   writes air). Deterministic.
4. **diff** — for each non-zero target cell, read the live world; emit a job only if
   world ≠ target. No air outside carve shapes. Order jobs by `phase` then bottom-up.
5. **fill** — Godmode reference filler batches contiguous same-block runs into `/fill`,
   else `/setblock`; marks cells done; re-diff requeues failures. AND/OR export
   `state.json` + `.schem` for Codex's drones.

## Incremental edit ops

Blueprint ops recompile → additive diff → small job set, leaving other regions untouched:
`addRegion`, `addComponent`, `removeRegion`, `transform` (e.g. "raise roof +3", "add west
tower"). "Make it taller / add a wing" become blueprint edits, not rebuilds.

## Multi-agent fill (fields defined now, full protocol Phase 2)

Jobs carry `region`; `state.claims` holds `{region: {agent, ts, ttl}}`. A filler claims a
region (lease with TTL), fills it, marks done; expired claims are reclaimable. The godmode
realizer is one filler; Codex drones are others — same state file.

## Module layout (new, under `grok/lib/build/`)

- `blueprint.js` — Layer-A schema, validation, edit ops (addRegion/transform/...).
- `voxel.js` — Layer-B target (palette+grid), get/set, `.schem` import/export.
- `compile.js` — regions/components → grid (incl. skill-component bridge to generators).
- `anchor.js` — site-aware terrain fit (uses `survey.js`).
- `diff.js` — target vs live world → jobs (additive, phase/bottom-up ordering).
- `state.js` — Codex `state.json` read/write + claim/lease helpers.
- `realize.js` — godmode filler (batch `/fill`+`/setblock`) + `.schem` export.
- `index.js` — `planAndBuild(request)` orchestrator wiring author→anchor→compile→diff→fill.

`lib/architect.js` emits Layer-A blueprints (instead of ad-hoc plans); `lib/skills.js`
exposes blueprint ops as skills; `assistant.js` routes builds/edits through the pipeline.

## Testing

- Unit: compile a fixture blueprint → target grid asserts (shape/palette/hollow/carve);
  edit ops produce expected additive deltas.
- Diff: seed a partial world (a wall already placed) → diff emits jobs ONLY for missing
  cells, never air outside carve.
- Additive proof: place a build over existing terrain/a tree → verify surrounding blocks
  are untouched (no mass-clear).
- Site-fit: on sloped ground the anchor pass steps the foundation to the contour.
- Interop: round-trip a `.schem` through voxel import/export.
- Live (godmode, vanilla-compatible server via mineflayer): "build X here" → furnished,
  detailed, terrain-fit build with a bounded, additive job count; "add a west tower" adds
  only the tower.

## Interop / coordination with Codex

We emit Codex's `state.json` schema verbatim (+ `region`/`claims`), export `.schem`, and
document the Layer-A schema in `BLUEPRINT_HANDOFF` follow-up notes. Ship as one PR so
Codex can adopt the semantic layer their handoff requested. We do not modify their swarm
runner.
