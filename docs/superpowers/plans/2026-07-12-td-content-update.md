# Tower Defense Content Update — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two new tower types (Sharpshooter, Flamethrower), a kill-earned veterancy system for all towers, wave-completion loot drops at a spawn gate, and a "My Towers" management panel — all inside the existing `mods/towerdefense` Fabric mod.

**Architecture:** Four independent parts built in order (A → B → C → D). Each part leaves the mod compiling and playable. New combat/economy logic is extracted into Minecraft-free static helper classes (`TowerVeterancy`, `WaveRewards`) so it can be unit-tested; block/entity/screen behavior is verified in-game. The two new towers reuse the existing `AbstractTowerBlockEntity` recipe; veterancy is folded into that base class's derived-stat methods so every tower (old and new) benefits with no per-tower edits to the stat math.

**Tech Stack:** Java 21, Minecraft 1.21.6, Fabric Loom, Fabric API, yarn mappings `1.21.6+build.1`, JUnit 5 (for pure-logic tests).

## Global Constraints

- Minecraft `1.21.6`, Fabric loader `0.19.3`, Fabric API `0.128.2+1.21.6`, Java release `21`, yarn `1.21.6+build.1`. Copy exact values from `mods/towerdefense/gradle.properties`; do not bump.
- Package root `net.bubblesky.towerdefense`; mod id constant `TowerDefenseMod.MOD_ID`.
- **Indentation is TABS** in all `.java` files (match surrounding files exactly).
- Block-entity persistence (1.21.6): `readData(ReadView)` / `writeData(WriteView)`; typed getters take a default — `view.getInt("key", def)`, `view.getString("key", "")`; setters `view.putInt`, `view.putString`. Always call `super.readData/​writeData` first.
- `PersistentState` (1.21.6): `new PersistentStateType<>(name, ctor, Codec, DataFixTypes.LEVEL)` + `server.getOverworld().getPersistentStateManager().getOrCreate(TYPE)`; build the Codec with `NbtCompound.CODEC.xmap(fromNbt, toNbt)`. Each store needs a **unique** persistent key string.
- Networking: register payload types with `PayloadTypeRegistry.playC2S()/playS2C().register(ID, CODEC)` inside a `register()` called once from mod init; register the client S2C **receiver** with `ClientPlayNetworking.registerGlobalReceiver(ID, ...)` in `TowerDefenseModClient.onInitializeClient()`. Payload id = `new CustomPayload.Id<>(Identifier.of(TowerDefenseMod.MOD_ID, "..."))`.
- No external/JSON config for game tuning — all tunables are `private static final` constants near the top of their owning class (mirror `WaveManager`).
- Coins are the plain item `ModItems.COIN`; read via `player.getInventory().getMainStacks()` + `stack.isOf(ModItems.COIN)` / `stack.getCount()`; spend via `stack.decrement(n)`; grant via `new ItemStack(ModItems.COIN, n)` inserted with `player.getInventory().insertStack(stack)` (fallback `player.dropItem(stack, false)`).
- World type on the server is `net.minecraft.server.world.ServerWorld`; players are `ServerPlayerEntity`; enemies are `HostileEntity`. Read enemy HP with `getMaxHealth()` / `getHealth()` (floats).
- **DO NOT COMMIT.** The maintainer commits at their own boundaries. Implement the task, run the tests/build, write the report — but skip every "Commit" step in the tasks below (they remain only to mark task boundaries). Never push, open PRs, or run any remote-mutating git command.
- **PRESERVE concurrent in-tree work — do not revert it.** The working tree already contains uncommitted edits by another worker that this plan builds *on top of*, not over: (1) `AbstractTowerBlockEntity` now has `MAX_TIER = 6` with **geometric** scaling — `range()` = `baseRange() + (tier-1)*3.0`, `cooldownTicks()` = `baseCooldown() * Math.pow(0.85, tier-1)`, `damageMultiplier()` = `Math.pow(1.5, tier-1)`; (2) `TdCommand.buy` has signature `buy(ctx, type, count)` (bulk purchase); (3) `ArrowTowerBlockEntity` sets `arrow.pickupType = DISALLOWED`; (4) an unrelated colony feature (`ColonyRespawn`, `ColonyOrders`, `TowerDefenseMod` init). When editing any of these files, **keep these changes intact** and add alongside them. Any task step whose "before" code shows the *old* linear formulas or the old `buy(ctx, type)` signature has been reconciled below — follow the reconciled text.

---

## Task 0: Confirm build + test harness

**Files:**
- Inspect: `mods/towerdefense/build.gradle`, `mods/towerdefense/gradle.properties`
- Possibly modify: `mods/towerdefense/build.gradle`
- Create (if missing): `mods/towerdefense/src/test/java/net/bubblesky/towerdefense/.gitkeep`

**Interfaces:**
- Produces: a working `./gradlew test` task so later pure-logic tasks can run JUnit.

- [ ] **Step 1: Check for an existing test source set**

Run: `ls mods/towerdefense/src/test/java 2>/dev/null; grep -n "junit" mods/towerdefense/build.gradle`
Expected: either JUnit is already wired (proceed, note the version) or nothing prints (add it in Step 2).

- [ ] **Step 2: If absent, add JUnit 5 to `build.gradle`**

Add to the `dependencies { }` block:

```gradle
	testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
```

And after the `dependencies` block add:

```gradle
test {
	useJUnitPlatform()
}
```

- [ ] **Step 3: Add a trivial sanity test and run it**

Create `mods/towerdefense/src/test/java/net/bubblesky/towerdefense/HarnessTest.java`:

```java
package net.bubblesky.towerdefense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class HarnessTest {
	@Test
	void harnessWorks() {
		assertEquals(2, 1 + 1);
	}
}
```

Run: `cd mods/towerdefense && ./gradlew test`
Expected: BUILD SUCCESSFUL, 1 test passed.

- [ ] **Step 4: Commit**

```bash
git add mods/towerdefense/build.gradle mods/towerdefense/src/test
git commit -m "chore(td): wire JUnit 5 test harness for content update"
```

---

# PART A — New tower types

Each new tower mirrors the Arrow tower recipe (enum entry + `*Block` + `*BlockEntity` + four registry edits + `/td` catalogue + assets). Reference templates (read them first): `blockentity/ArrowTowerBlockEntity.java`, `block/ArrowTowerBlock.java`, `registry/ModBlocks.java:33-40,88-99`, `registry/ModBlockEntities.java:22-25`, `registry/ModItems.java:74-75`, `registry/ModItemGroups.java:43,47`, `command/TdCommand.java:60-63`.

## Task A1: Sharpshooter tower (armor-piercing / crit boss-killer)

**Files:**
- Modify: `tower/TowerKind.java:33` (add enum entry)
- Create: `blockentity/SharpshooterTowerBlockEntity.java`
- Create: `block/SharpshooterTowerBlock.java`
- Modify: `registry/ModBlocks.java` (add `SHARPSHOOTER_TOWER`)
- Modify: `registry/ModBlockEntities.java` (add `SHARPSHOOTER_TOWER` BE type)
- Modify: `registry/ModItems.java` (add `SHARPSHOOTER_TOWER_ARROW`)
- Modify: `registry/ModItemGroups.java` (add block + arrow to creative group)
- Modify: `command/TdCommand.java` (add to `TOWERS` map; add help line if the help block enumerates towers)
- Create: `blockentity/SharpshooterDamage.java` (pure damage math — unit tested)
- Test: `src/test/java/net/bubblesky/towerdefense/SharpshooterDamageTest.java`
- Assets: `assets/towerdefense/blockstates/sharpshooter_tower.json`, `models/block/sharpshooter_tower.json`, `models/item/sharpshooter_tower.json`, `items/sharpshooter_tower_arrow.json`, textures under `textures/block/`, plus lang keys.

**Interfaces:**
- Consumes: `AbstractTowerBlockEntity` (`baseRange()`, `baseCooldown()`, `fire(...)`, `findNearestHostile(...)`, `damageMultiplier()`, `placerPlayer(...)`, `kind()`), `TowerKind`.
- Produces: `TowerKind.SHARPSHOOTER`; `ModBlocks.SHARPSHOOTER_TOWER`; `ModBlockEntities.SHARPSHOOTER_TOWER`; `ModItems.SHARPSHOOTER_TOWER_ARROW`; `SharpshooterDamage.compute(float targetMaxHealth, double tierMult, boolean crit)` returning `float`.

- [ ] **Step 1: Write the failing damage-math test**

Create `SharpshooterDamageTest.java`:

```java
package net.bubblesky.towerdefense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import net.bubblesky.towerdefense.blockentity.SharpshooterDamage;
import org.junit.jupiter.api.Test;

class SharpshooterDamageTest {
	@Test
	void scalesWithTargetMaxHealth() {
		// flat 6 + 20% of 100 max HP = 26, tier mult 1.0, no crit
		assertEquals(26.0f, SharpshooterDamage.compute(100.0f, 1.0, false), 1.0e-4);
	}

	@Test
	void critDoubles() {
		assertEquals(52.0f, SharpshooterDamage.compute(100.0f, 1.0, true), 1.0e-4);
	}

	@Test
	void appliesTierMultiplier() {
		// (6 + 0.2*50) = 16, *2.0 tier mult = 32
		assertEquals(32.0f, SharpshooterDamage.compute(50.0f, 2.0, false), 1.0e-4);
	}
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd mods/towerdefense && ./gradlew test --tests '*SharpshooterDamageTest'`
Expected: FAIL — `SharpshooterDamage` does not exist / cannot resolve.

- [ ] **Step 3: Implement `SharpshooterDamage`**

Create `blockentity/SharpshooterDamage.java`:

```java
package net.bubblesky.towerdefense.blockentity;

/**
 * Pure damage math for the Sharpshooter tower, split out so it is testable without a
 * running Minecraft. Damage = (flat + fraction-of-target-max-HP) * tier multiplier,
 * doubled on a crit. The max-HP term keeps the tower relevant as enemy HP scales up
 * with waves (it "ignores" flat scaling by dealing a percentage of whatever HP the
 * target has).
 */
public final class SharpshooterDamage {
	/** Flat base damage before any scaling. */
	public static final float FLAT = 6.0f;
	/** Fraction of the target's max health added to the flat base. */
	public static final float MAX_HP_FRACTION = 0.20f;
	/** Crit chance per shot. */
	public static final double CRIT_CHANCE = 0.25;
	/** Crit multiplier. */
	public static final float CRIT_MULT = 2.0f;

	private SharpshooterDamage() {
	}

	public static float compute(float targetMaxHealth, double tierMult, boolean crit) {
		float base = (FLAT + MAX_HP_FRACTION * targetMaxHealth) * (float) tierMult;
		return crit ? base * CRIT_MULT : base;
	}
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `cd mods/towerdefense && ./gradlew test --tests '*SharpshooterDamageTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Add the `TowerKind` enum entry**

In `tower/TowerKind.java`, after the `BALL(...)` entry (line 32-33) change the terminating `;` to a `,` and add:

```java
	/** Sharpshooter: long range, slow, armor-piercing crits — the boss/tank killer. */
	SHARPSHOOTER("sharpshooter_tower", () -> ModBlocks.SHARPSHOOTER_TOWER, () -> ModItems.SHARPSHOOTER_TOWER_ARROW,
		Blocks.POLISHED_BLACKSTONE, Blocks.EMERALD_BLOCK, 0);
```

- [ ] **Step 6: Create the block entity**

Create `blockentity/SharpshooterTowerBlockEntity.java`:

