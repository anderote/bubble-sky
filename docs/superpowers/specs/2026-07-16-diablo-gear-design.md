# Diablo-Style Graded Gear — Design

_Date: 2026-07-16 · Mod: `mods/towerdefense` (Fabric 1.21.6) · Build-our-own (no 3rd-party mod)_

## Goal

Loot that feels like Diablo: enemies drop **graded gear** (rarity tiers) with **random stat
affixes**, colored names + tooltips, drops that scale with wave + the Warlord's escalation,
and an essence-based identify/salvage loop. Fully integrated with our existing RPG (7 stats,
classes, essence, StatModifiers) — no new client mod (ships in our jar; lore/name are
server-set data so no client mixin needed).

## Rarities

| Rarity | Color | Weight (base) | Affix count |
|---|---|---|---|
| Common | white | 60 | 0–1 |
| Magic | blue | 25 | 1–2 |
| Rare | yellow | 10 | 2–3 |
| Epic | purple | 4 | 3–4 |
| Legendary | gold | 1 | 4–5 (+1 signature) |

Rarity roll is weighted; higher **wave** and higher **Warlord escalation E** shift weight
toward better rarities (a luck/level factor added to the roll).

## Item scope (v1)

Vanilla base items as the chassis (they already work as weapons/armor): swords
(iron/diamond/netherite bases by tier), bows, and armor pieces (helmet/chest/legs/boots of
a metal tier). Affixes + rarity ride on top via components. (Guns/staves can come later.)

## Affixes (random stat rolls)

Stored in a `DataComponentTypes.CUSTOM_DATA` sub-compound `td_gear` = `{rarity, affixes:[{id,
value}], identified}`. An `Affix` registry (enum) with id, display, a rolled value range, and
how it applies. Pool (prefix/suffix flavored):
- +X% melee/bow damage · +X to a stat (STR/VIT/AGI/MARKS/FORTUNE/INT/RESIL) · +X% crit chance
  · +X% life steal · +X% movement speed · +X armor · +X max mana · +X% XP · +X% gold/essence ·
  +thorns · +X max health. Legendary signature affixes are punchier (e.g. "chain lightning on
  hit", "+1 summon", "explosive arrows") — a small curated set.

## Stat application

A gear-affix layer in `StatModifiers` (or a sibling `GearModifiers`): each tick / on inventory
change, sum the affixes across the player's EQUIPPED armor + held weapon and apply them as
`EntityAttributeModifier`s (keyed, replace-on-refresh so they don't stack across relogs) for
attribute affixes, and expose use-time multipliers (crit, life steal, %dmg, %gold) read at the
relevant call-sites — mirroring how the existing stat multipliers work. Only the held/worn
items count (so swapping gear changes your stats).

## Tooltips / display

Set the item's **name** (custom name component) colored by rarity + the **lore** component with
one colored line per affix (and "Unidentified — use N essence to identify" when unidentified).
All server-set data → renders on any client, no mixin.

## Drops

Hook the existing `td_enemy` death path (ProgressEvents/WaveRewards): on kill, a drop chance
(scales with enemy tier, wave, escalation E; bosses much higher) rolls a gear item via the
generator and drops it as an ItemEntity. Drops start **unidentified** for Rare+ (rarity/affixes
hidden until identified) for the Diablo "what did I get?" hook; Common/Magic drop identified.

## Essence loop (ties to our economy)

- **Identify**: `/td identify` (held item) or an anvil interaction spends essence (cost scales
  with rarity) to reveal an unidentified item's affixes.
- **Salvage**: `/td salvage` (held item) destroys gear for essence (return scales with rarity),
  so junk drops feed the economy. This closes the loot loop with the existing essence currency.

## Build phases

- **A — foundation**: `Rarity` + `Affix` model, the `td_gear` component, the gear **generator**
  (roll rarity+affixes for a base item), rarity name + lore tooltips, and **stat application**
  (equipped/held affixes → attributes + use-time mults). Testable via a `/td givegear <rarity>`
  debug command.
- **B — drops**: enemy-death drop rolls (scaling with wave/escalation/boss), unidentified-for-
  Rare+.
- **C — economy**: identify + salvage with essence; rarity drop-weight luck from wave/E.

## Integration constraints / non-goals

- MUST NOT double up with our existing stat system — gear affixes are an ADDITIVE layer keyed
  separately from the class/global stat modifiers. No 3rd-party mod. Vanilla bases only in v1.
  Sockets/gems/runewords/sets are future (not v1).
