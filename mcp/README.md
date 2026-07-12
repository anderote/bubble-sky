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
MINECRAFT_HOST=192.168.86.188 node ./mcp/codex-command-bot.mjs
```

Then address it in chat:

```text
@CodexBot help
@CodexBot follow me
@CodexBot stop
@CodexBot say hello
```