```java
package net.bubblesky.towerdefense.blockentity;

import java.util.List;
import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.bubblesky.towerdefense.tower.TowerKind;
import net.minecraft.block.BlockState;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.jetbrains.annotations.Nullable;

/**
 * Sharpshooter tower: very long range, slow cadence. Targets the TOUGHEST enemy in
 * range (highest max health) and deals {@link SharpshooterDamage} — a flat base plus a
 * percentage of the target's max HP, with a chance to crit. The percentage term makes
 * it the answer to high-HP late-wave enemies and bosses.
 */
public class SharpshooterTowerBlockEntity extends AbstractTowerBlockEntity {
	private static final double BASE_RANGE = 40.0;
	private static final int BASE_COOLDOWN = 70;
	private static final float ARROW_SPEED = 3.0f;
	private static final float ARROW_DIVERGENCE = 0.0f;

	public SharpshooterTowerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.SHARPSHOOTER_TOWER, pos, state);
	}

	@Override
	public TowerKind kind() {
		return TowerKind.SHARPSHOOTER;
	}

	@Override
	protected double baseRange() {
		return BASE_RANGE;
	}

	@Override
	protected int baseCooldown() {
		return BASE_COOLDOWN;
	}

	/** Override targeting: pick the enemy with the highest max health (toughest), not the nearest. */
	@Override
	@Nullable
	protected HostileEntity findNearestHostile(ServerWorld world, BlockPos pos, double cx, double cy, double cz) {
		double range = range();
		Box box = new Box(pos).expand(range);
		List<HostileEntity> mobs = world.getNonSpectatingEntities(HostileEntity.class, box);
		HostileEntity toughest = null;
		float bestHp = -1.0f;
		double rangeSq = range * range;
		for (HostileEntity mob : mobs) {
			if (!mob.isAlive() || mob.squaredDistanceTo(cx, cy, cz) > rangeSq) {
				continue;
			}
			if (mob.getMaxHealth() > bestHp) {
				bestHp = mob.getMaxHealth();
				toughest = mob;
			}
		}
		return toughest;
	}

	@Override
	protected void fire(ServerWorld world, double cx, double cy, double cz, HostileEntity target) {
		boolean crit = world.random.nextDouble() < SharpshooterDamage.CRIT_CHANCE;
		float damage = SharpshooterDamage.compute(target.getMaxHealth(), damageMultiplier(), crit);

		ArrowEntity arrow = new ArrowEntity(world, cx, cy, cz, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
		double dx = target.getX() - cx;
		double dy = target.getBodyY(0.5) - cy;
		double dz = target.getZ() - cz;
		arrow.setVelocity(dx, dy, dz, ARROW_SPEED, ARROW_DIVERGENCE);
		arrow.setDamage(damage);

		ServerPlayerEntity owner = placerPlayer(world);
		if (owner != null) {
			arrow.setOwner(owner);
		}
		world.spawnEntity(arrow);
		world.playSound(null, cx, cy, cz,
			crit ? SoundEvents.ITEM_CROSSBOW_HIT : SoundEvents.ITEM_CROSSBOW_SHOOT,
			SoundCategory.BLOCKS, 1.0f, crit ? 0.8f : 1.2f);
	}
}
```

- [ ] **Step 7: Create the block**

Create `block/SharpshooterTowerBlock.java` mirroring `block/ArrowTowerBlock.java` exactly, replacing `Arrow` → `Sharpshooter` and the ticker's BE type:

```java
package net.bubblesky.towerdefense.block;

import com.mojang.serialization.MapCodec;
import net.bubblesky.towerdefense.blockentity.SharpshooterTowerBlockEntity;
import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class SharpshooterTowerBlock extends BlockWithEntity {
	public static final MapCodec<SharpshooterTowerBlock> CODEC = createCodec(SharpshooterTowerBlock::new);

	public SharpshooterTowerBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}

	@Override
	protected BlockRenderType getRenderType(BlockState state) {
		return BlockRenderType.MODEL;
	}

	@Nullable
	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new SharpshooterTowerBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state,
			BlockEntityType<T> type) {
		if (world.isClient) {
			return null;
		}
		return validateTicker(type, ModBlockEntities.SHARPSHOOTER_TOWER, SharpshooterTowerBlockEntity::tick);
	}
}
```

- [ ] **Step 8: Register block, block entity, item, creative group**

In `registry/ModBlocks.java`, after the `BALL_TOWER` block (line 68), add:

```java
	public static final Block SHARPSHOOTER_TOWER = registerTower("sharpshooter_tower",
		SharpshooterTowerBlock::new,
		AbstractBlock.Settings.create()
			.strength(3.5f, 7.0f)
			.requiresTool()
			.nonOpaque()
			.sounds(BlockSoundGroup.STONE),
		TowerKind.SHARPSHOOTER);
```
(add `import net.bubblesky.towerdefense.block.SharpshooterTowerBlock;`)

In `registry/ModBlockEntities.java`, after `BALL_TOWER` (line 40), add:

```java
	public static final BlockEntityType<SharpshooterTowerBlockEntity> SHARPSHOOTER_TOWER =
		Registry.register(Registries.BLOCK_ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, "sharpshooter_tower"),
			FabricBlockEntityTypeBuilder.create(SharpshooterTowerBlockEntity::new, ModBlocks.SHARPSHOOTER_TOWER).build());
```
(add `import net.bubblesky.towerdefense.blockentity.SharpshooterTowerBlockEntity;`)

In `registry/ModItems.java`, after `BALL_TOWER_ARROW` (line 83), add:

```java
	public static final Item SHARPSHOOTER_TOWER_ARROW = register("sharpshooter_tower_arrow",
		s -> new TowerArrowItem(s, TowerKind.SHARPSHOOTER), new Item.Settings());
```

In `registry/ModItemGroups.java`, add inside the entries lambda (after line 46 and after line 50 respectively):

```java
			entries.add(ModItems.SHARPSHOOTER_TOWER_ARROW);
```
```java
			entries.add(ModBlocks.SHARPSHOOTER_TOWER);
```

- [ ] **Step 9: Add to the `/td` shop catalogue**

In `command/TdCommand.java`, in the `static { }` block (after line 63) add:

```java
		TOWERS.put("sharpshooter_tower", new TowerDef(ModBlocks.SHARPSHOOTER_TOWER, 45));
```
Then check `help()` (~lines 211-218): if it enumerates towers by calling `TOWERS.get("arrow_tower").price()` etc., add a matching line for `sharpshooter_tower`. If help iterates `catalogue()` dynamically, no change needed.

- [ ] **Step 10: Add assets (blockstate, models, item model, textures, lang)**

Copy the four Arrow-tower asset files as templates and rename for `sharpshooter_tower`:
- `assets/towerdefense/blockstates/sharpshooter_tower.json` — copy `blockstates/arrow_tower.json`, repoint the model to `towerdefense:block/sharpshooter_tower`.
- `assets/towerdefense/models/block/sharpshooter_tower.json` — copy `models/block/arrow_tower.json`, repoint textures.
- `assets/towerdefense/models/item/sharpshooter_tower.json` — parent `towerdefense:block/sharpshooter_tower`.
- `assets/towerdefense/items/sharpshooter_tower_arrow.json` — copy `items/arrow_tower_arrow.json` (or `coin.json` for the `{"model":{"type":"minecraft:model","model":"..."}}` shape), repoint to a `sharpshooter_tower_arrow` model.
- Textures: copy the arrow-tower block textures under `textures/block/` to `sharpshooter_tower*.png` (emerald/blackstone tint is fine as a first pass; art polish later).
- In `assets/towerdefense/lang/en_us.json` add (matching existing `block.towerdefense.*` / `item.towerdefense.*` keys — grep the file for `arrow_tower` to see the exact key names):

```json
	"block.towerdefense.sharpshooter_tower": "Sharpshooter Tower",
	"item.towerdefense.sharpshooter_tower_arrow": "Sharpshooter Tower Arrow",
```

Run: `cd mods/towerdefense && ./gradlew build`
Expected: BUILD SUCCESSFUL (compiles + data assets validate).

- [ ] **Step 11: Manual in-game verification**

Launch the client (`./gradlew runClient`), set up an arena (`/td arena`, `/td spawn`, `/td idol`), `/td buy sharpshooter_tower`, place it, `/td wave`. Confirm: it targets the toughest enemy, one-shots or heavily damages tanks/bosses, has visibly long range and slow cadence, and kills pay coins to you. Confirm it shows in the creative tab and `/td shop`.

- [ ] **Step 12: Commit**

```bash
git add mods/towerdefense
git commit -m "feat(td): add Sharpshooter tower (armor-piercing crit boss-killer)"
```

## Task A2: Flamethrower tower (spreading fire)

**Files:**
- Modify: `tower/TowerKind.java` (add `FLAMETHROWER` entry)
- Create: `blockentity/FlamethrowerTowerBlockEntity.java`
- Create: `block/FlamethrowerTowerBlock.java`
- Create: `game/FlameBurnManager.java` (server-tick DoT + spread + kill credit)
- Modify: `registry/ModBlocks.java`, `registry/ModBlockEntities.java`, `registry/ModItems.java`, `registry/ModItemGroups.java`
- Modify: `command/TdCommand.java` (`TOWERS` + help)
- Modify: `TowerDefenseMod.java` (start `FlameBurnManager` tick — mirror how `WaveManager.register()`/tick is wired)
- Test: `src/test/java/net/bubblesky/towerdefense/FlameBurnManagerTest.java` (pure spread-selection helper)
- Assets: same set as A1 for `flamethrower_tower` (palette `Blocks.NETHER_BRICKS` pole, `Blocks.MAGMA_BLOCK` ball).

**Interfaces:**
- Consumes: `AbstractTowerBlockEntity`, `TowerKind`, `ServerWorld`, `HostileEntity`.
- Produces: `TowerKind.FLAMETHROWER`; `ModBlocks.FLAMETHROWER_TOWER`; `ModBlockEntities.FLAMETHROWER_TOWER`; `ModItems.FLAMETHROWER_TOWER_ARROW`; `FlameBurnManager.ignite(ServerWorld, HostileEntity, BlockPos towerPos, java.util.UUID placer, int ticks, float damagePerInterval)`, `FlameBurnManager.tickAll(ServerWorld)`, `FlameBurnManager.selectSpreadTargets(...)` (pure helper for the test — see Step 1).

> **Kill attribution note:** the Flamethrower deals damage through `FlameBurnManager`, which knows the source tower position. When a burn tick reduces an enemy to 0 HP, the manager credits that tower's veterancy **directly** (wired in Part B, Task B3 Step 4). Until Part B lands, the manager's `onBurnKill` hook is a no-op stub.

- [ ] **Step 1: Write the failing spread-selection test**

The only Minecraft-free piece is "given candidate positions and a center + radius, which are within the spread radius (excluding the already-burning origin)". Extract it as a static helper on `FlameBurnManager` and test it.

Create `FlameBurnManagerTest.java`:

```java
package net.bubblesky.towerdefense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import net.bubblesky.towerdefense.game.FlameBurnManager;
import org.junit.jupiter.api.Test;

class FlameBurnManagerTest {
	@Test
	void selectsOnlyWithinRadiusExcludingOrigin() {
		// candidates as (index, distanceSquared-from-origin)
		List<Integer> within = FlameBurnManager.withinSpread(
			new double[] {0.0, 4.0, 9.0, 16.0}, // dist^2 for candidates 0..3
			0,                                    // origin index (excluded)
			3.0);                                 // spread radius (r^2 = 9)
		// candidate 0 is origin (excluded), 1 (d^2=4<=9) and 2 (d^2=9<=9) qualify, 3 (16>9) does not
		assertEquals(List.of(1, 2), within);
	}

	@Test
	void emptyWhenNoneInRange() {
		assertTrue(FlameBurnManager.withinSpread(new double[] {100.0}, -1, 3.0).isEmpty());
	}
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd mods/towerdefense && ./gradlew test --tests '*FlameBurnManagerTest'`
Expected: FAIL — `FlameBurnManager` does not exist.

- [ ] **Step 3: Implement `FlameBurnManager`**

Create `game/FlameBurnManager.java`:

