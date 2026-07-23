# Bubble Sky: working context for Claude

Bubble Sky is a private Minecraft 1.21.6 project shared by Alli and Andrew. Read
[`README.md`](README.md) for the product map and [`ARCHITECTURE.md`](ARCHITECTURE.md) for
runtime boundaries. The most important rule is that the client and server must use the exact
same custom and third-party mod jars.

## Andrew's quickest path

This checkout includes Bubble Sky Station vNext: a small control plane that runs Claude/Codex
jobs from Minecraft, coordinates two Macs, and installs checksum-locked play releases.

```sh
git pull --ff-only origin main
./scripts/station.mjs setup andrew
./scripts/station.mjs doctor
./scripts/station.mjs status
./scripts/station.mjs pair-code
```

The setup is guided and writes the private config to
`~/.config/bubble-sky/station.json`. It finds the `claude` executable, installs three macOS
LaunchAgents, and never stores secrets in this repository. Ask Alli for the shared Station
token over a private channel; the same token must be configured on both Macs. Full operational
details are in [`control/README.md`](control/README.md).

Useful recovery commands:

```sh
./scripts/station.mjs restart
./scripts/station.mjs logs station
./scripts/station.mjs logs chat
./scripts/station.mjs logs release
./scripts/station.mjs test
```

When a user asks to continue something from Minecraft, follow [`DEVFLOW.md`](DEVFLOW.md):
run `./scripts/station.mjs jobs`, then `./scripts/station.mjs handoff latest` (or the named
job). Post concise progress back with `./scripts/station.mjs announce "..."` when requested.

## Development map

- `mods/towerdefense/`: survival tower-defense/RPG mod and in-JVM HTTP agent bridge.
- `mods/bubble-sky-mod/`: smaller shared custom-content mod.
- `grok/`: Grok builder, architect pipeline, terrain tools, Warlord, and Foreman.
- `mcp/`: Codex swarm, command bot, bridge drones, and schematic compiler.
- `control/`: Station HTTP service, Minecraft gateway, job runner, and release watcher.
- `release/`: locked release inputs and impact metadata.
- `server/`: local play-server runtime. Preserve worlds and local config.

The bridge contract is documented in [`BRIDGE.md`](BRIDGE.md); mod/client dependencies and
join setup are in [`MODS.md`](MODS.md). Node is pinned to 22 and Java to 21. Build both mods
with their checked-in Gradle wrappers; run control-plane tests with
`node --test control/test/*.test.mjs`.

## Safe collaboration rules

- Begin feature work from a freshly fetched `origin/main` in a separate branch/worktree.
- Do not reset, clean, or overwrite another collaborator's checkout or local runtime files.
- Never commit tokens, API keys, `.env` files, Station config, worlds, logs, or generated jars.
- Keep the bridge and Station on localhost/trusted LAN/Tailscale; do not expose them publicly.
- Development agents may implement and test, but releases come only from merged `main`.
- A release is a complete, checksum-verified mod-set swap with rollback—not an ad-hoc jar copy.
- Before changing an API or shared job schema, inspect both its producers and consumers.

When asked to change the project, inspect the relevant README and nearby tests first, make the
smallest coherent change, run proportionate tests, and report the exact checks that passed.
