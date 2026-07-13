# Colonists — Design (v1, RimWorld-lite)

_Date: 2026-07-12 · Mod: `mods/towerdefense` (Fabric 1.21.6) · package `net.bubblesky.towerdefense.colony`_

## Vision

A **colony layer** inspired by RimWorld (simpler for now): you plant a **home flag**, recruit
named humanoid **colonists** (buy with gold), and they **autonomously do useful work** around
home based on **job priorities** — mine ore, chop wood, hunt animals, forage — hauling the
yield to nearby chests. No teleporting; they physically walk and work. You steer them by
setting priorities / giving orders in the **colony menu** and by **name in text chat**
(*"Alden, prioritize mining"*, *"Bram chop wood"*, *"colonists forage"*).

Later a lightweight **LLM layer** makes the "what should I do next?" call for each colonist
(forage/craft/explore) by issuing the same job orders over the agent bridge — but v1 runs on
robust **rule-based priority AI** so it works with no LLM in the loop.

## Home flag (the colony anchor)

- A placeable **Colony Flag** (a small block, or reuse the Layout flag store) marks a colony.
- Everything within a **home radius** (~28 blocks) of the flag is "home": colonists idle/return
  there, and the mod discovers nearby **workstations** — **chests** (stockpile / drop-off),
  **crafting tables**, **furnaces** — by scanning around the flag. Gathered goods are hauled to
  the nearest home chest.
- Multiple flags = multiple colonies later; v1 assumes one active colony (nearest flag).

## Colonist entity

- **`ColonistEntity extends PathAwareEntity`** (same family as `TdAllyEntity`), humanoid biped +
  a **colonist skin** (`textures/entity/colonist.png`), **named** from a pool, name tag shows
  current task (*"⛏ Alden — mining iron"*), **persistent** (job/priorities/home/inventory in NBT),
  carries a small **`SimpleInventory`** (~9 slots), bound to a home flag.

## Job priorities (RimWorld-lite)

- Work types: **MINE, CHOP, HUNT, FORAGE, HAUL, IDLE**.
- Each colonist holds a simple **priority ordering** over work types (default: Haul > Mine >
  Chop > Forage > Hunt, editable). Each tick-of-decision the colonist picks the **highest-priority
  work that has an available target near home**; if none, it idles/wanders home.
- Autonomous loop (rule-based AI goals we fully control):
  - **MINE** — nearest ore in home radius → walk → break over time (progress timer + swing +
    crack particles) → collect drop → when full, HAUL.
  - **CHOP** — nearest log → fell the tree → collect → HAUL when full.
  - **HUNT** — nearest animal → walk + attack until dead → pick up drops → HAUL when full.
  - **FORAGE** — nearby loose item drops / harvestable plants → pick up → HAUL when full.
  - **HAUL** — deposit carried items into the nearest home chest; if full, idle at it; then
    resume the top-priority gather job.
  - **IDLE** — wander near the home flag.

## Recruit (buy with gold)

- `/colony recruit` and a **Colonist** button in the **H** recruit menu — costs gold (reuse
  `TdCommand` `countCoins`/`removeCoins`, ~50). Spawns a named colonist at the player, bound to
  the nearest home flag (or an error if none planted yet).

## Commanding

1. **Chat by name** — `ServerMessageEvents.CHAT_MESSAGE` parses
   `"<name|colonists|all> <verb> [target]"`, verb ∈ mine/chop/hunt/forage/haul/come/stop/idle,
   plus `"<name> prioritize <work>"` to bump a work type to the top of that colonist's priorities.
   Case-insensitive; `colonists`/`all` addresses the colony. The message still shows in chat.
2. **Colony menu** — a roster screen: each colonist with editable **priority list** + current
   task + home flag. (Stretch for v1; chat + priorities is the MVP.)

## Bridge hook (for the future LLM layer)

Expose colony state + orders so an agent-side brain can drive it later: a `/colony ...` command
surface (assign job, set priority, forage/craft) that the **agent bridge** `/command` can call.
No LLM in v1 — just make the orders scriptable so the LLM layer plugs in without mod changes.

## Registration / integration

`ModEntities` (COLONIST type + attributes), `TowerDefenseModClient` (renderer bind), colony flag
block + item (`ModBlocks`/`ModItems`), `TowerDefenseMod` (chat listener + `/colony` command),
`en_us.json`, colonist skin. Additive — must not regress towers/waves/RPG/allies/economy.

## Non-goals (v1)

Crafting recipes/automation, building, schedules, health/needs/mood, per-player ownership, the
LLM decision layer (designed-for, not built), colony HUD. All later.

## Priorities (if time-bound)

MUST: colony flag + home discovery, ColonistEntity + skin, **MINE + HAUL** end-to-end, job
priorities, recruit-with-gold, chat commands (prioritize/mine/come/stop), registration.
SHOULD: CHOP + HUNT + FORAGE. NICE: colony roster menu.
