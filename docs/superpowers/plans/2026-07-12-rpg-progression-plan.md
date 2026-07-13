# RPG Progression — Implementation Plan

_Spec: ../specs/2026-07-12-rpg-progression-design.md_

All in `mods/towerdefense`, package `net.bubblesky.towerdefense`. Additive only.

## Steps

1. **`progression/PlayerProgress.java`** — fields `xp, level, unspentPoints, EnumMap<Stat,Integer> allocations`.
   Methods: `addXp(int)` (rolls level-ups, granting a point each), `xpForLevel(n)`,
   `allocate(Stat)` (guard on unspentPoints), `writeNbt/readNbt`. `enum Stat { VITALITY,
   STRENGTH, AGILITY, MARKSMANSHIP, FORTUNE }`.

2. **`progression/ProgressState.java`** — `PersistentState` on the overworld storing
   `Map<UUID,PlayerProgress>`. `static get(server)` + `forPlayer(uuid)` get-or-create.
   Mark dirty on change.

3. **`progression/StatModifiers.java`** — `apply(ServerPlayerEntity)` sets attribute
   modifiers for VITALITY (max_health +2/pt), STRENGTH (attack_damage +0.5/pt), AGILITY
   (movement_speed +2%/pt) via stable per-stat modifier IDs (remove-then-add so relog
   doesn't stack). Helpers `bowMult(progress)` (+6%/pt) and `coinMult(progress)` (+8%/pt)
   for use-time reads.

4. **XP hooks** — in a `progression/ProgressEvents.register()` called from mod init:
   - `ServerLivingEntityEvents.AFTER_DEATH`: if dead entity has `td_enemy` tag, award XP
     to the killer if a player (share a fraction to players within reward radius); boss
     tag → bonus. Compute XP from the enemy's max-health/wave.
   - On player join/respawn/world-change: `StatModifiers.apply` + send `ProgressSyncPayload`.

5. **Networking** — `progression/net/AllocatePointPayload` (C2S, `{Stat}`) and
   `ProgressSyncPayload` (S2C snapshot). Register codecs + receivers in mod init. Server
   receiver: validate points, `allocate`, `StatModifiers.apply`, save, resync.

6. **Coin/bow multipliers** — apply `coinMult` where wave/kill coins are paid (a shared
   helper the payout path can call) and `bowMult` where the player's fired arrow damage is
   set. Keep edits minimal and localized; if a payout site is owned by other code, prefer a
   post-hoc multiplier hook over rewriting it.

7. **Client** — `client/screen/CharacterScreen` (level, XP bar, unspent points, `+` per
   stat → send AllocatePointPayload); cache the last `ProgressSyncPayload`. Register
   keybinds in `TowerDefenseModClient`: `P` → open CharacterScreen, `I` → open vanilla
   inventory (`client.setScreen(new InventoryScreen(player))`). Optional HUD line (level +
   XP) alongside the existing HUD.

8. **Lang** — add `key.towerdefense.open_character`, `key.towerdefense.open_inventory`,
   and `screen.towerdefense.character.*` + stat names to `en_us.json`.

9. Build to green (`./gradlew build`), verify no regression to kit/economy/waves.

## Conflict note

Touches `TowerDefenseModClient.java`, `en_us.json`, and mod init (`TowerDefenseMod.java`)
— dispatch ONLY after the enemies/waves lane finishes to avoid concurrent edits.
