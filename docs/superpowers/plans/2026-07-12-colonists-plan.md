# Colonists — Implementation Plan (v1)

_Spec: ../specs/2026-07-12-colonists-design.md · Survey concluded: clone the existing ally system._

Architecture: **mod-side authoritative** (ColonistEntity + rule-based priority AI + `/colony`
command), with an **optional agent-side "colony brain"** (Node, reuses `grok/lib/bridge.js` +
`grok/lib/llm.js` + the `grok/agents/agent.js` decide-loop) that only sets priorities over the
bridge — built LATER, colonists never depend on it. Stay out of Codex's `mcp/` swarm lane.

All new code in `mods/towerdefense`, package `net.bubblesky.towerdefense.colony`.

## Build steps (mod-side v1)

1. **`ColonistEntity extends PathAwareEntity`** — clone `entity/TdAllyEntity.java`. Replace its
   `enum Order` with `enum Job { MINE, CHOP, HUNT, FORAGE, HAUL, IDLE }` plus a **priority list**
   (ordered `Job[]`, default HAUL>MINE>CHOP>FORAGE>HUNT). Add a `SimpleInventory` (~9 slots), a
   home-anchor `BlockPos`, and an owner UUID. NBT-persist job/priorities/home/inventory via
   `writeCustomData`/`readCustomData`; `setPersistent()`; name from a pool; name tag shows the
   task. Register in `registry/ModEntities.java` (copy the footman/ally registration + attrs).

2. **Rule-based work AI** — new `ColonyWorkGoal` (clone the structure of `entity/AllyOrderGoal.java`).
   Each decision: pick the highest-priority Job with an available target within the home radius,
   then execute:
   - **MINE `<ore>`** — scan for nearest matching ore near home; navigate; break over time (a
     break-progress timer + `swingHand` + `BlockStateParticleEffect` crack, mirroring
     `WaveManager.digTowardBase`); on break, add the drop item to the colonist inventory. When
     full → HAUL.
   - **CHOP** — nearest log → fell the connected logs → collect → HAUL.
   - **HUNT** — nearest `AnimalEntity` → melee until dead → pick up drops → HAUL.
   - **FORAGE** — nearby `ItemEntity` drops (and ripe crops if cheap) → pick up → HAUL.
   - **HAUL** — path to nearest home chest → insert inventory into its container → resume gather.
   - **IDLE** — wander near the home anchor.
   NEW vs allies: breaking blocks, holding a `SimpleInventory`, and depositing into a chest's
   `Inventory` — implement these carefully (server-thread; `world.breakBlock`, pick up `ItemEntity`,
   `Inventories`/`HopperBlockEntity.transfer`-style insert into the chest block entity).

3. **`/colony` Brigadier command** — clone `command/TdCommand.java` register/permission pattern:
   - `/colony flag` — set the colony **home flag** at the player (store in a `ColonyState`
     `PersistentState`, or reuse `LayoutStore`) and drop a visible marker (mirror
     `game/TdMarkers.java`). Colonists bind to the nearest colony flag.
   - `/colony recruit` — cost gold (reuse `TdCommand.countCoins`/`removeCoins`, ~50); spawn a named
     colonist at the flag (copy `TdCommand.hire()`'s `type.spawn(world,pos,SpawnReason.EVENT)`),
     bound to the colony; enforce a population cap (copy `MAX_ALLIES`).
   - `/colony order <name|all> <job>` and `/colony prioritize <name|all> <job>` — set job / bump a
     work type to top priority.
   - `/colony status` — list colonists + current jobs (emit parseable feedback lines).

4. **Chat commands** — `ServerMessageEvents.CHAT_MESSAGE` listener parsing
   `"<name|colonists|all> <verb> [target]"` (verb ∈ mine/chop/hunt/forage/haul/come/stop/idle,
   `prioritize <work>`). Resolve the deposit chest from the speaker's looked-at (server raycast ≤6)
   or nearest home chest. Message still shows in chat. Route to the same order/prioritize logic as
   `/colony`.

5. **Registration / assets** — `ModEntities` (COLONIST type + attributes), `TowerDefenseModClient`
   (bind the biped renderer with `textures/entity/colonist.png` — recolor an existing skin),
   `TowerDefenseMod.onInitialize` (register `/colony` + the chat listener), `en_us.json`
   (entity name + command strings).

6. **(Stretch) Colony roster menu** — a Colonist entry in the **H** hire screen to recruit, and a
   roster listing colonists with an editable priority list.

## Priorities

MUST: ColonistEntity + skin + home flag + **MINE + HAUL** end-to-end + job priorities +
`/colony recruit` (gold) + chat commands + registration. SHOULD: CHOP + HUNT + FORAGE.
NICE: roster menu.

## Deploy

Mod-side → batches into a server restart via `scripts/deploy-play.sh` (then refresh the client jar).
The agent-side colony brain is a separate later phase (no restart needed for it).
