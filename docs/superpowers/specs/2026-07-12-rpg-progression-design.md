# RPG Progression — Design

_Date: 2026-07-12 · Mod: `mods/towerdefense` (Fabric 1.21.6)_

## Goal

Turn the survival tower-defense into a light RPG: players earn XP from combat, level
up, and spend skill points on permanent stat upgrades. A character grows across many
games (not per-match). Two new keybinds: `I` = inventory, `P` = character screen.

## Player-facing model

- **XP** — earned per enemy kill, scaled by the enemy's wave/tier; bonus XP on
  wave-clear and Warlord kills. Custom XP (independent of vanilla experience levels).
- **Levels** — XP thresholds level the player up. Each level grants **1 skill point**.
  Threshold curve: `xpForLevel(n) = 50 + 25*(n-1) + 4*(n-1)^2` (gentle, super-linear).
- **Skill points → stats** (each point = one step; applied as attribute modifiers):
  | Stat | Attribute | Per-point step | Notes |
  |------|-----------|----------------|-------|
  | Vitality | `generic.max_health` | +2 HP (1 heart) | |
  | Strength | `generic.attack_damage` | +0.5 | melee |
  | Agility | `generic.movement_speed` | +2% | small, capped by point budget |
  | Marksmanship | (custom bow-damage multiplier) | +6% arrow damage | applied to fired arrows |
  | Fortune | (custom coin multiplier) | +8% gold from kills/waves | |
- **Persistence** — permanent. Per-player `{xp, level, unspentPoints, allocations{stat:points}}`
  stored in persistent player data; reapplied on join/respawn/dimension change.
- **Death** — normal survival (drop items, respawn); RPG level/stats are retained.

## Architecture

- **`progression/PlayerProgress`** — POJO holding xp/level/points/allocations, with
  `addXp`, `levelUp`, `allocate(stat)`, serialize/deserialize (NBT).
- **`progression/ProgressState`** — server-side store keyed by player UUID, persisted
  via `PersistentState` on the overworld (survives restarts; simplest reliable path on
  1.21.6). Accessors get-or-create per player.
- **XP hooks** — own `ServerLivingEntityEvents.AFTER_DEATH` listener (separate from
  WaveManager's boss-bounty listener): if the dead entity is a `td_enemy`, award XP to
  the killer (and nearby players share a fraction). Wave-clear/boss bonus XP awarded
  from a small hook the manager already broadcasts on (kept decoupled — read tags, don't
  edit WaveManager).
- **Attribute application** — `progression/StatModifiers.apply(player)` adds/refreshes
  `EntityAttributeModifier`s keyed by stable UUIDs per stat (replace-on-reapply so they
  don't stack across relogs). Bow-damage and coin multipliers are read from
  `PlayerProgress` at fire/payout time rather than via an attribute.
- **Networking** — one C2S payload `AllocatePointPayload{stat}` (server validates points
  available, applies, saves) and one S2C payload `ProgressSyncPayload{xp,level,points,allocations}`
  to feed the HUD/character screen. Registered in mod init.
- **UI** — `client/screen/CharacterScreen` shows level, XP bar, unspent points, and a
  `+` button per stat (sends `AllocatePointPayload`). `P` opens it; `I` opens the vanilla
  inventory screen. Keybinds registered in `TowerDefenseModClient`. An optional always-on
  HUD line (level + XP bar) can piggyback on the existing HUD.

## Scope / non-goals (first cut)

- No respec (can add a `/td respec` or a button later).
- No new gear tiers or spell trees yet (Marksmanship/Strength scale existing weapons).
- Bow-damage & Fortune are multipliers read at use-time (no custom attributes needed).

## Integration constraints

- Must NOT regress: survival kit + 100 starter gold, gold economy (coin items),
  wall-breaking, ally combat, marathon waves. New code is additive (new package +
  keybinds + payloads + one AFTER_DEATH listener).
