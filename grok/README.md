# grok/ — our personal in-world assistant

**Grok** is a natural-language Minecraft assistant you talk to in chat. You say things in plain
English ("come here", "clear a big area where I'm looking", "build me a tower"), and it interprets
your intent with the xAI Grok model and acts — with **godmode** (operator) powers so big edits are
instant. Distinct from Codex's swarm (Workstream B): this is *our* single, personal, extensible agent.

## Run

```sh
cd grok
npm install
cp .env.example .env          # paste your XAI_API_KEY
# server must be up (../server/run.sh) and the bot must be opped:
#   echo "op Grok" > ../server/console.in
node assistant.js
```

## Talk to it (natural language)

- **Move:** "come here" / "follow me", "stop", "go to 100 -40", "explore"
- **Teleport:** "teleport to me" (instant)
- **Godmode edits (needs op):**
  - "clear a big area where I'm looking" / "make a clearing here" (radius/height)
  - "flatten a 20 block area here" (clears + lays a floor)
  - "dig the block I'm looking at" / "mine the nearest oak log"
  - "build a tower 10 tall"
- **Ask questions (grounded in what it sees):** "where are you?", "what's nearby?",
  "what do you have?", "what can you do?"
- **Just chat.**

## How it works (today)

Single-shot reactive loop: each chat message → build a world-state snapshot (position, nearby
players/entities/resources, inventory, biome, **and the block you're looking at**) → **one** Grok
API call with a fixed action list → Grok returns `{reply, action, args}` → execute one action +
speak. Block edits use server commands (`/fill`, `/setblock`, `/tp`) via the opped bot, so they're
instant and reliable (no slow block-by-block digging).

## Warlord (enemy AI wave director)

`warlord.js` is a **separate** standalone agent — the ENEMY AI WARLORD brain for the
Tower-Defense mod (design block #17b). It is a cunning necromancer-general: each upcoming
wave it reads the live battlefield over the mod bridge, probes the **weakest flank** from
the tower distribution, composes an enemy army within the mod's threat **budget**, picks a
tactic, and snarls one in-character taunt — then submits the plan. It's **additive +
graceful**: the mod keeps its procedural scaling and falls back to default waves if the
Warlord is offline or errors, and it **clamps** any over-budget host.

The battlefield snapshot also includes the prior wave's S–D grade, clear time, closest
approach, Idol damage, hero HP lost/downs, dominance streak, and current/required player-chosen
spawn gates. The Warlord uses those signals to distinguish an effortless clear from a costly one;
the mod itself remains the bounded authority that scales pressure and pauses for a newly required
gate.

```sh
cd grok
node warlord.js                 # main loop: poll /td/battlefield, plan each new wave
node warlord.js --once          # one plan cycle then exit
node warlord.js --dry           # PRINT the plan instead of POSTing it (safe test)
node warlord.js --once --dry    # both
# or via the launcher (fills the bridge token from the server config):
scripts/run-warlord.sh --once --dry
```

**Env** (loaded from `grok/.env` like `assistant.js`):
- `ANTHROPIC_API_KEY` — required for the LLM call (provider `anthropic`).
- `WARLORD_MODEL` — Anthropic model (default `claude-opus-4-8`).
- `BUBBLESKY_BRIDGE_URL` — bridge base URL (default `http://127.0.0.1:25580`).
- `BUBBLESKY_BRIDGE_TOKEN` — `X-Bridge-Token`; if unset, read from
  `../server/config/bubblesky-bridge.json`.
- `WARLORD_POLL_MS` — battlefield poll interval (default `4000`).

Uses the live bridge endpoints `GET /td/battlefield`, `POST /td/waveplan`, `POST /td/taunt`
via `lib/bridge`, and `lib/llm` (Anthropic) for the single per-wave planning call. Rate-limited
to at most one LLM call per wave; occasional rare mid-wave taunts on notable events.

## Foreman (LLM colony director)

`foreman.js` is a **separate** standalone agent — the COLONY FOREMAN brain for the
Tower-Defense mod (design block #18b), and the **mirror of the Warlord**: same flank
intel, opposite intent. It reads the live colony + battlefield over the mod bridge,
finds the **weakest flank** (the side the Warlord is about to hit) from the tower
distribution, and sends 1–3 builders to **fortify that flank** — raising a cobble wall a
few blocks out from the Idol, spanning across the approach — while keeping the rest of the
crew gathering (mine/chop/forage/haul). If the last wave leaked a lot, it commits more
builders. It's **additive + graceful**: with no orders the colony runs on its rule-based
work brain, and it falls back to that brain if the Foreman is offline or errors. Speaks in
a gruff foreman voice via `/say`.

```sh
cd grok
node foreman.js                 # main loop: poll /td/colony + /td/battlefield, direct each wave
node foreman.js --once          # one direction cycle then exit
node foreman.js --dry           # PRINT the orders instead of POSTing them (safe test)
node foreman.js --once --dry    # both
# or via the launcher (fills the bridge token from the server config):
scripts/run-foreman.sh --once --dry
```

**Env** (loaded from `grok/.env` like `assistant.js`):
- `ANTHROPIC_API_KEY` — required for the LLM call (provider `anthropic`).
- `FOREMAN_MODEL` — Anthropic model (default `claude-opus-4-8`).
- `BUBBLESKY_BRIDGE_URL` — bridge base URL (default `http://127.0.0.1:25580`).
- `BUBBLESKY_BRIDGE_TOKEN` — `X-Bridge-Token`; if unset, read from
  `../server/config/bubblesky-bridge.json`.
- `FOREMAN_POLL_MS` — colony poll interval (default `5000`).

Uses the live bridge endpoints `GET /td/colony`, `GET /td/battlefield`,
`POST /td/colony/assign`, and `POST /say` via `lib/bridge`, and `lib/llm` (Anthropic) for
the single per-wave direction call. Rate-limited to at most one LLM call per wave (also
re-directs when the crew roster changes materially); with 0 colonists it logs and idles.

## Roadmap (making it smarter)

1. **Agentic loop** — plan → act → observe result → act again (multi-step tasks like
   "gather wood then build a house"), using native tool-calling.
2. **Reasoning model** — swap to `grok-4.20-reasoning` / 4.3 / 4.5 for real planning.
3. **Structure building** — load blueprints (reuse Codex's `mcp/blueprint-compiler.mjs` +
   `prismarine-schematic` `.schem` pipeline) and/or procedural generation → "build a castle here".
4. **Memory + task list** — pursue long goals across many messages.
5. **Self-verification** — check results, retry on failure.

## Requirements

- Fabric **1.21.6** server on `localhost:25565`, offline mode.
- The bot (`Grok`) **opped** for godmode commands.
- An **xAI API key** in `.env` (never commit it).
