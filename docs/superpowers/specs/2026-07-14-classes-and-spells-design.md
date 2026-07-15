# Classes, Two-Tier Progression & Spellcasting — Design

_Date: 2026-07-14 · Mod: `mods/towerdefense` (Fabric 1.21.6)_

Extends [2026-07-12-rpg-progression-design.md](2026-07-12-rpg-progression-design.md).
That system (7 global stats, XP→skill points, `PlayerProgress`/`ProgressState`, sync
payload, CharacterScreen) stays as the **global layer**. This adds a **per-class layer**,
**class selection on respawn**, and an **active spellcasting** system.

## Vision

Make the RPG stats *fun to spend into* and fuse the RPG with the swarm. A character is
who you are (global stats); a class is the loadout + a separate skill tree you grow per
class. You pick a class each life, keep your global character, and each class remembers
its own progress.

## Two-tier progression

**Global layer (persistent, shared across all classes) — already exists.**
- XP → global skill points → the 7 stats (Vitality … Intelligence/Resilience).
- Global modifiers: apply no matter which class is active. Untouched by class switching.

**Per-class layer (new, one independent track per class).**
- Each of the 4 classes has its own `{classXp, classLevel, classPoints, allocations{skill:points}}`.
- You level a class by **playing it**; switching classes preserves each class's own track.
- Class skill points buy into that class's **skill tree** (spell unlocks/ranks + passives).

**XP model (locked): every enemy kill grants BOTH**
- `+globalXp` (always → the 7-stat track), and
- `+classXp` to the **currently-active class only**.
- Wave-clear / boss bonuses feed both pools the same way.

## Classes (pick on respawn)

Four classes; each = a **stat bias**, a **spell loadout**, and a **swarm hook**:

| Class | Bias | Signature spells | Swarm hook |
|-------|------|------------------|------------|
| **Mage** | Intelligence | fireball, frost-nova, chain-lightning | — |
| **Ranger** | Marks/Agility | multishot, trap, summon-wolf | — |
| **Engineer** | Int/Fortune | deploy-turret, repair-pulse, wall-of-acid | commands **colonists** |
| **Warlord** | Str/Vit | war-cry, summon-squad, charge | commands **ally armies** |

- **Selection:** on respawn (and first spawn) a **class-pick screen** opens
  (reuses CharacterScreen infra; buttons per class showing that class's level). Chat
  fallback `/td class <name>`. Also `/td class` to reopen the picker.
- **On pick:** set `activeClass`, grant that class's **spell items** into dedicated
  hotbar slots (mirrors the existing build-materials-in-slots pattern), apply the class's
  per-life stat bias (a small modifier on top of global stats), and grant its gear (e.g.
  Mage staff / Ranger bow). Clears the previous class's granted spell items.

## Spellcasting (`spell/` package)

- **Cast input:** spells are **hotbar items** — right-click to cast the held spell.
  Reuses vanilla number-key switching (no client keybind mixins). Item cooldown overlay
  shows the spell cooldown. Each spell item is a `SpellItem` carrying a `SpellType`.
- **Mana:** a per-player pool `{mana, maxMana}` on `PlayerProgress`. `maxMana` and regen
  scale with **Intelligence** (finally an active role for Int beyond collection radius).
  Regen ticks server-side. Each spell has a `manaCost` + `cooldownTicks`; cast fails
  (with feedback) if insufficient mana or on cooldown.
- **Spell registry** (`spell/SpellType.java`, data-driven enum): each entry has id, mana
  cost, cooldown, and a `cast(ServerWorld, ServerPlayerEntity, aim)` effect. Starter set:
  - **fireball** — projectile, AoE fire damage.
  - **frost-nova** — PBAoE slow + light damage.
  - **chain-lightning** — bounces between nearby enemies.
  - **chain-heal** — heals self + nearby allies/colonists.
  - **summon-ally** — spawns a temporary `TdAllyEntity` (archer/footman) that despawns after N sec.
  - **wall-of-acid** — lays a short line of the new acid fluid across the aim direction.
  - **war-cry** — buffs nearby allies (Strength/speed) for a few seconds.
  - **deploy-turret / repair-pulse / multishot / trap / summon-wolf / summon-squad / charge** — per class kit.
- **Damage attribution:** spell damage is owned by the caster so it credits gold/XP via
  the existing kill hooks (same pattern as tower `damageAndCredit`).
- **HUD:** a **mana bar** added to `TdClientHud` (below/beside the XP+level line);
  `ProgressSyncPayload` extended with `mana`/`maxMana` and `activeClass`.

## Loot v1 (minimal)

- Enemies drop **essence** (a per-player currency, not inventory items — credited like
  gold via the vacuum). Spend in-life on temporary upgrades (buff active staff, +max
  mana, a temp stat point) at the Idol or via `/td essence`. Keeps loot simple; gear
  items can come later.

## Data model changes

- `PlayerProgress` gains: `mana`, `maxMana`, `activeClass` (enum, per-life), a
  `Map<PlayerClass, ClassProgress>` (each `{xp, level, points, allocations}`), and
  `essence`. NBT (de)serialization for all of it in `ProgressState`.
- `PlayerClass` enum (MAGE, RANGER, ENGINEER, WARLORD) + `ClassProgress` POJO.
- XP hook: on `td_enemy` death, award global XP (existing) **and** active-class XP.
- Respawn hook (`ServerPlayerEvents.AFTER_RESPAWN` / copy-from): open the class picker
  and (re)grant the active class loadout.

## Networking

- Extend `ProgressSyncPayload` (S2C) with `mana`, `maxMana`, `activeClass`, and the
  active class's `{level, xp, points}`.
- New C2S: `SelectClassPayload{class}` (validates, sets active class, grants loadout),
  `CastSpellPayload{spell, aim}` is **not** needed if casting is item-use server-side
  (right-click handled via `ServerPlayNetworking` item-use → simplest: handle cast in the
  `SpellItem.use` server side). Keep an `AllocateClassPointPayload{skill}` for class-tree
  allocation in the class screen.

## Build order (subagents)

1. **Foundation** — `PlayerClass`/`ClassProgress`, `PlayerProgress`+`ProgressState`
   extension (per-class tracks, mana, essence, activeClass), XP-split hook, respawn class
   picker (chat + minimal screen), loadout grant/clear. Sync payload extension.
2. **Casting** — `spell/` package (SpellType registry, SpellItem, mana pool + regen,
   cast effects for the starter set), HUD mana bar. Wall-of-acid uses the new fluid.
3. **Class trees + loot** — per-class skill trees (unlock/rank spells + passives) in the
   class screen, class stat biases, essence drops + spend sink. Wire Engineer→colonists /
   Warlord→armies command hooks (lightweight in this cut; deepen in the swarm block).

## Integration constraints / non-goals

- MUST NOT regress: existing 7-stat global progression, gold economy/bank, towers,
  waves, colony, acid fluid, starter kit + build materials.
- Additive: new `spell/` package, new enum/POJO, extended payload + state, new hotbar
  slots for spells. Global layer untouched.
- Non-goals this cut: full gear/itemization, respec of global stats, PvP, balancing pass
  (tuning constants exposed but not finalized).
