# Classes, Two-Tier Progression & Spellcasting — Plan

Implements [2026-07-14-classes-and-spells-design.md](../specs/2026-07-14-classes-and-spells-design.md).
Three subagent phases; build green + run dev-cycle after each (or batch 1+2).

## Phase 1 — Progression foundation
- `progression/PlayerClass.java` (enum MAGE/RANGER/ENGINEER/WARLORD + display/id/bias).
- `progression/ClassProgress.java` (POJO: xp, level, points, allocations map; xpForLevel curve; addXp/levelUp/allocate; NBT).
- Extend `PlayerProgress`: `mana`, `maxMana`, `essence`, `activeClass`, `Map<PlayerClass,ClassProgress>`; getters/mutators.
- Extend `ProgressState` NBT read/write for the above.
- XP-split: in the existing `td_enemy` AFTER_DEATH hook, award active-class XP alongside global XP.
- Respawn/first-spawn: `ServerPlayerEvents.AFTER_RESPAWN` → open class picker; `/td class [name]` command.
- Loadout grant/clear: on class select, set activeClass, grant class gear + spell items to dedicated hotbar slots, clear prior class's spell items, apply per-life stat bias.
- Extend `net/ProgressSyncPayload` (+ client `ClientProgress`) with mana/maxMana/activeClass/active-class level+xp+points.
- `SelectClassPayload` (C2S). Minimal class-pick screen (buttons) reusing CharacterScreen infra; chat fallback works without the screen.

## Phase 2 — Casting
- `spell/SpellType.java` (enum/registry: id, manaCost, cooldownTicks, cast fn).
- `spell/SpellItem.java` (holds SpellType; server-side `use` casts; sets item cooldown).
- Register spell items in `ModItems`; models/lang.
- Mana pool + regen (Int-scaled) ticked in `ProgressEvents`.
- Cast effects for starter set (fireball, frost-nova, chain-lightning, chain-heal, summon-ally, wall-of-acid, war-cry, + class kit). Damage attributed to caster for gold/XP credit.
- HUD mana bar in `TdClientHud`.

## Phase 3 — Class trees + loot
- Per-class skill trees (unlock/rank spells + passives) in the class screen; `AllocateClassPointPayload`.
- Class stat biases finalized.
- Essence drops (credited via vacuum like gold) + spend sink (`/td essence`, Idol UI): temp buffs (+max mana, staff buff, temp stat point).
- Lightweight swarm command hooks: Engineer→colonists, Warlord→armies (deepened later in the swarm block).

## Verify each phase
Build green → commit+push (rebase) → deploy-play.sh (bg) → sync launcher jar → confirm Grok connection → smoke-test in-game notes for the user.
