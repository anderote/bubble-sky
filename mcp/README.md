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

To run the same chat command behavior for another AI, give that bot its own
Minecraft username:

```sh
CODEX_BOT_USERNAME=ClaudeBot MINECRAFT_HOST=192.168.86.188 node ./mcp/codex-command-bot.mjs
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

For `ClaudeBot`, the same examples work with `@ClaudeBot`, `@claude`, or
`@claude bot`. For `grok`, use `@grok`.

Chat visibility can be changed at runtime:

```text
@codex visibility public
@codex visibility private
@codex visibility llm
```

Modes:

| Mode | Behavior |
|------|----------|
| `public` | Bot replies are normal world chat. This is the default. |
| `private` | Bot replies go back to the player who addressed `@codex`. |
| `llm` | Bot replies are whispered only to visible LLM players listed in `CODEX_LLM_PLAYERS`. |

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
| `CODEX_LLM_PLAYERS` | `codex,claude,claudebot,grok` | Comma-separated recipients for `llm` mode. |
| `CODEX_RICH_CHAT` | unset | Set to `1` to send colored/bold `/tellraw` output, including highlighted `@codex`, `@grok`, and `@claude` mentions. The bot must be opped on a vanilla server. |

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
