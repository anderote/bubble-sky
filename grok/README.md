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
