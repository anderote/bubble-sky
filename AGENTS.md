# Bubble Sky agent instructions

Read [`CLAUDE.md`](CLAUDE.md) for the project map, runtime constraints, and collaboration safety
rules. Those instructions apply equally to Codex and Claude.

When the user asks to pick up, continue, inspect, or summarize work from Minecraft chat:

1. Run `./scripts/station.mjs jobs` to find the requested job.
2. Run `./scripts/station.mjs handoff latest` or
   `./scripts/station.mjs handoff <job-id>` to load its complete durable context.
3. Inspect current git/PR state before editing; a transcript may describe work that has moved.
4. After meaningful progress, offer or run
   `./scripts/station.mjs announce "<short status>"` when the user wants the update visible
   in Minecraft.

The human workflow and exact Minecraft commands are in [`DEVFLOW.md`](DEVFLOW.md). Never copy
private Station tokens or runtime job files into the repository.
