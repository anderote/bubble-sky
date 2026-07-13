# Tower Defense Content Update — Design

**Date:** 2026-07-12
**Mod:** `mods/towerdefense/` (Fabric 1.21.6, Java 21, client+server)
**Status:** Design — pending user review

This update bundles four independent features into one content pass on the tower-defense mod:

1. **"My Towers" panel** — a custom textured GUI listing your placed towers, with per-tower stats, upgrade, and sell.
2. **Wave-completion rewards** — loot drops at an enemy spawn gate each time a wave is cleared, scaling with wave number.
3. **Two new tower types** — a *Sharpshooter* (armor-piercing/crit boss-killer) and a *Flamethrower* (spreading-fire crowd control).
4. **Turret veterancy** — towers earn XP from kills, ranking up through 10 veterancy levels that scale their stats up to ~2.5×.

Each feature is self-contained; they share only the existing mod infrastructure. They can be implemented and tested in any order.

---

## Feature 1 — "My Towers" panel

### Purpose
Give each player a single screen that shows the towers **they** have placed, so they can review live stats and manage each tower (upgrade / sell) without having to physically walk up to it and aim.

### Entry point
- New keybind, default **`K`** (`key.towerdefense.open_towers`, category `key.categories.towerdefense`), registered alongside the existing J/H/P/I binds in `client/TowerDefenseModClient.java`.
- Command fallback `/td towers` for parity with the rest of the `/td` surface.
- Opens `client/screen/MyTowersScreen` (client `Screen`, modeled on `client/screen/CharacterScreen.java`).

### Data model — tower tracking (new)
Towers are currently not tracked anywhere central — each is just a placed block + block entity. We add:

- **`TowerRegistry`** — a server-side `PersistentState` (same mechanism as `progression/ProgressState`), stored on the overworld. Maps `owner UUID → Set<BlockPos>`.
  - **Register** a tower on placement — hook the code path where `placer` is set / where `TowerStructure` raises the tower.
  - **Unregister** on block break and on sell.
  - **Self-heal on read:** when building a player's roster, drop any tracked position that no longer holds that player's tower block entity (covers waves/griefing/`/setblock` removals). Keeps the registry authoritative without needing to catch every removal path.
- **`invested` field on `AbstractTowerBlockEntity`** — new persisted int (NBT key `"invested"`), read/written in `read/writeData` next to `tier`/`placer`. Set to the base price on purchase; each upgrade adds its cost. Used to compute the sell refund.

