# MCP control

Workstream B MCP wiring for a Mineflayer bot controlled through Claude/Codex.

## Runtime

- Node is pinned by `../.node-version` to Node 22.
- Install/use it with:

```sh
eval "$(fnm env --use-on-cd)"
fnm install
fnm use
```

## Launch

The root `.mcp.json` registers one MCP server named `minecraft`. It runs:

```sh
./mcp/run-minecraft-mcp.sh
```

Defaults:

| Setting | Default | Override |
|---------|---------|----------|
| Host | `localhost` | `MINECRAFT_HOST` |
| Port | `25565` | `MINECRAFT_PORT` |
| Bot username | `ClaudeBot` | `MCP_MINECRAFT_USERNAME` |

The Fabric server must already be running with `online-mode=false`, or the MCP server will
start but the bot will fail to join.

## First verification

After Workstream A has the server up:

1. Run `node ./mcp/smoke-mineflayer.mjs` to confirm a vanilla Mineflayer client can join.
2. Start Codex/Claude from the repo so it sees `.mcp.json`.
3. Confirm the `minecraft` MCP server is connected.
4. Ask the bot for its position.
5. Ask it to move a short distance, dig one vanilla block, and send a chat message.

Keep MCP-facing tests vanilla-only. Custom registered mod blocks/items are out of scope for v1.

## Command bot

For lightweight in-game control without an MCP client attached:

```sh
./mcp/codex-bot.sh start
./mcp/codex-bot.sh status
./mcp/codex-bot.sh logs
./mcp/codex-bot.sh stop
```

Then address it in chat:

```text
@CodexBot help
@CodexBot follow me
@CodexBot stop
@CodexBot say hello
```

## Detached swarm

For long-running tasks that should not stay attached to a Codex chat session, start detached
worker bots:

```sh
./mcp/codex-swarm.sh start 3 survey
./mcp/codex-swarm.sh status
./mcp/codex-swarm.sh logs
./mcp/codex-swarm.sh stop
```

The workers write pids/logs under ignored `.codex-runtime/swarm/`.

Useful tasks:

| Task | Behavior |
|------|----------|
| `standby` | Join and wait for addressed commands |
| `survey` | Walk a square patrol around the spawn/origin |
| `flatten` | Clear blocks above a shared work area |
| `castle` | Flatten, then attempt a simple castle-wall pass if inventory permits |

Optional target settings:

```sh
MINECRAFT_HOST=192.168.86.188 CODEX_SWARM_ORIGIN=0,79,0 ./mcp/codex-swarm.sh start 4 castle
```

In-game swarm commands:

```text
@swarm help
@swarm task survey
@swarm task flatten
@swarm task castle
@swarm stop
```
