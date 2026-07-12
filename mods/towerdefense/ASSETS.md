# Tower Defense — Asset Provenance

## What ships in this mod right now

All textures currently bundled in `src/main/resources/assets/towerdefense/textures/`
are **original placeholder pixel-art**, generated procedurally (see
`scratchpad/gen_textures.py` used at build time). They are simple 16x16 icons
made by this project — no third-party assets are copied in. They are intended to
be swapped for higher-fidelity CC0/MIT art (see below) without any code changes,
since the file names/paths are stable:

| File | Depicts |
|------|---------|
| `textures/item/spear.png` | wooden shaft + steel spear head |
| `textures/item/mace.png` | spiked mace |
| `textures/item/war_hammer.png` | blocky war hammer |
| `textures/item/coin.png` | gold coin |
| `textures/block/arrow_tower_side.png` | stone-brick tower wall with arrow slit |
| `textures/block/arrow_tower_top.png` | battlement top with firing hole |
| `textures/entity/goblin_skirmisher.png` | green biped skin (fast swarm) |
| `textures/entity/footman.png` | leather-brown biped skin (baseline melee) |
| `textures/entity/archer.png` | green-tunic biped skin (ranged) |
| `textures/entity/man_at_arms.png` | mail-grey biped skin (sturdy melee) |
| `textures/entity/undead_soldier.png` | tattered grey-green biped skin (tanky) |
| `textures/entity/heavy_knight.png` | steel-plate biped skin (armored bruiser) |

### Enemy roster skins (Path A — reskinned vanilla biped)

The six `textures/entity/*.png` are **original 64x64 placeholder skins**, generated
procedurally by `scratchpad/gen_enemy_skins.py`. They paint only the BASE-layer
UV islands of the standard humanoid skin (head / body / arms / legs) and leave the
2nd-layer (hat/jacket/sleeve) regions transparent, so the plain
`BipedEntityModel` on `EntityModelLayers.PLAYER` renders a clean solid-colored
figure with no overlay doubling. Each enemy is just a flat color + two eye pixels
— enough to tell the roster apart, nothing more.

To upgrade to real art, overwrite these PNGs (keep the same file names) with any
64x64 humanoid skin in the standard Minecraft skin layout — no code change needed.
Suggested directions when sourcing **permissively-licensed** art later (verify the
license before shipping; ARR mods below are DESIGN references only, do NOT copy
their textures):

- **Wandering Orc** — thematic goblin/orc humanoids (ARR — design reference only).
- **Epic Knights / Epic Paladins** — plate-armor knight aesthetics for
  `man_at_arms` / `heavy_knight` (ARR — design reference only).
- Any **CC0 humanoid skin pack** (e.g. OpenGameArt "free Minecraft-style skins")
  is a safe drop-in; record the credit here and in a `NOTICE` file if attribution
  is required.

## Code patterns / design referenced (not copied verbatim)

All Java here is written fresh against Yarn 1.21.6+build.1. The following clean
(permissive / public-domain) sources informed the DESIGN:

- **Coins-on-kill** follows **Mob Money** — License: **CC0** (public domain),
  supports 1.21.x Fabric — https://github.com/xSaVageAU/MobMoney . Implemented via
  Fabric API `ServerLivingEntityEvents.AFTER_DEATH`, rewarding only kills whose
  damage attacker resolves to a player (matches Mob Money's player-kill reward).
- **Aimed-arrow-from-a-block-entity** turret firing follows the Fabric Wiki
  custom-projectiles tutorial (https://wiki.fabricmc.net/tutorial:projectiles) and
  the vanilla `AbstractSkeletonEntity` / `BowItem` pattern: build an `ArrowEntity`,
  aim `dir = target - arrowPos`, `setVelocity(dir.x, dir.y, dir.z, speed,
  divergence)`, `spawnEntity`. Auto-acquire + fire behavior mirrors **K-Turrets
  [Fabric]** and **CubicTurret** (MIT, https://github.com/Pitan76/CubicTurret) as
  behavioral references only.
- **Weapon damage/speed tradeoffs** mirror the design of **MedievalWeapons**
  (globox1997) — its **CODE is MIT** and may be referenced, but its **ART is
  copyrighted (Songs of War) and was NOT used**. Our weapon textures are original
  placeholders.

### Reference-only mods (design mirrored, NO code/art reused — restrictive licenses)
SpellEngine & ProjectileDamage (GPL); Open Tower Defence / HTOpenTD &
mc-tower-defence (LGPL); Iron's Spells, Simply Swords, Wandering Orc, Nexus
(ARR / custom). Listed here so future work steers clear of copying from them.

## Recommended permissively-licensed art to drop in later

Verified during research. To upgrade visuals, download these and overwrite the
PNGs above (keep the same file names):

- **Shade — Free 16x16 Weapon RPG Icons** — License: **CC0** (no attribution
  required). Includes Spear, Mace, and Battle Hammer at native 16x16.
  - https://opengameart.org/content/16x16-weapon-rpg-icons
  - https://merchant-shade.itch.io/free-16x16-weapon-rpg-icons
- **MedievalWeapons — Globox1997** — License: **MIT** (attribution required).
  Higher-fidelity weapon textures in multiple material tiers (`*_lance.png`,
  `*_mace.png`, etc.).
  - https://github.com/Globox1997/MedievalWeapons
- **CC0 stone / castle-brick tiles** for the tower block:
  - https://opengameart.org/content/16x16-block-texture-set (CC0)
  - https://opengameart.org/content/castle-brick-connecting-tileset-16x16 (CC0)

If you add any MIT (attribution-required) art, record the credit here and in a
`NOTICE` file before shipping.
