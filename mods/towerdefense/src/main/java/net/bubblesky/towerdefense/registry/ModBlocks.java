package net.bubblesky.towerdefense.registry;

import java.util.function.Function;
import net.bubblesky.towerdefense.TowerDefenseMod;
import net.bubblesky.towerdefense.block.AcidBlock;
import net.bubblesky.towerdefense.block.ArrowTowerBlock;
import net.bubblesky.towerdefense.block.BallTowerBlock;
import net.bubblesky.towerdefense.block.CannonTowerBlock;
import net.bubblesky.towerdefense.block.FlameTowerBlock;
import net.bubblesky.towerdefense.block.FrostTowerBlock;
import net.bubblesky.towerdefense.block.LightningTowerBlock;
import net.bubblesky.towerdefense.item.TowerBlockItem;
import net.bubblesky.towerdefense.tower.TowerKind;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
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

	// The single-block sticky mini turret (no pole/orb). Placeable on any face.
	public static final Block BALL_TOWER = registerTower("ball_tower",
		BallTowerBlock::new,
		AbstractBlock.Settings.create()
			.strength(2.0f, 4.0f)
			.requiresTool()
			.nonOpaque()
			.sounds(BlockSoundGroup.STONE),
		TowerKind.BALL);

	// The corrosive acid pseudo-liquid. No block item (obtained via acid_bucket,
	// like vanilla fluids); still /setblock-able. No collision so you sink through
	// it, translucent + non-opaque so you can see through the green.
	public static final Block ACID = register("acid",
		AcidBlock::new,
		AbstractBlock.Settings.create()
			.noCollision()
			.nonOpaque()
			.strength(100.0f)
			.dropsNothing()
			.luminance(s -> 4)
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
			new Item.Settings().registryKey(itemKey).useBlockPrefixedTranslationKey(), kind);
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
