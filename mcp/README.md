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
| Bot username | `claude` | `MCP_MINECRAFT_USERNAME` |

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

For overnight/local operation, run it under the managed tmux launcher. It restarts
with backoff when the server is unreachable or disconnects the bot:

```sh
MINECRAFT_HOST=192.168.86.188 ./mcp/codex-command.sh start
./mcp/codex-command.sh status
./mcp/codex-command.sh logs
./mcp/codex-command.sh stop
```

To run the same chat command behavior for another AI, give that bot its own
Minecraft username:

```sh
CODEX_BOT_USERNAME=claude MINECRAFT_HOST=192.168.86.188 node ./mcp/codex-command-bot.mjs
CODEX_BOT_USERNAME=grok MINECRAFT_HOST=192.168.86.188 node ./mcp/codex-command-bot.mjs
```

Then address it in chat by its username. Conversations are public by default so everyone can see what
players are asking the LLM bots and what they reply:

```text
@codex help
@codex follow me
@codex escort me to -40,40
@codex stop
@codex say hello
@codex history
@codex status
```

For `claude`, use `@claude`. For `grok`, use `@grok`.

Chat visibility can be changed at runtime:

```text
@codex visibility public
@codex visibility private
@codex visibility llm
@codex visibility alone
```

Modes:

| Mode | Behavior |
|------|----------|
| `public` | Bot replies are normal world chat. This is the default. |
| `private` | Bot replies go back to the player who addressed `@codex`. |
| `llm` | Bot replies are whispered to visible LLM players listed in `CODEX_LLM_PLAYERS` plus the player who addressed `@codex`. |
| `alone` | Codex whispers only to the player who addressed it and keeps LLM/build-bot chatter such as Grok and drones out of Codex history/status. It does not server-mute other players' public chat. |

Addressed prompts and bot replies are persisted to `.codex-runtime/chat-history.jsonl`
by default. This lets someone log in later and ask:

```text
@codex history
@codex history 12
@codex what are you up to
```

Environment knobs:

| Setting | Default | Notes |
|---------|---------|-------|
| `CODEX_BOT_USERNAME` | `codex` | In-game bot name. |
| `CODEX_BOT_ALIASES` | unset | Optional comma-separated extra names that should address this bot. |
| `CODEX_CHAT_VISIBILITY` | `public` | Startup visibility mode. |
| `CODEX_CHAT_HISTORY` | `.codex-runtime/chat-history.jsonl` | JSONL transcript path. |
| `CODEX_CHAT_HISTORY_LIMIT` | `2000` | Maximum retained transcript events. |
| `CODEX_LLM_PLAYERS` | `codex,claude,grok` | Comma-separated recipients for `llm` mode. |
| `CODEX_RICH_CHAT` | unset | Set to `1` to send colored/bold `/tellraw` output, including highlighted `@codex`, `@grok`, and `@claude` mentions. The bot must be opped on a vanilla server. |

## Swarm launcher

The detached swarm launcher keeps drone tmux sessions alive and backs off when the
Minecraft server is unreachable or repeatedly disconnects bots:

```sh
MINECRAFT_HOST=192.168.86.188 CODEX_SWARM_COUNT=1 ./mcp/codex-swarm.sh start
./mcp/codex-swarm.sh status
./mcp/codex-swarm.sh logs 1
./mcp/codex-swarm.sh stop
```

Retry knobs:

| Setting | Default | Notes |
|---------|---------|-------|
| `CODEX_SWARM_RESTART_MIN_SECONDS` | `3` | First restart delay after a short failed run. |
| `CODEX_SWARM_RESTART_MAX_SECONDS` | `60` | Maximum restart delay while the server is unhealthy. |
| `CODEX_SWARM_RESTART_HEALTHY_SECONDS` | `120` | Runtime after which the failure counter resets. |

Extra examples:

```text
@codex tell alli coming over now
@codex come to Andrew
@codex look at me
@codex go to 12 64 -8
@codex bring me to -40,40
@codex lead me to -40 72 40
@codex where are you
@codex bring me to you
@codex bring me to where codex is
```