```java
package net.bubblesky.towerdefense.game;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.jetbrains.annotations.Nullable;

/**
 * Tracks active Flamethrower burns. Each burn deals periodic damage attributed to the
 * placing player (so kills pay coins) and, while alive, spreads to nearby not-yet-burning
 * enemies (contagion). Burns are transient combat state — not persisted across restarts.
 *
 * <p>Because the manager applies the damage itself it knows which tower is responsible,
 * so it credits that tower's veterancy directly on a burn kill (see {@link #onBurnKill}).
 */
public final class FlameBurnManager {
	/** Ticks between burn damage applications. */
	private static final int BURN_INTERVAL = 10;
	/** Radius (blocks) a burn spreads to each interval. */
	private static final double SPREAD_RADIUS = 3.0;
	/** Max new enemies a single burn ignites per interval. */
	private static final int SPREAD_CAP = 3;

	private static final class Burn {
		final BlockPos towerPos;
		final UUID placer;
		int ticksRemaining;
		final float damagePerInterval;
		int untilNext = BURN_INTERVAL;

		Burn(BlockPos towerPos, UUID placer, int ticksRemaining, float damagePerInterval) {
			this.towerPos = towerPos;
			this.placer = placer;
			this.ticksRemaining = ticksRemaining;
			this.damagePerInterval = damagePerInterval;
		}
	}

	private static final ConcurrentHashMap<UUID, Burn> BURNS = new ConcurrentHashMap<>();

	private FlameBurnManager() {
	}

	/** Start (or refresh) a burn on {@code enemy}. */
	public static void ignite(ServerWorld world, HostileEntity enemy, BlockPos towerPos, UUID placer,
			int ticks, float damagePerInterval) {
		enemy.setOnFireForTicks(ticks);
		BURNS.compute(enemy.getUuid(), (id, existing) -> {
			if (existing == null) {
				return new Burn(towerPos, placer, ticks, damagePerInterval);
			}
			existing.ticksRemaining = Math.max(existing.ticksRemaining, ticks);
			return existing;
		});
	}

	/** Pure helper: indices whose distanceSquared is within {@code radius}, excluding {@code originIndex}. */
	public static List<Integer> withinSpread(double[] distSq, int originIndex, double radius) {
		double r2 = radius * radius;
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < distSq.length; i++) {
			if (i == originIndex) {
				continue;
			}
			if (distSq[i] <= r2) {
				out.add(i);
			}
		}
		return out;
	}

	/** Advance all burns one tick. Call once per server tick with each active TD world. */
	public static void tickAll(ServerWorld world) {
		for (Iterator<java.util.Map.Entry<UUID, Burn>> it = BURNS.entrySet().iterator(); it.hasNext(); ) {
			java.util.Map.Entry<UUID, Burn> e = it.next();
			Burn burn = e.getValue();
			LivingEntity entity = findLiving(world, e.getKey());
			if (entity == null || !entity.isAlive()) {
				it.remove();
				continue;
			}
			burn.ticksRemaining--;
			if (burn.ticksRemaining <= 0) {
				it.remove();
			}
			if (--burn.untilNext > 0) {
				continue;
			}
			burn.untilNext = BURN_INTERVAL;

			// Apply burn damage attributed to the placer so kills pay coins.
			ServerPlayerEntity owner = world.getServer().getPlayerManager().getPlayer(burn.placer);
			DamageSource src = owner != null
				? world.getDamageSources().playerAttack(owner)
				: world.getDamageSources().onFire();
			boolean wasAlive = entity.isAlive();
			entity.damage(world, src, burn.damagePerInterval);
			if (wasAlive && !entity.isAlive()) {
				onBurnKill(world, burn.towerPos);
				it.remove();
				continue;
			}

			// Spread to nearby not-yet-burning enemies.
			spread(world, entity, burn);
		}
	}

	private static void spread(ServerWorld world, LivingEntity origin, Burn burn) {
		Box box = new Box(origin.getBlockPos()).expand(SPREAD_RADIUS);
		int ignited = 0;
		for (HostileEntity mob : world.getNonSpectatingEntities(HostileEntity.class, box)) {
			if (ignited >= SPREAD_CAP) {
				break;
			}
			if (mob == origin || BURNS.containsKey(mob.getUuid()) || !mob.isAlive()) {
				continue;
			}
			if (mob.squaredDistanceTo(origin) <= SPREAD_RADIUS * SPREAD_RADIUS) {
				ignite(world, mob, burn.towerPos, burn.placer, burn.ticksRemaining, burn.damagePerInterval);
				ignited++;
			}
		}
	}

	@Nullable
	private static LivingEntity findLiving(ServerWorld world, UUID id) {
		return world.getEntity(id) instanceof LivingEntity le ? le : null;
	}

	/** Credit the tower that owns this burn with a kill. Wired up in Part B (Task B3 Step 4). */
	private static void onBurnKill(ServerWorld world, BlockPos towerPos) {
		// PART B: resolve the tower block entity at towerPos and credit it (no-op stub until Task B3).
	}
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `cd mods/towerdefense && ./gradlew test --tests '*FlameBurnManagerTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Add the `TowerKind` entry**

In `tower/TowerKind.java`, extend the enum (change the `SHARPSHOOTER(...)` terminator to `,`) and add:

```java
	/** Flamethrower: short range, ignites a target and spreads fire to nearby enemies. */
	FLAMETHROWER("flamethrower_tower", () -> ModBlocks.FLAMETHROWER_TOWER, () -> ModItems.FLAMETHROWER_TOWER_ARROW,
		Blocks.NETHER_BRICKS, Blocks.MAGMA_BLOCK, 0);
```

- [ ] **Step 6: Create the block entity**

Create `blockentity/FlamethrowerTowerBlockEntity.java`:

```java
package net.bubblesky.towerdefense.blockentity;

import net.bubblesky.towerdefense.game.FlameBurnManager;
import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.bubblesky.towerdefense.tower.TowerKind;
import net.minecraft.block.BlockState;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;

/**
 * Flamethrower tower: short range, fast cadence. Ignites its target (and, via
 * {@link FlameBurnManager}, spreads fire to nearby enemies) with a burn that ticks
 * damage over time. Crowd-clearer; weak against lone tanks.
 */
public class FlamethrowerTowerBlockEntity extends AbstractTowerBlockEntity {
	private static final double BASE_RANGE = 8.0;
	private static final int BASE_COOLDOWN = 20;
	private static final int BURN_TICKS = 80;
	private static final float BURN_DAMAGE_PER_INTERVAL = 2.0f;

	public FlamethrowerTowerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.FLAMETHROWER_TOWER, pos, state);
	}

	@Override
	public TowerKind kind() {
		return TowerKind.FLAMETHROWER;
	}

	@Override
	protected double baseRange() {
		return BASE_RANGE;
	}

	@Override
	protected int baseCooldown() {
		return BASE_COOLDOWN;
	}

	@Override
	protected void fire(ServerWorld world, double cx, double cy, double cz, HostileEntity target) {
		if (placer == null) {
			// No owner → no coin/veterancy credit path; still burn for effect.
			FlameBurnManager.ignite(world, target, getPos(), new java.util.UUID(0L, 0L),
				BURN_TICKS, (float) (BURN_DAMAGE_PER_INTERVAL * damageMultiplier()));
		} else {
			FlameBurnManager.ignite(world, target, getPos(), placer,
				BURN_TICKS, (float) (BURN_DAMAGE_PER_INTERVAL * damageMultiplier()));
		}
		world.playSound(null, cx, cy, cz, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, 1.0f, 1.0f);
	}
}
```

> `placer` and `getPos()` are inherited from `AbstractTowerBlockEntity` (protected field + `BlockEntity.getPos()`).

- [ ] **Step 7: Create the block**

Create `block/FlamethrowerTowerBlock.java` — identical to `SharpshooterTowerBlock` (Task A1 Step 7) with `Sharpshooter` → `Flamethrower` and the ticker BE type `ModBlockEntities.FLAMETHROWER_TOWER`.

- [ ] **Step 8: Register (blocks / BE / item / group) + catalogue**

Mirror Task A1 Steps 8-9 for `flamethrower_tower`:
- `ModBlocks`: `FLAMETHROWER_TOWER = registerTower("flamethrower_tower", FlamethrowerTowerBlock::new, AbstractBlock.Settings.create().strength(3.0f,6.0f).requiresTool().nonOpaque().sounds(BlockSoundGroup.NETHER_BRICKS), TowerKind.FLAMETHROWER);`
- `ModBlockEntities`: `FLAMETHROWER_TOWER` type bound to `ModBlocks.FLAMETHROWER_TOWER`.
- `ModItems`: `FLAMETHROWER_TOWER_ARROW = register("flamethrower_tower_arrow", s -> new TowerArrowItem(s, TowerKind.FLAMETHROWER), new Item.Settings());`
- `ModItemGroups`: add arrow + block entries.
- `TdCommand.TOWERS.put("flamethrower_tower", new TowerDef(ModBlocks.FLAMETHROWER_TOWER, 30));` (+ help line if enumerated).

- [ ] **Step 9: Drive the burn manager from the server tick**

In `TowerDefenseMod.java`, find where `WaveManager` is registered/ticked (it hooks `ServerTickEvents.END_SERVER_TICK`). Add a tick that advances burns for the arena world. If `WaveManager` already resolves the active `ServerWorld` each tick, add alongside it:

```java
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			net.minecraft.server.world.ServerWorld world = server.getOverworld();
			net.bubblesky.towerdefense.game.FlameBurnManager.tickAll(world);
		});
```
(If a TD-specific world is used elsewhere, use that instead of `getOverworld()`. Keep it to the world(s) that host the arena.)

- [ ] **Step 10: Assets + build**

Create the `flamethrower_tower` asset set (mirror A1 Step 10; nether-brick/magma textures) and lang keys:

```json
	"block.towerdefense.flamethrower_tower": "Flamethrower Tower",
	"item.towerdefense.flamethrower_tower_arrow": "Flamethrower Tower Arrow",
```

Run: `cd mods/towerdefense && ./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: Manual in-game verification**

`/td buy flamethrower_tower`, place near the spawn path, `/td wave`. Confirm: enemies in a tight pack catch fire, the fire visibly spreads to neighbors, DoT ticks damage over ~4s, kills pay you coins, short range. 

- [ ] **Step 12: Commit**

```bash
git add mods/towerdefense
git commit -m "feat(td): add Flamethrower tower with spreading burn DoT"
```

---

# PART B — Turret veterancy

Adds kill-earned veterancy (levels 0–10, ~2.5× stats at max) to **all** towers by folding a veterancy multiplier into `AbstractTowerBlockEntity`'s derived-stat methods, plus kill tracking and rank-up feedback. Because the two new towers already route through those methods, they inherit veterancy for free.

## Task B1: `TowerVeterancy` pure logic

**Files:**
- Create: `blockentity/TowerVeterancy.java`
- Test: `src/test/java/net/bubblesky/towerdefense/TowerVeterancyTest.java`

**Interfaces:**
- Produces: `TowerVeterancy.MAX_LEVEL` (int, 10); `levelForXp(int xp) -> int`; `xpIntoLevel(int xp) -> int`; `xpForNextLevel(int level) -> int` (0 at max); `xpForKill(float maxHealth) -> int`; `damageMult(int level) -> double`; `rangeBonus(int level) -> double`; `cooldownFactor(int level) -> double`.

- [ ] **Step 1: Write the failing test**

Create `TowerVeterancyTest.java`:

```java
package net.bubblesky.towerdefense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.bubblesky.towerdefense.blockentity.TowerVeterancy;
import org.junit.jupiter.api.Test;

class TowerVeterancyTest {
	@Test
	void startsAtLevelZero() {
		assertEquals(0, TowerVeterancy.levelForXp(0));
		assertEquals(0, TowerVeterancy.levelForXp(4));
	}

