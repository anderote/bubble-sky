# Blueprint Compiler Handoff

This branch packages the post-GrabCraft path for review. GrabCraft is useful as a
human-facing source of ideas and layer-by-layer references, but the swarm should
execute structured Minecraft schematics whenever possible.

## Current workflow

1. Download or convert a build into WorldEdit/Sponge `.schem`.
2. Put the file in `mcp/blueprints/imported/`, for example:

   ```sh
   mcp/blueprints/imported/castle.schem
   ```

3. Start the swarm:

   ```sh
   ./mcp/codex-swarm.sh start 4
   ```

4. Ask the boss in game:

   ```text
   @swarm blueprints
   @swarm build schematic castle at -40 72 40
   @swarm status
   @swarm blueprint
   ```

The boss compiles the schematic into `.codex-runtime/swarm/state.json`. Drones
consume assigned jobs from that state file.

## What changed

- `prismarine-schematic` is installed in `mcp/package.json`.
- `mcp/blueprint-compiler.mjs` reads `.schem` / legacy `.schematic` files and
  converts non-air blocks into swarm jobs.
- `mcp/compile-schematic.mjs` lets you compile a schematic without a running
  server, mainly for inspection/debugging.
- `CodexBoss` supports:
  - `@swarm blueprints`
  - `@swarm build schematic <name> [at x y z]`
  - `@swarm blueprint`
  - natural command normalization like `build me a cabin` and `we need a cabin`
- Drones batch command-mode work and use `/fill` for contiguous runs.
- Drones verify command endpoints and retry once before marking a batch failed.
- Swarm sessions pipe verbose logs to `.codex-runtime/swarm/logs/*.log`.

## Important caveats

- The built-in `cabin` and `watchtower` are test/demo blueprints. Serious builds
  should come from `.schem` files.
- `.schem` files usually do not include semantic regions like "north wall" or
  "great hall." The boss can report phases such as `clear`, `foundation`,
  `walls`, `roof`, and `detail`, but true architectural region names require a
  richer metadata layer.
- Command mode uses `/setblock` and `/fill`, so bot accounts must be opped.
- Verification checks command endpoints, not every block in a large fill. This
  is a pragmatic speed/reliability tradeoff.

## Useful commands

Compile a schematic locally:

```sh
node ./mcp/compile-schematic.mjs castle --origin -40,72,40
```

Inspect live tmux panes:

```sh
./mcp/codex-swarm.sh logs boss
./mcp/codex-swarm.sh logs 1
```

Inspect persistent logs:

```sh
./mcp/codex-swarm.sh logfiles
tail -f .codex-runtime/swarm/logs/boss.log
tail -f .codex-runtime/swarm/logs/drone-1.log
```

## Suggested next steps

1. Use GrabCraft to identify good builds, then find or create `.schem` versions.
2. Add a small curated blueprint library under `mcp/blueprints/imported/` or keep
   large schematics out of git and document where to place them.
3. Add blueprint metadata next to schematics, for example `castle.json`, with
   human names, recommended origin behavior, regions, and material palette.
4. Add a cleanup command such as `@swarm demolish last build` or
   `@swarm clear area`.
5. If semantic progress is needed, compile metadata regions into phases like
   `north wall`, `gatehouse`, `roof`, and `interior detail`.
