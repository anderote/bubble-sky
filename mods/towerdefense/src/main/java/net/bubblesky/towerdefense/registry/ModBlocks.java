package net.bubblesky.towerdefense.registry;

import java.util.function.Function;
import net.bubblesky.towerdefense.TowerDefenseMod;
import net.bubblesky.towerdefense.block.ArrowTowerBlock;
import net.bubblesky.towerdefense.block.CannonTowerBlock;
import net.bubblesky.towerdefense.block.FrostTowerBlock;
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

	public static final Block ARROW_TOWER = register("arrow_tower",
		ArrowTowerBlock::new,
		AbstractBlock.Settings.create()
			.strength(3.0f, 6.0f)
			.requiresTool()
			.nonOpaque()
			.sounds(BlockSoundGroup.STONE),
		true);

	public static final Block CANNON_TOWER = register("cannon_tower",
		CannonTowerBlock::new,
		AbstractBlock.Settings.create()
			.strength(4.0f, 8.0f)
			.requiresTool()
			.nonOpaque()
			.sounds(BlockSoundGroup.STONE),
		true);

	public static final Block FROST_TOWER = register("frost_tower",
		FrostTowerBlock::new,
		AbstractBlock.Settings.create()
			.strength(3.0f, 6.0f)
			.requiresTool()
			.nonOpaque()
			.sounds(BlockSoundGroup.GLASS),
		true);

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
