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
| `llm` | Bot replies are whispered to visible LLM players listed in `CODEX_LLM_PLAYERS` plus the player who addressed `@codex`. |

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
| `CODEX_IGNORED_SPEAKERS` | `codexdrone1,codexdrone2,codexdrone3,codexdrone4,codexboss` | Speakers the command bot records but will not answer, preventing swarm status chatter from triggering help menus. |
| `CODEX_RICH_CHAT` | unset | Set to `1` to send colored/bold `/tellraw` output, including highlighted `@codex`, `@grok`, and `@claude` mentions. The bot must be opped on a vanilla server. |

Extra examples:

```text
@codex tell alli coming over now
@codex come to Andrew
@codex look at me
@codex do you see what I'm looking at
@codex do you see this castle
@codex keep building this red thing
@codex do you see this red thing, keep building it
@codex go to 12 64 -8
@codex build fortress here
@codex delete this castle
@codex burn this castle with lava
@codex freeze this tower
@codex bring me to -40,40
@codex lead me to -40 72 40
@codex where are you
@codex bring me to you
@codex bring me to where codex is
```

## Swarm builders

The swarm runner starts one planner/boss bot plus drone botfolk. The boss listens
for build requests, writes a shared plan under `.codex-runtime/swarm/state.json`,
and drones execute their assigned block jobs.

Start four drones plus `CodexBoss`:

```sh
./mcp/codex-swarm.sh start 4
```

In game:

```text
@swarm help
@swarm build cabin
@swarm build me a cabin
@swarm we need a cabin
@swarm give me a watchtower
@swarm please make a watchtower near -40 72 40
@swarm build cabin here
@swarm build cabin at -40,40
@swarm build cabin at -40 72 40
@swarm build watchtower
@swarm burn this castle with lava
@swarm flood this base
@swarm freeze this tower
@swarm curse this fortress
@swarm blueprints
@swarm build schematic castle at -40 72 40
@swarm blueprint
@swarm status
@swarm cancel
```

Operational commands:

```sh
./mcp/codex-swarm.sh status
./mcp/codex-swarm.sh logs boss
./mcp/codex-swarm.sh logs 1
./mcp/codex-swarm.sh logfiles
tail -f .codex-runtime/swarm/logs/boss.log
tail -f .codex-runtime/swarm/logs/drone-1.log
./mcp/codex-swarm.sh stop
```

`logs boss` and `logs 1` read recent tmux pane output. `logfiles` shows the
persistent log directory. New swarm sessions pipe verbose boss/drone output into
`.codex-runtime/swarm/logs/*.log`.

The boss reports high-level progress in chat. For arbitrary schematics, semantic
labels like "north wall" are not reliable because `.schem` files contain blocks,
not architectural regions. Instead the boss announces phase completions such as
clear, foundation, walls, stairs, roof, lighting, and detail, plus periodic
`n/m jobs` progress. Ask `@swarm blueprint` for the current source file and size.

The boss also understands a small natural-language effect vocabulary for nearby
structures. Requests like `burn this castle with lava`, `flood this base`,
`freeze this tower`, and `curse this fortress` compile into block-job plans
around the requesting player's position, so drones can execute the effect through
the same Bubble Sky bridge as builds.

The first implementation uses `/setblock` for reliable building, while drones
still move near their assigned jobs so they appear to be working in-world. On an
offline-mode vanilla/Fabric server, op the bot accounts before asking them to
build:

```text
op CodexBoss
op CodexDrone1
op CodexDrone2
op CodexDrone3
op CodexDrone4
```

If the drones are not opped, Minecraft hides `/setblock` and `/fill` from them.
The workers now detect that command rejection and mark the batch failed instead
of pretending the build completed.

### Schematic blueprints

The swarm can compile WorldEdit/Sponge `.schem` files into the same per-block
job plan used by the built-in cabin and watchtower.

Put imported schematics here:

```sh
mcp/blueprints/imported/castle.schem
```

Then run in game:

```text
@swarm blueprints
@swarm build schematic castle at -40 72 40
```

You can also compile without a running server to inspect the generated plan:

```sh
node ./mcp/compile-schematic.mjs castle --origin -40,72,40
```

Compiled plans are written under `mcp/blueprints/compiled/` by default. The
planner compiles on demand from the imported `.schem`, clears the schematic
volume first, then places non-air blocks bottom-up with coarse phases such as
foundation, supports, walls, stairs, roof, lighting, and detail. Use vanilla
block schematics for bot-facing builds. Drones observe a shared phase barrier,
so no worker starts placing foundation blocks while another is still clearing.

Environment knobs:

| Setting | Default | Notes |
|---------|---------|-------|
| `CODEX_SWARM_COUNT` | `4` | Number of drone botfolk. |
| `CODEX_SWARM_PREFIX` | `CodexDrone` | Drone username prefix. |
| `CODEX_SWARM_BOSS` | `CodexBoss` | Planner username. |
| `CODEX_SWARM_BUILD_MODE` | `command` | Uses `/setblock`; inventory mode is intentionally not implemented yet. |
| `CODEX_SWARM_COMMAND_DELAY_MS` | `100` | Small per-drone delay after sending a command. |
| `CODEX_SWARM_GLOBAL_COMMAND_DELAY_MS` | `650` | Shared delay across all drones between `/setblock` or `/fill` commands. Raise this if the server kicks drones for spam. |
| `CODEX_SWARM_BATCH_SIZE` | `32` | Number of assigned jobs a drone consumes per work cycle. |
| `CODEX_SWARM_JOB_CHUNK_SIZE` | `32` | Number of contiguous per-phase jobs the boss assigns to one drone before rotating to the next. |
| `CODEX_SWARM_VERIFY_COMMANDS` | `0` | Optional best-effort endpoint verification for `/setblock` and `/fill`. Disabled by default because Mineflayer's local world cache can lag command results and create false failures. |
| `CODEX_SWARM_IGNORED_SPEAKERS` | `codex,claude,claudebot,grok` | Chat speakers the boss ignores so ambient LLM bots do not issue swarm work. Drone names are always ignored too. |
| `CODEX_SWARM_ANNOUNCE_ON_JOIN` | `0` | Keeps bot reconnects quiet by default. |
| `CODEX_SWARM_REPORT_INTERVAL_MS` | `60000` | Boss progress report cadence while a build is active. |
| `CODEX_SWARM_REPORT_MILESTONE` | `25` | Boss also reports when progress crosses this percentage step. |
| `CODEX_DRONE_REPORT_EVERY_JOBS` | `0` | Drone checkpoint chat cadence. Keep `0` by default so drones do not trigger other LLM bots; progress, positions, and altitudes still go to `.codex-runtime/swarm/progress/*.json` and logs. |
| `CODEX_DRONE_REPORT_MIN_INTERVAL_MS` | `90000` | Minimum time between reports from the same drone. |
| `CODEX_DRONE_REPORT_PHASES` | unset | Set to `1` if drones should publicly report phase changes. |
| `CODEX_DRONE_REPORT_TASK_JOIN` | unset | Set to `1` if each drone should publicly announce joining a task. |