### Screen layout
Two-pane custom screen (not a chest/container GUI). Rendered with GUI primitives (panels via `context.fill` + borders, matching `CharacterScreen`'s hand-drawn style) plus each tower's existing item texture rendered as an icon. A polished PNG background (`assets/towerdefense/textures/gui/my_towers.png`) is an optional later polish; the initial implementation is fully functional with drawn panels.

```
╭─────────────  My Towers  ──────────────╮   coins: 63🪙
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐            │  ╭─ Frost Tower ─────────╮
│  │🏹L2│ │💣L1│ │❄ L3│ │🔵L1│            │  │ Tier 3 · MAX          │
│  └★★──┘ └────┘ └MAX─┘ └────┘            │  │ Veterancy ★7  (2.0×)  │
│  ┌────┐                                 │  │ 412/560 kills → ★8    │
│  │🏹L1│   ← click an icon to select     │  │ Range 9  Dmg ×2.0     │
│  └────┘                                 │  │ Cooldown 0.6s         │
│                                         │  │ [ Upgrade — MAX ]     │
│                                         │  │ [ Sell — +18🪙 ]      │
╰─────────────────────────────────────────╯  ╰───────────────────────╯
```

- **Left:** scrollable grid of your placed towers; each icon = tower-type texture + a tier badge (or `MAX`) and a small veterancy indicator (e.g. star pips / rank number).
- **Right:** detail pane for the selected tower — kind, **veterancy level (0–10) + its stat multiplier and XP-to-next-rank**, live stats (tier, range, damage multiplier, cooldown in seconds — reflecting both tier and veterancy), an **Upgrade** button (shows next-tier cost; disabled at MAX or if unaffordable) and a **Sell** button (shows refund). Your coin balance shown top-right.

### Networking (reuse the `progression.net` pattern)
- **S2C `TowerRosterPayload`** — sent on open and after every action. Carries the player's coin count plus, per owned tower, `{posId, kind, tier, range, cooldown, dmgMult, upgradeCost, refund, isMaxed, vetLevel, vetXp, vetXpToNext, vetMult, kills}`. `posId` is the encoded `BlockPos` long.
- **C2S `TowerActionPayload`** — `{posId, action ∈ {UPGRADE, SELL}}`. Server re-validates ownership + affordability + max-tier, mutates world/registry, then pushes a fresh `TowerRosterPayload`.
- **Refactor:** extract the existing `TdCommand.upgrade()` logic (`command/TdCommand.java:327-358`) into a shared `TowerService.upgradeTower(world, player, pos)` called by both the `/td upgrade` command and the UI. Add `TowerService.sellTower(world, player, pos)`.

### Economics
- **Upgrade:** unchanged from today — cost = base tower price × current tier, max tier 3.
- **Sell:** refund = **floor(50% of `invested`)**. Example: Frost bought at 20, upgraded twice (20 + 40) → invested 80 → refund **40🪙**. Selling removes the tower structure (`TowerStructure` teardown), unregisters it, and drops the refund coins to the player.

### Testing
Unit-test the pure logic: `TowerRegistry` add/remove/prune, invested/refund math, upgrade validation. Manual in-game pass for the screen, keybind, and networking round-trip.

---

## Feature 2 — Wave-completion rewards

### Purpose
Reward clearing a wave with a bundle of useful items (materials, food, equipment) that grows as waves get bigger, dropped at an enemy spawn gate.

### Hook
In `game/WaveManager.java`, the wave-cleared branch of `tickActive()` (`:396-409`) — the same place that already pays coins via `payNearbyPlayers`. After the existing payout, call `WaveRewards.dropWaveReward(world, st)`.

### Reward model — curated bands + randomness
`WaveRewards` defines **wave bands** keyed off `st.currentWave`, each with a curated item pool. On wave clear, pick a **random subset of 3–5 entries** from the current band; per-item counts scale gently with wave number. Illustrative bands (exact contents tunable via `static final` constants near the top of the class, per existing convention):

| Band | Waves | Example pool |
|------|-------|--------------|
| 1 | 1–4 | bread, cooked meat, oak/cobble, torches, arrows |
| 2 | 5–9 | steak, iron ingots, a random iron gear piece, coal, apples |
| 3 | 10–14 | diamonds (few), a diamond gear piece, golden apples, redstone, ender pearls |
| 4 | 15+ | enchanted gear, netherite scrap, golden apples, XP-ish consumables |

- **Boss waves** (every 5th, `BOSS_WAVE_INTERVAL`): larger haul — more entries drawn + one guaranteed premium item from the band.
- All tuning (band cutoffs, subset size, count-scaling factor, boss multiplier) lives in named `static final` constants at the top of `WaveRewards`, matching the mod's no-external-config convention.

### Drop mechanics
- Drop at **one random spawn gate per wave**: pick a random `BlockPos` from `st.spawnPoints` and snap it to the surface with the existing `surfaceSpawn(...)` helper (`WaveManager.java:522`).
- Spawn each item as a loose `ItemEntity` (copy the idiom at `WaveManager.java:790-793`: `new ItemEntity` + `setToDefaultPickupDelay()` + small random velocity for scatter + `world.spawnEntity`).
- Broadcast a short message on clear, e.g. *"Wave 7 cleared — rewards dropped at a spawn gate!"* (optionally with the gate's coords), consistent with the existing wave-clear broadcast + `TdFeedback.waveClear`.

### Scope note
The arena is shared co-op (one `TdArenaState`, all players within `REWARD_RADIUS` rewarded, coins are unowned world pickups). Wave-reward loot is therefore a **shared pile** — the first players to reach the gate grab it — which is consistent with the game's existing coin model.

### Testing
Unit-test `WaveRewards` band selection + count scaling (deterministic given a seeded RNG). Manual in-game pass to confirm drops land on the surface at a gate and are pickup-able.

---

## Feature 3 — Two new tower types

Both follow the existing recipe: a `TowerKind` enum entry (core `Block`, tower-arrow `Item`, pole/ball palette), a `*TowerBlock`, a `*TowerBlockEntity extends AbstractTowerBlockEntity` supplying `baseRange`/`baseCooldown`/`fire`, registration in `ModBlocks`/`ModBlockEntities`/`ModItems`/`ModItemGroups`, models/blockstates/textures, and a price + shop entry in `TdCommand.catalogue()`. Because the J-menu shop and the new My-Towers panel are catalogue/registry-driven, both new towers appear in them automatically. All kills must attribute damage to the tower's `placer` so coin bounties pay the owner, matching existing towers.

### Sharpshooter — armor-piercing / crit boss-killer
- **Niche:** long range, slow fire, deals **true damage that ignores wave stat-scaling**, with a crit chance. Prioritizes the **toughest** enemy in range (highest max HP). The answer to high-HP late-wave enemies and bosses.
- **Stats:** `baseRange` large (≈16, tier scaling → ~20), `baseCooldown` slow (≈60 ticks). Damage = a flat base × `damageMultiplier(tier)` **plus a percentage of the target's max HP** (so it stays relevant as enemy HP scales ~30× by wave 100). Crit chance (≈25%) doubles the hit. Damage is applied so it is not reduced by wave scaling (percent-max-HP component guarantees this).
- **Targeting:** among enemies in range, pick the one with the highest max HP (fallback highest current HP).
- **Feel:** hitscan/instant shot (model on `ArrowTowerBlockEntity.fire()` but instant) with a distinct crit visual/sound.
- **Price:** premium (≈45 coins). Palette: distinct pole + a dark/emerald ball.

### Flamethrower — spreading flames
- **Niche:** short range, cheaper, applies **burning DoT** to a target and the fire **spreads to nearby enemies** (contagion). Excels against tight packs; weak against lone tanks.
- **Stats:** `baseRange` short (≈5), `baseCooldown` medium-fast (≈20 ticks). On fire: ignite the chosen target (burn DoT scaling with `damageMultiplier(tier)`), then ignite every enemy within a small spread radius (≈3 blocks, growing slightly with tier) of that target.
- **Burn mechanic:** apply vanilla fire (`setOnFireFor`) for the visual plus periodic bonus damage attributed to the `placer` (so DoT kills credit coins to the owner). Burn continues ticking after enemies leave the tower's range.
- **Feel:** flame particles + fire sound along the short cone/target.
- **Price:** moderate (≈30 coins). Palette: distinct pole + an orange/red ball.

### Testing
Unit-test the damage math where feasible (sharpshooter percent-max-HP + crit resolution; flamethrower spread-target selection). Manual in-game pass: sharpshooter one-shots/tanks bosses as intended and ignores scaling; flamethrower ignites and visibly spreads through a pack; both credit coins to the placer; both appear in the shop and My-Towers panel.

---

## Feature 4 — Turret veterancy

### Purpose
Give every tower a long-tail, earned progression that runs parallel to the coin-bought tier upgrades: towers accumulate kills, gain XP, and rank up through **10 veterancy levels**, becoming substantially stronger the longer they survive and perform. This rewards keeping towers alive across many waves and makes individual towers feel like they have history.

### Model
Veterancy is **earned** (via kills), separate and independent from **tier** (bought with coins). Both multipliers stack.

- **State on `AbstractTowerBlockEntity`** (new persisted fields, NBT keys `"kills"` and `"vetXp"`):
  - `kills` — total kills credited to this tower (display/stat).
  - `vetXp` — accumulated veterancy XP.
  - `vetLevel` (0–10) is **derived** from `vetXp` against a threshold table (not stored separately, to avoid drift).
- **XP source:** each kill credited to the tower grants XP. Base 1 XP per kill, optionally weighted by enemy strength (e.g. bosses worth more) so late waves advance veterancy faster than trivial early ones. (Weighting factor is a tunable constant; default: `xp = 1 + bossBonus`.)
- **XP curve (long grind):** cumulative thresholds grow geometrically so level 10 takes hundreds of kills. Defined as `static final` constants (a 10-entry table). Illustrative cumulative kills-to-rank: ★1≈5, ★2≈15, ★3≈35, ★5≈100, ★7≈260, ★10≈600+. Exact numbers tunable in-class.

### Stat effect
A **veterancy multiplier** `vetMult(level)` scales from `1.0` at level 0 to **~2.5× at level 10** (≈ +0.15 per level), applied primarily to **damage**, on top of the tier `damageMultiplier`. Secondary, gentler gains: range grows modestly (up to +2–3 blocks at ★10) and cooldown shrinks modestly (down to ~-20% at ★10). Net effect at max veterancy ≈ the "2–3× the stats" target.

Applied in the block entity's existing derived-stat methods:
- `damage` → `base × tierDamageMult × vetMult(level)`
- `range()` → existing tier range `+ vetRangeBonus(level)`
- `cooldownTicks()` → existing tier cooldown `× vetCooldownFactor(level)`

This composes cleanly with the new towers: the Sharpshooter's percent-max-HP + crit and the Flamethrower's burn DoT all scale through the same `vetMult`.

### Kill attribution to a tower
Kills currently credit the **placer** (a player) for coins; veterancy additionally needs to credit the **tower** that dealt the killing blow. Add lightweight source-tower tagging:
- Tag each tower projectile/shot with its origin tower `BlockPos` (alongside the existing placer ownership). For the Sharpshooter's hitscan and the Flamethrower's burn DoT, carry the same source-tower tag on the damage.
- On enemy death (reuse the existing `AFTER_DEATH` hooks in `TowerDefenseMod`/`WaveManager`), if the killing damage traces to a tower, increment that tower's `kills`/`vetXp` and re-derive `vetLevel`. Guard against the tower having been removed.

### Feedback
- **Rank-up:** when a tower crosses a veterancy threshold, play a particle burst + sound at the tower and (optionally) a brief broadcast to the owner. 
- **Display:** veterancy level, multiplier, and XP-to-next surface in the My Towers panel (Feature 1). In-world nameplate/hologram display is out of scope for now (panel-only), consistent with the earlier UI decisions.

### Interaction with sell
Selling a tower forfeits its accumulated veterancy (refund is still based only on coins invested). This is intended — veterancy is a reason **not** to sell a seasoned tower.

### Testing
Unit-test the pure logic: `vetLevel` derivation from `vetXp` against the threshold table, `vetMult`/range/cooldown mappings at each level, and XP accrual/weighting. Manual in-game pass: confirm kills credit the correct tower, ranks advance, stats visibly increase, and rank-up feedback fires.

---

## Out of scope (YAGNI)
- Tower locate/teleport, rename/labels (explicitly deferred).
- Reward chests/crates and per-player reward instancing (loose shared drops chosen).
- Roster "all towers"/team view and toggle (personal roster only).
- In-world veterancy nameplates/holograms (panel display only).
- External JSON config (mod uses in-class constants throughout).

## Implementation order (suggested)
1. New tower types (self-contained, immediately playable, and they populate the shop/panel).
2. Turret veterancy (touches `AbstractTowerBlockEntity` stat methods + kill attribution; best done before the panel so the panel can surface it).
3. Wave-completion rewards (localized to `WaveManager` + one new class).
4. My Towers panel (largest surface: registry + networking + screen; displays tier + veterancy).