	@Test
	void crossesThresholds() {
		assertEquals(1, TowerVeterancy.levelForXp(5));
		assertEquals(2, TowerVeterancy.levelForXp(15));
		assertEquals(10, TowerVeterancy.levelForXp(100000));
	}

	@Test
	void maxLevelIsTenAndTakesHundreds() {
		assertEquals(10, TowerVeterancy.MAX_LEVEL);
		assertTrue(TowerVeterancy.xpForNextLevel(9) > 500, "grind to max should be hundreds of kills");
		assertEquals(0, TowerVeterancy.xpForNextLevel(10)); // maxed: nothing more
	}

	@Test
	void multiplierScalesToAboutTwoPointFive() {
		assertEquals(1.0, TowerVeterancy.damageMult(0), 1.0e-9);
		assertEquals(2.5, TowerVeterancy.damageMult(10), 1.0e-9);
	}

	@Test
	void bossKillsWorthMoreXp() {
		assertEquals(1, TowerVeterancy.xpForKill(20.0f));
		assertTrue(TowerVeterancy.xpForKill(400.0f) > 1);
	}

	@Test
	void cooldownFactorShrinks() {
		assertEquals(1.0, TowerVeterancy.cooldownFactor(0), 1.0e-9);
		assertEquals(0.8, TowerVeterancy.cooldownFactor(10), 1.0e-9);
	}
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd mods/towerdefense && ./gradlew test --tests '*TowerVeterancyTest'`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement `TowerVeterancy`**

Create `blockentity/TowerVeterancy.java`:

```java
package net.bubblesky.towerdefense.blockentity;

/**
 * Pure veterancy math for towers. A tower accrues XP from kills and ranks up through
 * {@link #MAX_LEVEL} levels on a deliberately long, geometric curve. Higher veterancy
 * multiplies damage (up to ~2.5x at max), modestly extends range, and modestly shortens
 * cooldown — stacking on top of the coin-bought tier upgrades. No Minecraft deps so it
 * is unit-testable.
 */
public final class TowerVeterancy {
	public static final int MAX_LEVEL = 10;

	/** Cumulative XP required to REACH each level (index = level; index 0 = 0). */
	private static final int[] CUMULATIVE_XP = {
		0, 5, 15, 35, 65, 110, 175, 265, 385, 540, 750
	};

	private TowerVeterancy() {
	}

	/** Highest level whose cumulative threshold is met by {@code xp}. */
	public static int levelForXp(int xp) {
		int level = 0;
		for (int i = 1; i <= MAX_LEVEL; i++) {
			if (xp >= CUMULATIVE_XP[i]) {
				level = i;
			} else {
				break;
			}
		}
		return level;
	}

	/** XP accumulated within the current level (0 at a fresh level boundary). */
	public static int xpIntoLevel(int xp) {
		int level = levelForXp(xp);
		return xp - CUMULATIVE_XP[level];
	}

	/** XP span from {@code level} to the next; 0 if already max. */
	public static int xpForNextLevel(int level) {
		if (level >= MAX_LEVEL) {
			return 0;
		}
		return CUMULATIVE_XP[level + 1] - CUMULATIVE_XP[level];
	}

	/** XP a single kill grants: 1 base, more for high-max-HP enemies (bosses). Cap +5. */
	public static int xpForKill(float maxHealth) {
		return 1 + Math.min(5, (int) (maxHealth / 40.0f));
	}

	/** Damage multiplier: 1.0 at level 0 → 2.5 at level 10 (+0.15/level). */
	public static double damageMult(int level) {
		return 1.0 + 0.15 * clamp(level);
	}

	/** Bonus range in blocks: up to +2 at level 10. */
	public static double rangeBonus(int level) {
		return 0.2 * clamp(level);
	}

	/** Cooldown factor: 1.0 at level 0 → 0.8 at level 10 (−2%/level). */
	public static double cooldownFactor(int level) {
		return 1.0 - 0.02 * clamp(level);
	}

