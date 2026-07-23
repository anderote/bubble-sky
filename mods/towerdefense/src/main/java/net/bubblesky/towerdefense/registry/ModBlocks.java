package net.bubblesky.towerdefense.registry;

import java.util.function.Function;
import net.bubblesky.towerdefense.TowerDefenseMod;
import net.bubblesky.towerdefense.block.AcidFluidBlock;
import net.bubblesky.towerdefense.block.ArrowTowerBlock;
import net.bubblesky.towerdefense.block.BallTowerBlock;
import net.bubblesky.towerdefense.block.BallistaTowerBlock;
import net.bubblesky.towerdefense.block.BeaconTowerBlock;
import net.bubblesky.towerdefense.block.CannonTowerBlock;
import net.bubblesky.towerdefense.block.FlameTowerBlock;
import net.bubblesky.towerdefense.block.FrostTowerBlock;
import net.bubblesky.towerdefense.block.LightningTowerBlock;
import net.bubblesky.towerdefense.block.SharpshooterTowerBlock;
import net.bubblesky.towerdefense.block.MasonTowerBlock;
import net.bubblesky.towerdefense.block.PoisonTowerBlock;
import net.bubblesky.towerdefense.block.SnareTowerBlock;
import net.bubblesky.towerdefense.item.TowerBlockItem;
import net.bubblesky.towerdefense.tower.TowerKind;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

/**
 * Registers the mod's blocks. Currently just the {@code arrow_tower} — the core
 * tower-defense piece.
 */
public final class ModBlocks {
	private ModBlocks() {
	}

	// Tower blocks use a TowerBlockItem so PLACING the block raises the whole tower
	// (structure for ARROW/CANNON/FROST, single sticky turret for BALL) — see /td buy.
	public static final Block ARROW_TOWER = registerTower("arrow_tower",
		ArrowTowerBlock::new,
		AbstractBlock.Settings.create()
			.strength(3.0f, 6.0f)
			.requiresTool()
			.nonOpaque()
			.sounds(BlockSoundGroup.STONE),
		TowerKind.ARROW);

	public static final Block CANNON_TOWER = registerTower("cannon_tower",
		CannonTowerBlock::new,
		AbstractBlock.Settings.create()
			.strength(4.0f, 8.0f)
			.requiresTool()
			.nonOpaque()
			.sounds(BlockSoundGroup.STONE),
		TowerKind.CANNON);

	public static final Block FROST_TOWER = registerTower("frost_tower",
		FrostTowerBlock::new,
		AbstractBlock.Settings.create()
			.strength(3.0f, 6.0f)
			.requiresTool()
			.nonOpaque()
			.sounds(BlockSoundGroup.GLASS),
		TowerKind.FROST);

	// Slow, powerful, higher-cost tower: smites the nearest enemy with a lightning bolt
	// and chains to a couple of nearby ones. No projectile, so nothing piles up.
	public static final Block LIGHTNING_TOWER = registerTower("lightning_tower",
		LightningTowerBlock::new,
		AbstractBlock.Settings.create()
			.strength(3.5f, 7.0f)
			.requiresTool()
			.nonOpaque()
			.sounds(BlockSoundGroup.METAL),
		TowerKind.LIGHTNING);

	// Fast, close-range incinerator: sprays a cone of flame, torches bunched enemies and
	// leaves a lingering (particle-only, block-free) burning-ground patch under the target.
	public static final Block FLAME_TOWER = registerTower("flame_tower",
		FlameTowerBlock::new,
		AbstractBlock.Settings.create()
			.strength(3.0f, 6.0f)
			.requiresTool()
			.nonOpaque()
			.sounds(BlockSoundGroup.STONE),
		TowerKind.FLAME);

	// Long-range, slow-firing precision tower: targets the toughest enemy in range (not
	// the nearest) and hits it hard with a flat-plus-percent-max-HP shot, sometimes crit.
	public static final Block SHARPSHOOTER_TOWER = registerTower("sharpshooter_tower",
		SharpshooterTowerBlock::new,
		AbstractBlock.Settings.create()
			.strength(3.5f, 7.0f)
			.requiresTool()
			.nonOpaque()
			.sounds(BlockSoundGroup.STONE),
		TowerKind.SHARPSHOOTER);