	private static int clamp(int level) {
		return Math.max(0, Math.min(MAX_LEVEL, level));
	}
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `cd mods/towerdefense && ./gradlew test --tests '*TowerVeterancyTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add mods/towerdefense/src
git commit -m "feat(td): add TowerVeterancy leveling curve + stat scaling math"
```

## Task B2: Wire veterancy state + stat scaling into `AbstractTowerBlockEntity`

**Files:**
- Modify: `blockentity/AbstractTowerBlockEntity.java`

**Interfaces:**
- Consumes: `TowerVeterancy`.
- Produces (new public/protected API on `AbstractTowerBlockEntity`): `int getKills()`, `int getVetXp()`, `int getVetLevel()`, `boolean addKill(float enemyMaxHealth)` (returns true if the level increased), and veterancy-scaled `range()`, `cooldownTicks()`, `damageMultiplier()`.

- [ ] **Step 1: Add fields**

In `AbstractTowerBlockEntity.java`, after the `cooldown` field (line 44) add:

```java
	/** Total kills credited to this tower (drives veterancy XP). */
	protected int kills = 0;
	/** Accumulated veterancy XP. */
	protected int vetXp = 0;
```

- [ ] **Step 2: Add accessors + `addKill`**

After `upgrade()` (line 76) add:

```java
	public int getKills() {
		return kills;
	}

	public int getVetXp() {
		return vetXp;
	}

	public int getVetLevel() {
		return TowerVeterancy.levelForXp(vetXp);
	}

	/** Credit one kill; returns true if this pushed the tower to a new veterancy level. */
	public boolean addKill(float enemyMaxHealth) {
		int before = getVetLevel();
		kills++;
		vetXp += TowerVeterancy.xpForKill(enemyMaxHealth);
		markDirty();
		return getVetLevel() > before;
	}
```
(add `import net.bubblesky.towerdefense.blockentity.TowerVeterancy;` — same package, so no import actually needed; drop the import.)

- [ ] **Step 3: Fold veterancy into the derived-stat methods**

Replace `range()` (84-86), `cooldownTicks()` (89-91), and `damageMultiplier()` (94-96) with:

```java
	/** +2 blocks/tier above 1, plus a veterancy range bonus. */
	protected double range() {
		return baseRange() + (tier - 1) * 2.0 + TowerVeterancy.rangeBonus(getVetLevel());
	}

	/** Cooldown shrinks 20%/tier and is further shortened by veterancy. */
	protected int cooldownTicks() {
		double tierFactor = 1.0 - 0.2 * (tier - 1);
		double vetFactor = TowerVeterancy.cooldownFactor(getVetLevel());
		return Math.max(1, (int) Math.round(baseCooldown() * tierFactor * vetFactor));
	}

	/** Damage/effect multiplier: tier (1.0/1.5/2.0) times the veterancy multiplier. */
	protected double damageMultiplier() {
		return (1.0 + 0.5 * (tier - 1)) * TowerVeterancy.damageMult(getVetLevel());
	}
```

- [ ] **Step 4: Persist kills/vetXp**

In `readData` (after line 168) add:

```java
		this.kills = Math.max(0, view.getInt("kills", 0));
		this.vetXp = Math.max(0, view.getInt("vetXp", 0));
```
In `writeData` (after line 182) add:

```java
		view.putInt("kills", kills);
		view.putInt("vetXp", vetXp);
```

- [ ] **Step 5: Compile**

Run: `cd mods/towerdefense && ./gradlew build`
Expected: BUILD SUCCESSFUL. (All towers now scale with veterancy; nothing yet increments it — that's B3/B4.)

- [ ] **Step 6: Commit**

```bash
git add mods/towerdefense/src/main/java/net/bubblesky/towerdefense/blockentity/AbstractTowerBlockEntity.java
git commit -m "feat(td): scale tower range/cooldown/damage by veterancy level"
```

## Task B3: Projectile kill attribution + rank-up feedback

**Files:**
- Modify: `blockentity/AbstractTowerBlockEntity.java` (add `tagShot` + `TOWER_TAG_PREFIX` + rank-up feedback helper)
- Modify: `blockentity/ArrowTowerBlockEntity.java`, `blockentity/CannonTowerBlockEntity.java`, `blockentity/FrostTowerBlockEntity.java`, `blockentity/BallTowerBlockEntity.java`, `blockentity/SharpshooterTowerBlockEntity.java` (tag each fired projectile)
- Create: `game/VeterancyEvents.java` (AFTER_DEATH → credit tower)
- Modify: `TowerDefenseMod.java` (call `VeterancyEvents.register()` from init)

**Interfaces:**
- Consumes: `AbstractTowerBlockEntity.addKill`, `TowerVeterancy`.
- Produces: `AbstractTowerBlockEntity.tagShot(net.minecraft.entity.Entity projectile)`; `AbstractTowerBlockEntity.TOWER_TAG_PREFIX` (String); `VeterancyEvents.register()`; `VeterancyEvents.creditTowerAt(ServerWorld, BlockPos, float enemyMaxHealth)`.

- [ ] **Step 1: Add the shot-tagging helper + rank-up feedback to the base class**

In `AbstractTowerBlockEntity.java` add a constant near the top of the class:

```java
	/** Command-tag prefix stamped on a tower's projectiles: "tdtower:x,y,z". */
	public static final String TOWER_TAG_PREFIX = "tdtower:";
```
and a method (after `addKill`):

```java
	/** Stamp this tower's position onto a fired projectile so its kill credits this tower. */
	public void tagShot(net.minecraft.entity.Entity projectile) {
		BlockPos p = getPos();
		projectile.addCommandTag(TOWER_TAG_PREFIX + p.getX() + "," + p.getY() + "," + p.getZ());
	}

	/** Play a rank-up burst at the tower and notify the owner. Call when addKill returns true. */
	public void onRankUp(ServerWorld world) {
		BlockPos p = getPos();
		world.playSound(null, p, net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP,
			net.minecraft.sound.SoundCategory.BLOCKS, 0.7f, 1.4f);
		world.spawnParticles(net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER,
			p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5, 20, 0.4, 0.4, 0.4, 0.1);
		ServerPlayerEntity owner = placerPlayer(world);
		if (owner != null) {
			owner.sendMessage(net.minecraft.text.Text.literal(
				kind().id() + " reached veterancy " + getVetLevel() + "!")
				.formatted(net.minecraft.util.Formatting.GOLD), true);
		}
	}
```

- [ ] **Step 2: Tag projectiles in each projectile tower's `fire`**

In `ArrowTowerBlockEntity.fire` (before `world.spawnEntity(arrow);` at line 65) add:

```java
		tagShot(arrow);
```
Do the same in `SharpshooterTowerBlockEntity.fire` (before its `world.spawnEntity(arrow);`). Then open `CannonTowerBlockEntity.java`, `FrostTowerBlockEntity.java`, `BallTowerBlockEntity.java`; in each `fire(...)`, after the projectile entity is constructed and before it is spawned via `world.spawnEntity(<projectile>)`, insert `tagShot(<projectile>);` using that method's local projectile variable name. (Frost/Ball may not spawn a standard projectile — if a tower deals damage some other way, skip tagging it here; it will simply not earn veterancy from projectile kills, which is acceptable for this pass. Note any tower skipped.)

- [ ] **Step 3: Create `VeterancyEvents`**

Create `game/VeterancyEvents.java`:

```java
package net.bubblesky.towerdefense.game;

import net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Credits a tower's veterancy when a projectile it fired lands a killing blow. The tower
 * stamps its position onto each projectile via {@link AbstractTowerBlockEntity#tagShot};
 * here we read that tag off the killing damage source. (Flamethrower burns credit their
 * tower directly through {@link FlameBurnManager}, not through this listener.)
 */
public final class VeterancyEvents {
	private VeterancyEvents() {
	}

	public static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity.getWorld() instanceof ServerWorld world) || !(entity instanceof HostileEntity)) {
				return;
			}
			BlockPos towerPos = towerFrom(source);
			if (towerPos != null) {
				creditTowerAt(world, towerPos, entity.getMaxHealth());
			}
		});
	}

	/** Extract the tower position from a killing projectile's command tag, or null. */
	private static BlockPos towerFrom(DamageSource source) {
		Entity projectile = source.getSource();
		if (projectile == null) {
			return null;
		}
		for (String tag : projectile.getCommandTags()) {
			if (tag.startsWith(AbstractTowerBlockEntity.TOWER_TAG_PREFIX)) {
				String[] xyz = tag.substring(AbstractTowerBlockEntity.TOWER_TAG_PREFIX.length()).split(",");
				if (xyz.length == 3) {
					try {
						return new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]),
							Integer.parseInt(xyz[2]));
					} catch (NumberFormatException ignored) {
						return null;
					}
				}
			}
		}
		return null;
	}

	/** Credit the tower at {@code pos} (if it still exists) with one kill + rank-up feedback. */
	public static void creditTowerAt(ServerWorld world, BlockPos pos, float enemyMaxHealth) {
		if (world.getBlockEntity(pos) instanceof AbstractTowerBlockEntity tower) {
			if (tower.addKill(enemyMaxHealth)) {
				tower.onRankUp(world);
			}
		}
	}
}
```

- [ ] **Step 4: Register from mod init + finish the Flamethrower hook**

In `TowerDefenseMod.java`, wherever other systems register (near `ProgressEvents.register()` / `WaveManager` wiring), add:

```java
		net.bubblesky.towerdefense.game.VeterancyEvents.register();
```
Then in `game/FlameBurnManager.java`, complete the `onBurnKill` stub (Task A2 Step 3):

```java
	private static void onBurnKill(ServerWorld world, BlockPos towerPos) {
		net.bubblesky.towerdefense.game.VeterancyEvents.creditTowerAt(world, towerPos, 0.0f);
	}
```
(0.0f max-HP → `xpForKill` gives the base 1 XP for a burn kill, which is intended.)

- [ ] **Step 5: Build**

Run: `cd mods/towerdefense && ./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Manual verification**

In-game: place an arrow tower, run several waves, and confirm kills accumulate (verified visually once the panel lands in Part D; for now confirm no crashes and that a rank-up plays the level-up sound/particles after enough kills — you can lower `CUMULATIVE_XP` temporarily to test, then restore).

- [ ] **Step 7: Commit**

```bash
git add mods/towerdefense
git commit -m "feat(td): credit tower veterancy on kills with rank-up feedback"
```

---

# PART C — Wave-completion rewards

Drops a scaling loot bundle at a random enemy spawn gate when a wave is cleared.

## Task C1: `WaveRewards` band/scaling logic (pure)

**Files:**
- Create: `game/WaveRewards.java`
- Test: `src/test/java/net/bubblesky/towerdefense/WaveRewardsTest.java`

**Interfaces:**
- Produces: `WaveRewards.bandForWave(int wave) -> int` (0..3); `WaveRewards.subsetSize(int wave, boolean bossWave) -> int`; `WaveRewards.scaledCount(int baseCount, int wave) -> int`; `WaveRewards.rollDrops(int wave, boolean bossWave, java.util.random.RandomGenerator rng) -> java.util.List<net.minecraft.item.ItemStack>` (the item-assembling method; not unit-tested directly).

- [ ] **Step 1: Write the failing test (pure numeric helpers only)**

Create `WaveRewardsTest.java`:

```java
package net.bubblesky.towerdefense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.bubblesky.towerdefense.game.WaveRewards;
import org.junit.jupiter.api.Test;

class WaveRewardsTest {
	@Test
	void bandsStepUpWithWave() {
		assertEquals(0, WaveRewards.bandForWave(1));
		assertEquals(0, WaveRewards.bandForWave(4));
		assertEquals(1, WaveRewards.bandForWave(5));
		assertEquals(2, WaveRewards.bandForWave(10));
		assertEquals(3, WaveRewards.bandForWave(15));
		assertEquals(3, WaveRewards.bandForWave(999));
	}

	@Test
	void bossWavesDrawMore() {
		assertTrue(WaveRewards.subsetSize(10, true) > WaveRewards.subsetSize(10, false));
	}

	@Test
	void countScalesUpButStaysPositive() {
		assertTrue(WaveRewards.scaledCount(4, 1) >= 4);
		assertTrue(WaveRewards.scaledCount(4, 30) > WaveRewards.scaledCount(4, 1));
	}
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd mods/towerdefense && ./gradlew test --tests '*WaveRewardsTest'`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement `WaveRewards`**

Create `game/WaveRewards.java`:

```java
package net.bubblesky.towerdefense.game;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * Decides the wave-completion loot bundle: curated per-band item pools (chosen by wave
 * number) with a random subset drawn each wave and counts that scale gently as waves
 * grow. Boss waves draw a larger bundle. Numeric helpers are pure/testable; item
 * assembly ({@link #rollDrops}) references the item registry and is verified in-game.
 */
public final class WaveRewards {
	/** Inclusive lower wave bound for each band index. */
	private static final int[] BAND_MIN_WAVE = {1, 5, 10, 15};
	private static final int MIN_SUBSET = 3;
	private static final int MAX_SUBSET = 5;
	private static final int BOSS_BONUS_SUBSET = 2;

	/** One entry in a band's pool: item + base count range. */
	private record Entry(Item item, int minCount, int maxCount) {
	}

	/** Curated pools, indexed by band. */
	private static final Entry[][] BANDS = {
		{ // Band 0: waves 1-4 — basics
			new Entry(Items.BREAD, 4, 8),
			new Entry(Items.COOKED_BEEF, 2, 5),
			new Entry(Items.OAK_LOG, 8, 16),
			new Entry(Items.COBBLESTONE, 16, 32),
			new Entry(Items.TORCH, 8, 16),
			new Entry(Items.ARROW, 8, 16),
		},
		{ // Band 1: waves 5-9 — iron age
			new Entry(Items.COOKED_BEEF, 4, 8),
			new Entry(Items.IRON_INGOT, 2, 5),
			new Entry(Items.COAL, 6, 12),
			new Entry(Items.APPLE, 3, 6),
			new Entry(Items.IRON_HELMET, 1, 1),
			new Entry(Items.IRON_SWORD, 1, 1),
		},
		{ // Band 2: waves 10-14 — diamonds
			new Entry(Items.DIAMOND, 1, 3),
			new Entry(Items.GOLDEN_APPLE, 1, 2),
			new Entry(Items.REDSTONE, 6, 12),
			new Entry(Items.ENDER_PEARL, 1, 3),
			new Entry(Items.DIAMOND_CHESTPLATE, 1, 1),
			new Entry(Items.COOKED_BEEF, 8, 12),
		},
		{ // Band 3: waves 15+ — endgame
			new Entry(Items.DIAMOND, 3, 6),
			new Entry(Items.NETHERITE_SCRAP, 1, 2),
			new Entry(Items.GOLDEN_APPLE, 2, 4),
			new Entry(Items.ENCHANTED_GOLDEN_APPLE, 1, 1),
			new Entry(Items.DIAMOND_SWORD, 1, 1),
			new Entry(Items.EXPERIENCE_BOTTLE, 4, 8),
		},
	};

	private WaveRewards() {
	}

	public static int bandForWave(int wave) {
		int band = 0;
		for (int i = 0; i < BAND_MIN_WAVE.length; i++) {
			if (wave >= BAND_MIN_WAVE[i]) {
				band = i;
			}
		}
		return band;
	}

	public static int subsetSize(int wave, boolean bossWave) {
		return Math.min(MAX_SUBSET + (bossWave ? BOSS_BONUS_SUBSET : 0),
			MIN_SUBSET + (bossWave ? BOSS_BONUS_SUBSET : 0));
	}

	/** Gentle linear growth: +3% per wave on the base count, always >= base. */
	public static int scaledCount(int baseCount, int wave) {
		return Math.max(baseCount, (int) Math.round(baseCount * (1.0 + 0.03 * wave)));
	}

	/** Assemble the actual loot stacks for a cleared wave. */
	public static List<ItemStack> rollDrops(int wave, boolean bossWave, RandomGenerator rng) {
		Entry[] pool = BANDS[bandForWave(wave)];
		int n = Math.min(subsetSize(wave, bossWave), pool.length);
		// Shuffle indices via Fisher-Yates and take the first n.
		int[] idx = new int[pool.length];
		for (int i = 0; i < idx.length; i++) {
			idx[i] = i;
		}
		for (int i = idx.length - 1; i > 0; i--) {
			int j = rng.nextInt(i + 1);
			int tmp = idx[i];
			idx[i] = idx[j];
			idx[j] = tmp;
		}
		List<ItemStack> out = new ArrayList<>();
		for (int k = 0; k < n; k++) {
			Entry e = pool[idx[k]];
			int base = e.minCount() + (e.maxCount() > e.minCount() ? rng.nextInt(e.maxCount() - e.minCount() + 1) : 0);
			int count = scaledCount(base, wave);
			out.add(new ItemStack(e.item(), Math.max(1, Math.min(count, e.item().getMaxCount()))));
		}
		if (bossWave) {
			// Guarantee one premium item from the top band on boss waves.
			Entry[] top = BANDS[BANDS.length - 1];
			out.add(new ItemStack(top[rng.nextInt(top.length)].item(), 1));
		}
		return out;
	}
}
```

> If any `Items.*` constant name differs in 1.21.6 mappings, the compiler will flag it at Step 5 — swap to the correct yarn name (e.g. verify `NETHERITE_SCRAP`, `EXPERIENCE_BOTTLE`).

- [ ] **Step 4: Run the test to confirm it passes**

Run: `cd mods/towerdefense && ./gradlew test --tests '*WaveRewardsTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add mods/towerdefense/src
git commit -m "feat(td): add WaveRewards curated loot bands + scaling"
```

## Task C2: Drop the reward at a spawn gate on wave clear

**Files:**
- Modify: `game/WaveManager.java` (`tickActive` wave-clear branch, lines 402-416)

**Interfaces:**
- Consumes: `WaveRewards.rollDrops`, `surfaceSpawn` (private, same class), `st.spawnPoints`, `st.currentWave`, `BOSS_WAVE_INTERVAL`, `broadcast`.

- [ ] **Step 1: Insert the drop after the coin payout**

In `WaveManager.tickActive`, immediately after `TdFeedback.waveClear(world, st);` (line 412) and before `st.phase = ...INTERMISSION` (line 413), add:

```java
			dropWaveReward(server, world, st);
```

- [ ] **Step 2: Add the `dropWaveReward` helper**

Add this private static method to `WaveManager` (near `payNearbyPlayers`, ~line 804):

```java
	/** Drop the wave-completion loot bundle at one random enemy spawn gate. */
	private static void dropWaveReward(MinecraftServer server, ServerWorld world, TdArenaState st) {
		if (st.spawnPoints.isEmpty()) {
			return;
		}
		boolean bossWave = st.currentWave % BOSS_WAVE_INTERVAL == 0;
		java.util.List<net.minecraft.item.ItemStack> loot =
			WaveRewards.rollDrops(st.currentWave, bossWave, world.random);
		BlockPos gate = surfaceSpawn(world, st.spawnPoints.get(world.random.nextInt(st.spawnPoints.size())));
		for (net.minecraft.item.ItemStack stack : loot) {
			ItemEntity drop = new ItemEntity(world, gate.getX() + 0.5, gate.getY() + 0.5, gate.getZ() + 0.5, stack);
			drop.setToDefaultPickupDelay();
			drop.setVelocity((world.random.nextDouble() - 0.5) * 0.2, 0.2, (world.random.nextDouble() - 0.5) * 0.2);
			world.spawnEntity(drop);
		}
		broadcast(server, Text.literal("Wave " + st.currentWave + " rewards dropped at a spawn gate!")
			.formatted(Formatting.AQUA));
	}
```
(`ItemEntity`, `Text`, `Formatting`, `BlockPos` are already imported in `WaveManager`; `world.random` is `net.minecraft.util.math.random.Random`, which implements `RandomGenerator`.)

- [ ] **Step 3: Build**

Run: `cd mods/towerdefense && ./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual verification**

Run a few waves. Confirm: after each clear, a bundle of loose items appears at one spawn gate on the surface (not buried), the chat announces it, item variety/counts grow with wave number, and boss waves (5, 10, …) drop a noticeably bigger haul including a premium item.

- [ ] **Step 5: Commit**

```bash
git add mods/towerdefense/src/main/java/net/bubblesky/towerdefense/game/WaveManager.java
git commit -m "feat(td): drop scaling wave-completion loot at a spawn gate"
```

---

# PART D — "My Towers" panel

A per-player screen (key `K`) listing the towers you placed, with live stats (tier + veterancy), Upgrade, and Sell. Built on the progression networking/PersistentState/Screen templates.

## Task D1: `TowerRegistry` (per-owner tower tracking)

**Files:**
- Create: `state/TowerRegistry.java`
- Test: `src/test/java/net/bubblesky/towerdefense/TowerRegistryLogicTest.java` (pure add/remove/prune-set logic extracted to a helper)

**Interfaces:**
- Produces: `TowerRegistry.get(MinecraftServer) -> TowerRegistry`; `register(UUID owner, BlockPos pos)`; `unregister(UUID owner, BlockPos pos)`; `Set<BlockPos> forOwner(UUID owner)`. Persistent key `"towerdefense_tower_registry"`.

- [ ] **Step 1: Write the failing add/remove test**

Because `PersistentState` needs a server, test the underlying map behavior via a small static helper. Create `TowerRegistryLogicTest.java`:

```java
package net.bubblesky.towerdefense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.bubblesky.towerdefense.state.TowerRegistry;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

class TowerRegistryLogicTest {
	@Test
	void addAndRemoveKeyedByOwner() {
		Map<UUID, Set<Long>> map = new HashMap<>();
		UUID a = new UUID(1L, 1L);
		TowerRegistry.addTo(map, a, new BlockPos(1, 2, 3).asLong());
		TowerRegistry.addTo(map, a, new BlockPos(4, 5, 6).asLong());
		assertEquals(2, map.get(a).size());
		TowerRegistry.removeFrom(map, a, new BlockPos(1, 2, 3).asLong());
		assertEquals(1, map.get(a).size());
		assertTrue(map.get(a).contains(new BlockPos(4, 5, 6).asLong()));
	}

	@Test
	void removingLastDropsOwner() {
		Map<UUID, Set<Long>> map = new HashMap<>();
		UUID a = new UUID(2L, 2L);
		long pos = new BlockPos(0, 0, 0).asLong();
		TowerRegistry.addTo(map, a, pos);
		TowerRegistry.removeFrom(map, a, pos);
		assertFalse(map.containsKey(a));
	}
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd mods/towerdefense && ./gradlew test --tests '*TowerRegistryLogicTest'`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement `TowerRegistry`** (mirror `progression/ProgressState.java`)

Create `state/TowerRegistry.java`:

```java
package net.bubblesky.towerdefense.state;

import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtLongArray;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

/**
 * World-saved map of tower owner UUID → the set of tower core positions they placed.
 * Mirrors {@link net.bubblesky.towerdefense.progression.ProgressState}: lives in the
 * overworld persistent-state manager, Codec via {@code NbtCompound.CODEC.xmap}, one
 * sub-tag (a long array of packed BlockPos) per owner UUID.
 */
public class TowerRegistry extends PersistentState {
	private final Map<UUID, Set<Long>> byOwner = new HashMap<>();

	private static final Codec<TowerRegistry> CODEC =
		NbtCompound.CODEC.xmap(TowerRegistry::fromNbt, TowerRegistry::toNbt);

	public static final PersistentStateType<TowerRegistry> TYPE =
		new PersistentStateType<>("towerdefense_tower_registry", TowerRegistry::new, CODEC, DataFixTypes.LEVEL);

	public TowerRegistry() {
	}

	public static TowerRegistry get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
	}

	public void register(UUID owner, BlockPos pos) {
		addTo(byOwner, owner, pos.asLong());
		markDirty();
	}

	public void unregister(UUID owner, BlockPos pos) {
		removeFrom(byOwner, owner, pos.asLong());
		markDirty();
	}

	/** Snapshot of an owner's tower positions (empty if none). */
	public Set<BlockPos> forOwner(UUID owner) {
		Set<BlockPos> out = new HashSet<>();
		for (long packed : byOwner.getOrDefault(owner, Set.of())) {
			out.add(BlockPos.fromLong(packed));
		}
		return out;
	}

	// ---- pure helpers (unit-tested) ----------------------------------------
	public static void addTo(Map<UUID, Set<Long>> map, UUID owner, long packed) {
		map.computeIfAbsent(owner, k -> new HashSet<>()).add(packed);
	}

	public static void removeFrom(Map<UUID, Set<Long>> map, UUID owner, long packed) {
		Set<Long> set = map.get(owner);
		if (set != null) {
			set.remove(packed);
			if (set.isEmpty()) {
				map.remove(owner);
			}
		}
	}

	// ---- serialization -----------------------------------------------------
	private NbtCompound toNbt() {
		NbtCompound nbt = new NbtCompound();
		NbtCompound owners = new NbtCompound();
		for (Map.Entry<UUID, Set<Long>> e : byOwner.entrySet()) {
			long[] arr = e.getValue().stream().mapToLong(Long::longValue).toArray();
			owners.put(e.getKey().toString(), new NbtLongArray(arr));
		}
		nbt.put("owners", owners);
		return nbt;
	}

	private static TowerRegistry fromNbt(NbtCompound nbt) {
		TowerRegistry state = new TowerRegistry();
		NbtCompound owners = nbt.getCompoundOrEmpty("owners");
		for (String key : owners.getKeys()) {
			try {
				UUID owner = UUID.fromString(key);
				NbtElement el = owners.get(key);
				if (el instanceof NbtLongArray longs) {
					Set<Long> set = new HashSet<>();
					for (long v : longs.getLongArray()) {
						set.add(v);
					}
					state.byOwner.put(owner, set);
				}
			} catch (IllegalArgumentException ignored) {
			}
		}
		return state;
	}
}
```

> Confirm the exact NBT long-array accessor in 1.21.6 yarn (`NbtLongArray#getLongArray` vs `#toArray`); adjust at compile time.

- [ ] **Step 4: Run the test to confirm it passes**

Run: `cd mods/towerdefense && ./gradlew test --tests '*TowerRegistryLogicTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Register/unregister on place & break**

- On placement: in `tower/TowerStructure.build` (line 34, after `TdArenaState.get(...).addTower(pos)`), add:

```java
		if (placer != null) {
			net.bubblesky.towerdefense.state.TowerRegistry.get(world.getServer()).register(placer, pos);
		}
```
- On break: find where tower blocks are broken/removed. If there's no existing block-break hook, add one in `TowerDefenseMod`:

```java
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
			if (be instanceof net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity tower && tower.getPlacerUuid() != null
					&& world instanceof net.minecraft.server.world.ServerWorld sw) {
				net.bubblesky.towerdefense.state.TowerRegistry.get(sw.getServer()).unregister(tower.getPlacerUuid(), pos);
			}
		});
```
For this you need a `getPlacerUuid()` accessor on `AbstractTowerBlockEntity` — add:

```java
	@Nullable
	public UUID getPlacerUuid() {
		return placer;
	}
```

- [ ] **Step 6: Build + commit**

Run: `cd mods/towerdefense && ./gradlew build` (expect SUCCESS).

```bash
git add mods/towerdefense
git commit -m "feat(td): add TowerRegistry tracking towers per owner"
```

## Task D2: Shared upgrade/sell service + `invested` tracking

**Files:**
- Modify: `blockentity/AbstractTowerBlockEntity.java` (add `invested` field + accessors + persistence)
- Create: `command/TowerService.java` (shared upgrade/sell logic)
- Modify: `command/TdCommand.java` (route `/td upgrade` through `TowerService`; set `invested` on buy; add `/td towers` opener later in D4)

**Interfaces:**
- Produces: `AbstractTowerBlockEntity.getInvested()`, `addInvested(int)`, `setInvested(int)`; `TowerService.upgradeCost(ServerWorld, AbstractTowerBlockEntity) -> int`; `TowerService.refund(AbstractTowerBlockEntity) -> int`; `TowerService.upgrade(ServerWorld, ServerPlayerEntity, BlockPos) -> Result`; `TowerService.sell(ServerWorld, ServerPlayerEntity, BlockPos) -> Result` where `Result` is a small record `{boolean ok, String message}`.

- [ ] **Step 1: Add `invested` to the block entity**

In `AbstractTowerBlockEntity.java` add field (after `vetXp`): `protected int invested = 0;` and accessors:

```java
	public int getInvested() {
		return invested;
	}

	public void addInvested(int coins) {
		invested += coins;
		markDirty();
	}

	public void setInvested(int coins) {
		invested = coins;
		markDirty();
	}
```
Persist it: in `readData` add `this.invested = Math.max(0, view.getInt("invested", 0));`; in `writeData` add `view.putInt("invested", invested);`.

- [ ] **Step 2: Record `invested` on buy + upgrade**

In `TdCommand.buy` (after `removeCoins(player, def.price());`, line 316) the tower block is only *given* to the player, not yet placed — so set `invested` at **placement** instead: in `TowerStructure.build`, after registering, seed invested from the catalogue price. Since `TowerStructure` doesn't know the price, add a hook: after `TowerRegistry...register(...)` in `build`, set:

```java
		if (world.getBlockEntity(pos) instanceof AbstractTowerBlockEntity tower) {
			tower.setInvested(net.bubblesky.towerdefense.command.TdCommand.priceOfPublic(pos, world));
		}
```
Add a public accessor to `TdCommand` mirroring the private `priceOf`:

```java
	/** Public catalogue price of the tower block at a position (default 10). */
	public static int priceOfPublic(BlockPos pos, ServerWorld world) {
		return priceOf(world, pos);
	}
```
In `TdCommand.upgrade`, after `removeCoins(player, cost);` (line 353) add `tower.addInvested(cost);`.

- [ ] **Step 3: Create `TowerService`**

Create `command/TowerService.java`. It owns the reusable upgrade/sell logic so both the command and the panel share one implementation:

```java
package net.bubblesky.towerdefense.command;

import net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity;
import net.bubblesky.towerdefense.state.TowerRegistry;
import net.bubblesky.towerdefense.tower.TowerStructure;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Shared upgrade/sell operations for towers, used by both {@code /td} and the My Towers panel. */
public final class TowerService {
	/** Fraction of coins invested returned on sell. */
	public static final double REFUND_FRACTION = 0.5;

	public record Result(boolean ok, String message) {
	}

	private TowerService() {
	}

	/** Cost to upgrade this tower to its next tier = base price × current tier. */
	public static int upgradeCost(ServerWorld world, AbstractTowerBlockEntity tower) {
		return TdCommand.priceOfPublic(tower.getPos(), world) * tower.getTier();
	}

	/** Coins returned if this tower is sold now. */
	public static int refund(AbstractTowerBlockEntity tower) {
		return (int) Math.floor(tower.getInvested() * REFUND_FRACTION);
	}

	public static Result upgrade(ServerWorld world, ServerPlayerEntity player, BlockPos pos) {
		if (!(world.getBlockEntity(pos) instanceof AbstractTowerBlockEntity tower)) {
			return new Result(false, "That tower no longer exists.");
		}
		if (!player.getUuid().equals(tower.getPlacerUuid())) {
			return new Result(false, "You can only upgrade your own towers.");
		}
		if (tower.getTier() >= AbstractTowerBlockEntity.MAX_TIER) {
			return new Result(false, "Already at max tier.");
		}
		int cost = upgradeCost(world, tower);
		if (TdCommand.countCoinsPublic(player) < cost) {
			return new Result(false, "Not enough coins: need " + cost + ".");
		}
		TdCommand.removeCoinsPublic(player, cost);
		tower.upgrade();
		tower.setPlacer(player.getUuid());
		tower.addInvested(cost);
		return new Result(true, "Upgraded to tier " + tower.getTier() + " for " + cost + " coins.");
	}

	public static Result sell(ServerWorld world, ServerPlayerEntity player, BlockPos pos) {
		if (!(world.getBlockEntity(pos) instanceof AbstractTowerBlockEntity tower)) {
			return new Result(false, "That tower no longer exists.");
		}
		if (!player.getUuid().equals(tower.getPlacerUuid())) {
			return new Result(false, "You can only sell your own towers.");
		}
		int refund = refund(tower);
		java.util.UUID owner = tower.getPlacerUuid();
		TowerStructure.clear(world, pos);
		if (owner != null) {
			TowerRegistry.get(world.getServer()).unregister(owner, pos);
		}
		if (refund > 0) {
			ItemStack coins = new ItemStack(net.bubblesky.towerdefense.registry.ModItems.COIN, refund);
			if (!player.getInventory().insertStack(coins)) {
				player.dropItem(coins, false);
			}
		}
		return new Result(true, "Sold tower for " + refund + " coins.");
	}
}
```
Add the two public passthroughs to `TdCommand` (mirroring the private helpers):

```java
	public static int countCoinsPublic(ServerPlayerEntity player) {
		return countCoins(player);
	}

	public static void removeCoinsPublic(ServerPlayerEntity player, int amount) {
		removeCoins(player, amount);
	}
```

- [ ] **Step 4: Route `/td upgrade` through the service**

Replace the body of `TdCommand.upgrade` (lines 342-357) so it aims at a tower then delegates:

```java
		BlockPos pos = tower.getPos();
		TowerService.Result r = TowerService.upgrade(world, player, pos);
		if (!r.ok()) {
			src.sendError(Text.literal(r.message()));
			return 0;
		}
		src.sendFeedback(() -> Text.literal(r.message()).formatted(Formatting.GREEN), false);
		return 1;
```
(Keep the `lookedAtTower` null-check above it; delete the now-duplicated tier/cost/coins logic it replaces.)

- [ ] **Step 5: Build + commit**

Run: `cd mods/towerdefense && ./gradlew build` (expect SUCCESS).

```bash
git add mods/towerdefense
git commit -m "feat(td): shared TowerService upgrade/sell + invested tracking"
```

## Task D3: Roster networking (S2C snapshot + C2S action)

**Files:**
- Create: `towerui/net/TowerRosterPayload.java` (S2C)
- Create: `towerui/net/TowerActionPayload.java` (C2S)
- Create: `towerui/TowerUiEvents.java` (register payloads + server handlers + `sendRoster`)
- Create: `client/ClientTowers.java` (client snapshot cache)
- Modify: `TowerDefenseMod.java` (call `TowerUiEvents.register()`)

**Interfaces:**
- Produces:
  - `TowerRosterPayload(int coins, java.util.List<TowerRosterPayload.Row> rows)` where `Row` is a record `(long posId, int kindOrdinal, int tier, int range, int cooldownTicks, int dmgPct, int upgradeCost, int refund, boolean maxed, int vetLevel, int vetXpInto, int vetXpNext, int kills)`; `ID`, `CODEC` (hand-rolled).
  - `TowerActionPayload(long posId, int action)` with `ACTION_UPGRADE=0`, `ACTION_SELL=1`; `CODEC` via `PacketCodec.tuple` (two `VAR_LONG`/`VAR_INT`).
  - `TowerUiEvents.register()`; `TowerUiEvents.sendRoster(ServerPlayerEntity)`.
  - `ClientTowers.update(TowerRosterPayload)`, `ClientTowers.coins()`, `ClientTowers.rows()`.

- [ ] **Step 1: S2C payload** — create `towerui/net/TowerRosterPayload.java`, modeled on `ProgressSyncPayload` (hand-rolled `PacketCodec.of(write, read)`):

```java
package net.bubblesky.towerdefense.towerui.net;

import java.util.ArrayList;
import java.util.List;
import net.bubblesky.towerdefense.TowerDefenseMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** S2C: a player's owned-tower roster snapshot, pushed on open and after each action. */
public record TowerRosterPayload(int coins, List<Row> rows) implements CustomPayload {

	public record Row(long posId, int kindOrdinal, int tier, int range, int cooldownTicks, int dmgPct,
		int upgradeCost, int refund, boolean maxed, int vetLevel, int vetXpInto, int vetXpNext, int kills) {
	}

	public static final CustomPayload.Id<TowerRosterPayload> ID =
		new CustomPayload.Id<>(Identifier.of(TowerDefenseMod.MOD_ID, "tower_roster"));

	public static final PacketCodec<RegistryByteBuf, TowerRosterPayload> CODEC =
		PacketCodec.of(TowerRosterPayload::write, TowerRosterPayload::read);

	private void write(RegistryByteBuf buf) {
		buf.writeVarInt(coins);
		buf.writeVarInt(rows.size());
		for (Row r : rows) {
			buf.writeLong(r.posId());
			buf.writeVarInt(r.kindOrdinal());
			buf.writeVarInt(r.tier());
			buf.writeVarInt(r.range());
			buf.writeVarInt(r.cooldownTicks());
			buf.writeVarInt(r.dmgPct());
			buf.writeVarInt(r.upgradeCost());
			buf.writeVarInt(r.refund());
			buf.writeBoolean(r.maxed());
			buf.writeVarInt(r.vetLevel());
			buf.writeVarInt(r.vetXpInto());
			buf.writeVarInt(r.vetXpNext());
			buf.writeVarInt(r.kills());
		}
	}

	private static TowerRosterPayload read(RegistryByteBuf buf) {
		int coins = buf.readVarInt();
		int n = buf.readVarInt();
		List<Row> rows = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			rows.add(new Row(buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
				buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
				buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
		}
		return new TowerRosterPayload(coins, rows);
	}

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
```

- [ ] **Step 2: C2S payload** — create `towerui/net/TowerActionPayload.java`, modeled on `AllocatePointPayload`:

```java
package net.bubblesky.towerdefense.towerui.net;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** C2S: perform an action on the tower at {@code posId}. */
public record TowerActionPayload(long posId, int action) implements CustomPayload {
	public static final int ACTION_UPGRADE = 0;
	public static final int ACTION_SELL = 1;

	public static final CustomPayload.Id<TowerActionPayload> ID =
		new CustomPayload.Id<>(Identifier.of(TowerDefenseMod.MOD_ID, "tower_action"));

	public static final PacketCodec<RegistryByteBuf, TowerActionPayload> CODEC = PacketCodec.tuple(
		PacketCodecs.VAR_LONG, TowerActionPayload::posId,
		PacketCodecs.VAR_INT, TowerActionPayload::action,
		TowerActionPayload::new);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
```

- [ ] **Step 3: Server events** — create `towerui/TowerUiEvents.java`:

```java
package net.bubblesky.towerdefense.towerui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity;
import net.bubblesky.towerdefense.command.TowerService;
import net.bubblesky.towerdefense.state.TowerRegistry;
import net.bubblesky.towerdefense.towerui.net.TowerActionPayload;
import net.bubblesky.towerdefense.towerui.net.TowerRosterPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Registers the My Towers panel networking and server-side action handling. */
public final class TowerUiEvents {
	private TowerUiEvents() {
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(TowerRosterPayload.ID, TowerRosterPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(TowerActionPayload.ID, TowerActionPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(TowerActionPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			ServerWorld world = (ServerWorld) player.getWorld();
			BlockPos pos = BlockPos.fromLong(payload.posId());
			TowerService.Result r = payload.action() == TowerActionPayload.ACTION_SELL
				? TowerService.sell(world, player, pos)
				: TowerService.upgrade(world, player, pos);
			if (!r.ok()) {
				player.sendMessage(net.minecraft.text.Text.literal(r.message())
					.formatted(net.minecraft.util.Formatting.RED), true);
			}
			sendRoster(player);
		});
	}

	/** Build and push the player's current tower roster. Prunes stale registry entries. */
	public static void sendRoster(ServerPlayerEntity player) {
		ServerWorld world = (ServerWorld) player.getWorld();
		UUID owner = player.getUuid();
		TowerRegistry registry = TowerRegistry.get(world.getServer());
		Set<BlockPos> positions = registry.forOwner(owner);
		List<TowerRosterPayload.Row> rows = new ArrayList<>();
		for (BlockPos pos : positions) {
			if (!(world.getBlockEntity(pos) instanceof AbstractTowerBlockEntity tower)
					|| !owner.equals(tower.getPlacerUuid())) {
				registry.unregister(owner, pos); // self-heal: prune stale/foreign entries
				continue;
			}
			boolean maxed = tower.getTier() >= AbstractTowerBlockEntity.MAX_TIER;
			int vetLevel = tower.getVetLevel();
			rows.add(new TowerRosterPayload.Row(
				pos.asLong(),
				tower.kind().ordinal(),
				tower.getTier(),
				(int) Math.round(tower.getDisplayRange()),
				tower.getDisplayCooldown(),
				(int) Math.round(tower.getDisplayDamageMultiplier() * 100),
				maxed ? 0 : TowerService.upgradeCost(world, tower),
				TowerService.refund(tower),
				maxed,
				vetLevel,
				net.bubblesky.towerdefense.blockentity.TowerVeterancy.xpIntoLevel(tower.getVetXp()),
				net.bubblesky.towerdefense.blockentity.TowerVeterancy.xpForNextLevel(vetLevel),
				tower.getKills()));
		}
		ServerPlayNetworking.send(player, new TowerRosterPayload(
			net.bubblesky.towerdefense.command.TdCommand.countCoinsPublic(player), rows));
	}
}
```
This needs three small **public display accessors** on `AbstractTowerBlockEntity` (the stat methods are `protected`); add them:

```java
	public double getDisplayRange() {
		return range();
	}

	public int getDisplayCooldown() {
		return cooldownTicks();
	}

	public double getDisplayDamageMultiplier() {
		return damageMultiplier();
	}
```

- [ ] **Step 4: Client cache** — create `client/ClientTowers.java` mirroring `ClientProgress`:

```java
package net.bubblesky.towerdefense.client;

import java.util.List;
import net.bubblesky.towerdefense.towerui.net.TowerRosterPayload;

/** Client-side cache of the last tower-roster snapshot from the server. */
public final class ClientTowers {
	private static volatile int coins = 0;
	private static volatile List<TowerRosterPayload.Row> rows = List.of();

	private ClientTowers() {
	}

	public static void update(TowerRosterPayload payload) {
		coins = payload.coins();
		rows = List.copyOf(payload.rows());
	}

	public static int coins() {
		return coins;
	}

	public static List<TowerRosterPayload.Row> rows() {
		return rows;
	}
}
```

- [ ] **Step 5: Register from init** — in `TowerDefenseMod.java` add `net.bubblesky.towerdefense.towerui.TowerUiEvents.register();` next to the other `register()` calls.

- [ ] **Step 6: Build + commit**

Run: `cd mods/towerdefense && ./gradlew build` (expect SUCCESS).

```bash
git add mods/towerdefense
git commit -m "feat(td): My Towers roster networking (S2C snapshot + C2S actions)"
```

## Task D4: `MyTowersScreen` + keybind + client receiver

**Files:**
- Create: `client/screen/MyTowersScreen.java`
- Modify: `client/TowerDefenseModClient.java` (add key `K`, register S2C receiver, request roster on open)
- Modify: `assets/towerdefense/lang/en_us.json` (keybind + screen strings)
- Modify: `command/TdCommand.java` (optional `/td towers` note — the panel is client-opened, so the command just tells players to press K)

**Interfaces:**
- Consumes: `ClientTowers`, `TowerRosterPayload.Row`, `TowerActionPayload`, `TowerKind` (for icons/names).

- [ ] **Step 1: Create `MyTowersScreen`** (front-end over `ClientTowers`, mirrors `CharacterScreen` render/draw idioms — `context.fill`, `drawTextWithShadow`, `ButtonWidget`):

```java
package net.bubblesky.towerdefense.client.screen;

import java.util.List;
import net.bubblesky.towerdefense.client.ClientTowers;
import net.bubblesky.towerdefense.tower.TowerKind;
import net.bubblesky.towerdefense.towerui.net.TowerActionPayload;
import net.bubblesky.towerdefense.towerui.net.TowerRosterPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The "My Towers" panel (opened with {@code K}). Left: a grid of the player's placed
 * towers; click one to select. Right: the selected tower's live stats (tier + veterancy)
 * with Upgrade and Sell buttons that send a {@link TowerActionPayload}; the server
 * validates and resyncs, so the panel updates live.
 */
public class MyTowersScreen extends Screen {
	private static final int COLS = 4;
	private static final int ICON = 34;
	private int selected = 0;
	private ButtonWidget upgradeButton;
	private ButtonWidget sellButton;

	public MyTowersScreen() {
		super(Text.literal("My Towers"));
	}

	@Override
	protected void init() {
		int detailX = this.width / 2 + 20;
		int by = this.height - 60;
		upgradeButton = ButtonWidget.builder(Text.literal("Upgrade"), b -> act(TowerActionPayload.ACTION_UPGRADE))
			.dimensions(detailX, by, 160, 20).build();
		sellButton = ButtonWidget.builder(Text.literal("Sell"), b -> act(TowerActionPayload.ACTION_SELL))
			.dimensions(detailX, by + 24, 160, 20).build();
		this.addDrawableChild(upgradeButton);
		this.addDrawableChild(sellButton);
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> this.close())
			.dimensions(this.width / 2 - 50, this.height - 28, 100, 20).build());
	}

	private void act(int action) {
		List<TowerRosterPayload.Row> rows = ClientTowers.rows();
		if (selected >= 0 && selected < rows.size()) {
			ClientPlayNetworking.send(new TowerActionPayload(rows.get(selected).posId(), action));
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		int gridX = this.width / 2 - 20 - COLS * ICON;
		int gridY = 60;
		List<TowerRosterPayload.Row> rows = ClientTowers.rows();
		for (int i = 0; i < rows.size(); i++) {
			int x = gridX + (i % COLS) * ICON;
			int y = gridY + (i / COLS) * ICON;
			if (mouseX >= x && mouseX < x + ICON - 2 && mouseY >= y && mouseY < y + ICON - 2) {
				selected = i;
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int cx = this.width / 2;
		context.drawCenteredTextWithShadow(this.textRenderer,
			Text.literal("My Towers").formatted(Formatting.GOLD, Formatting.BOLD), cx, 16, 0xFFFFFFFF);
		context.drawTextWithShadow(this.textRenderer,
			Text.literal("Coins: " + ClientTowers.coins()).formatted(Formatting.YELLOW),
			cx + 20, 34, 0xFFFFFFFF);

		List<TowerRosterPayload.Row> rows = ClientTowers.rows();
		int gridX = cx - 20 - COLS * ICON;
		int gridY = 60;
		for (int i = 0; i < rows.size(); i++) {
			int x = gridX + (i % COLS) * ICON;
			int y = gridY + (i / COLS) * ICON;
			int bg = i == selected ? 0xFF4A4A4A : 0xFF2A2A2A;
			context.fill(x, y, x + ICON - 2, y + ICON - 2, bg);
			TowerKind kind = TowerKind.fromOrdinal(rows.get(i).kindOrdinal());
			String badge = rows.get(i).maxed() ? "M" : String.valueOf(rows.get(i).tier());
			context.drawTextWithShadow(this.textRenderer, Text.literal(kindShort(kind)), x + 4, y + 4, 0xFFFFFFFF);
			context.drawTextWithShadow(this.textRenderer,
				Text.literal("T" + badge).formatted(Formatting.AQUA), x + 4, y + 16, 0xFFFFFFFF);
			context.drawTextWithShadow(this.textRenderer,
				Text.literal("★" + rows.get(i).vetLevel()).formatted(Formatting.GOLD), x + 17, y + 16, 0xFFFFFFFF);
		}

		renderDetail(context, rows);
	}

	private void renderDetail(DrawContext context, List<TowerRosterPayload.Row> rows) {
		int x = this.width / 2 + 20;
		int y = 60;
		boolean has = selected >= 0 && selected < rows.size();
		upgradeButton.active = has && !rows.get(selected).maxed();
		sellButton.active = has;
		if (!has) {
			context.drawTextWithShadow(this.textRenderer,
				Text.literal("No towers yet. Buy one with /td buy.").formatted(Formatting.GRAY), x, y, 0xFFFFFFFF);
			return;
		}
		TowerRosterPayload.Row r = rows.get(selected);
		TowerKind kind = TowerKind.fromOrdinal(r.kindOrdinal());
		String[] lines = {
			kind.id(),
			"Tier " + r.tier() + (r.maxed() ? " (MAX)" : ""),
			"Veterancy ★" + r.vetLevel() + "  (dmg " + r.dmgPct() + "%)",
			r.vetLevel() >= 10 ? "Veterancy maxed" : (r.vetXpInto() + " / " + r.vetXpNext() + " XP  (" + r.kills() + " kills)"),
			"Range " + r.range() + "   Cooldown " + String.format("%.1fs", r.cooldownTicks() / 20.0),
			r.maxed() ? "Upgrade: MAX" : ("Upgrade cost: " + r.upgradeCost() + " coins"),
			"Sell refund: " + r.refund() + " coins",
		};
		for (int i = 0; i < lines.length; i++) {
			context.drawTextWithShadow(this.textRenderer, Text.literal(lines[i]), x, y + i * 12, 0xFFFFFFFF);
		}
		upgradeButton.setMessage(Text.literal(r.maxed() ? "Upgrade — MAX" : "Upgrade — " + r.upgradeCost() + " coins"));
		sellButton.setMessage(Text.literal("Sell — +" + r.refund() + " coins"));
	}

	private static String kindShort(TowerKind kind) {
		return kind.id().replace("_tower", "");
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
```

- [ ] **Step 2: Keybind + receiver + open-request**

In `TowerDefenseModClient.java`: add a field `private static KeyBinding towersKey;`; register it after `inventoryKey` (line 102):

```java
		towersKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.towerdefense.open_towers",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_K,
			KEY_CATEGORY));
```
Register the S2C receiver next to the progress receiver (line 105):

```java
		ClientPlayNetworking.registerGlobalReceiver(TowerRosterPayload.ID,
			(payload, context) -> ClientTowers.update(payload));
```
In the `END_CLIENT_TICK` dispatch (lines 111-129) add handling: drain `towersKey`, and when pressed with no screen open, request a fresh roster then open the screen. Since the roster is server-authoritative, send an action-less "open" by simply opening the screen and asking the server to push — add a lightweight approach: reuse `TowerActionPayload` with a special no-op? No — instead, register a **C2S open-request**. Simplest: send the roster when the client asks via a tiny dedicated payload. To avoid a third payload, have the client open the screen immediately (it renders from cache) AND send a `TowerActionPayload(0L, -1)` treated server-side as "just resync": in `TowerUiEvents` handler, if `action` is neither UPGRADE nor SELL, skip the service call and only `sendRoster(player)`. Add key handling:

```java
			boolean openTowers = false;
			while (towersKey.wasPressed()) openTowers = true;
```
and in the open branch:

```java
				} else if (openTowers) {
					ClientPlayNetworking.send(new net.bubblesky.towerdefense.towerui.net.TowerActionPayload(0L, -1));
					client.setScreen(new net.bubblesky.towerdefense.client.screen.MyTowersScreen());
				}
```
(add imports for `TowerRosterPayload`, `ClientTowers`, `MyTowersScreen`.) Confirm the `TowerUiEvents` receiver already tolerates `action == -1` by only resyncing (it does: the ternary calls `upgrade` for any non-SELL action — **fix that**: guard so `action` must be `ACTION_UPGRADE` to upgrade, else if `ACTION_SELL` sell, else just resync).

Update the `TowerUiEvents` handler (Task D3 Step 3) accordingly:

```java
			TowerService.Result r = null;
			if (payload.action() == TowerActionPayload.ACTION_UPGRADE) {
				r = TowerService.upgrade(world, player, pos);
			} else if (payload.action() == TowerActionPayload.ACTION_SELL) {
				r = TowerService.sell(world, player, pos);
			}
			if (r != null && !r.ok()) {
				player.sendMessage(net.minecraft.text.Text.literal(r.message())
					.formatted(net.minecraft.util.Formatting.RED), true);
			}
			sendRoster(player);
```

- [ ] **Step 3: Lang strings**

Add to `en_us.json`:

```json
	"key.towerdefense.open_towers": "Open My Towers (K)",
	"screen.towerdefense.towers.title": "My Towers",
```

- [ ] **Step 4: Build**

Run: `cd mods/towerdefense && ./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Manual end-to-end verification**

In-game: buy/place several towers of different kinds, press **K**. Confirm: only YOUR towers show; the grid selects on click; the detail pane shows tier, veterancy level/XP/kills, range, cooldown, upgrade cost, refund; **Upgrade** spends coins and the panel updates live (tier +1, cost changes, disables at MAX); **Sell** removes the tower in-world, refunds ~50% of invested, and the tower disappears from the grid. Run a wave and confirm kills tick up the veterancy shown in the panel. Break a tower manually and confirm it drops off the roster (self-heal).

- [ ] **Step 6: Commit**

```bash
git add mods/towerdefense
git commit -m "feat(td): My Towers panel screen, K keybind, live upgrade/sell"
```

---

## Final verification

- [ ] Run the full unit suite: `cd mods/towerdefense && ./gradlew test` → all pass.
- [ ] Full build: `./gradlew build` → SUCCESS.
- [ ] One combined in-game playthrough exercising all four features together (buy new towers, let towers rank up, clear waves and grab spawn-gate loot, manage towers via the K panel).

## Spec coverage self-check (done while writing — no open gaps)

- Feature 1 (My Towers panel): Tasks D1–D4 (tracking, service, networking, screen; upgrade + sell + veterancy display). ✅
- Feature 2 (Wave rewards): Tasks C1–C2 (curated bands + randomness, one random gate, boss-wave bonus, loose drops, broadcast). ✅
- Feature 3 (New towers): Tasks A1–A2 (Sharpshooter armor-piercing/crit + toughest-target; Flamethrower spreading burn). ✅
- Feature 4 (Veterancy): Tasks B1–B3 (10-level curve, stat scaling ~2.5×, kill attribution, rank-up feedback; auto-applies to all towers incl. new ones). ✅

## Notes / risks flagged for the implementer

- **Yarn API name drift (1.21.6):** a few referenced names should be confirmed at first compile and corrected if the mappings differ: `NbtLongArray` accessor, `world.getDamageSources().playerAttack/onFire`, `setOnFireForTicks`, `ButtonWidget#setMessage`, and the `Items.*` constants in `WaveRewards`. The compiler pinpoints any mismatch; fix in place.
- **Frost/Ball projectile tagging:** if either doesn't fire a taggable projectile, it simply won't earn veterancy from kills this pass (noted in Task B3 Step 2). Acceptable; revisit if desired.
- **`TowerStructure.clear` vs registry:** sell (Task D2/D3) explicitly unregisters from `TowerRegistry`; `clear` alone does not, by design.
- **FlameBurnManager world scope:** Task A2 Step 9 ticks `server.getOverworld()`. If the arena can live in another dimension, tick that world instead (or all loaded worlds).