	// The single-block sticky mini turret (no pole/orb). Placeable on any face.
	public static final Block BALL_TOWER = registerTower("ball_tower",
		BallTowerBlock::new,
		AbstractBlock.Settings.create()
			.strength(2.0f, 4.0f)
			.requiresTool()
			.nonOpaque()
			.sounds(BlockSoundGroup.STONE),
		TowerKind.BALL);

	public static final Block SNARE_TOWER = registerTower("snare_tower",
		SnareTowerBlock::new,
		AbstractBlock.Settings.create()
			.strength(3.0f, 6.0f)
			.requiresTool()
			.nonOpaque()
			.sounds(BlockSoundGroup.ROOTS),
		TowerKind.SNARE);

	public static final Block BALLISTA_TOWER = registerTower("ballista_tower",
		BallistaTowerBlock::new,
		AbstractBlock.Settings.create()
			.strength(4.0f, 8.0f)
			.requiresTool()
			.nonOpaque()
			.sounds(BlockSoundGroup.WOOD),
		TowerKind.BALLISTA);

	public static final Block MASON_TOWER = registerTower("mason_tower",
		MasonTowerBlock::new,
		AbstractBlock.Settings.create()
			.strength(5.0f, 10.0f)
			.requiresTool()
			.nonOpaque()
			.sounds(BlockSoundGroup.STONE),
		TowerKind.MASON);

	public static final Block BEACON_TOWER = registerTower("beacon_tower",
		BeaconTowerBlock::new,
		AbstractBlock.Settings.create()
			.strength(3.5f, 7.0f)
			.requiresTool()
			.nonOpaque()
			.luminance(s -> 8)
			.sounds(BlockSoundGroup.GLASS),
		TowerKind.BEACON);

	public static final Block POISON_TOWER = registerTower("poison_tower",
		PoisonTowerBlock::new,
		AbstractBlock.Settings.create()
			.strength(3.0f, 6.0f)
			.requiresTool()
			.nonOpaque()
			.sounds(BlockSoundGroup.HONEY),
		TowerKind.POISON);

	// The corrosive acid fluid's block form spreads and flows like water. It has no
	// block item (use an acid bucket), no collision, and translucent rendering.
	public static final Block ACID = register("acid",
		AcidFluidBlock::new,
		AbstractBlock.Settings.create()
			.replaceable()
			.noCollision()
			.liquid()
			.nonOpaque()
			.strength(100.0f)
			.dropsNothing()
			.luminance(s -> 4)
			.pistonBehavior(PistonBehavior.DESTROY)
			.sounds(BlockSoundGroup.HONEY),
		false);

	/**
	 * Register a tower block whose item is a {@link TowerBlockItem} — placing it raises
	 * the full tower for its {@link TowerKind} instead of dropping a bare block.
	 */
	public static Block registerTower(String name, Function<AbstractBlock.Settings, Block> factory,
			AbstractBlock.Settings settings, TowerKind kind) {
		RegistryKey<Block> blockKey = RegistryKey.of(RegistryKeys.BLOCK,
			Identifier.of(TowerDefenseMod.MOD_ID, name));
		Block block = factory.apply(settings.registryKey(blockKey));
		RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM,
			Identifier.of(TowerDefenseMod.MOD_ID, name));
		TowerBlockItem blockItem = new TowerBlockItem(block,
			new Item.Settings().registryKey(itemKey).useBlockPrefixedTranslationKey().maxCount(99), kind);
		Registry.register(Registries.ITEM, itemKey, blockItem);
		return Registry.register(Registries.BLOCK, blockKey, block);
	}

	public static Block register(String name, Function<AbstractBlock.Settings, Block> factory,
			AbstractBlock.Settings settings, boolean withItem) {
		RegistryKey<Block> blockKey = RegistryKey.of(RegistryKeys.BLOCK,
			Identifier.of(TowerDefenseMod.MOD_ID, name));
		Block block = factory.apply(settings.registryKey(blockKey));
		if (withItem) {
			RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM,
				Identifier.of(TowerDefenseMod.MOD_ID, name));
			BlockItem blockItem = new BlockItem(block,
				new Item.Settings().registryKey(itemKey).useBlockPrefixedTranslationKey());
			Registry.register(Registries.ITEM, itemKey, blockItem);
		}
		return Registry.register(Registries.BLOCK, blockKey, block);
	}

	/** Forces class load so the static registrations above run. */
	public static void initialize() {
	}
}
