package net.bubblesky.towerdefense.registry;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * A dedicated creative item group so all tower-defense content is easy to grab
 * in one tab.
 */
public final class ModItemGroups {
	private ModItemGroups() {
	}

	public static final RegistryKey<ItemGroup> TOWER_DEFENSE_KEY =
		RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of(TowerDefenseMod.MOD_ID, "general"));

	public static final ItemGroup TOWER_DEFENSE_GROUP = FabricItemGroup.builder()
		.icon(() -> new ItemStack(ModBlocks.ARROW_TOWER))
		.displayName(Text.translatable("itemgroup.towerdefense.general"))
		.build();

	public static void initialize() {
		Registry.register(Registries.ITEM_GROUP, TOWER_DEFENSE_KEY, TOWER_DEFENSE_GROUP);

		ItemGroupEvents.modifyEntriesEvent(TOWER_DEFENSE_KEY).register(entries -> {
			entries.add(ModItems.SPEAR);
			entries.add(ModItems.MACE);
			entries.add(ModItems.WAR_HAMMER);
			entries.add(ModItems.COIN);
			entries.add(ModItems.ACID_BUCKET);
			entries.add(ModItems.LAYOUT_WAND);
			// Flag Bow retired — flag-planting folded into the unified TD bow.
			entries.add(ModItems.TD_BOW);
			entries.add(ModItems.ARROW_TOWER_ARROW);
			entries.add(ModItems.CANNON_TOWER_ARROW);
			entries.add(ModItems.FROST_TOWER_ARROW);
			entries.add(ModItems.LIGHTNING_TOWER_ARROW);
			entries.add(ModItems.FLAME_TOWER_ARROW);
			entries.add(ModItems.BALL_TOWER_ARROW);
			entries.add(ModItems.SHARPSHOOTER_TOWER_ARROW);
			entries.add(ModItems.SNARE_TOWER_ARROW);
			entries.add(ModItems.BALLISTA_TOWER_ARROW);
			entries.add(ModItems.MASON_TOWER_ARROW);
			entries.add(ModItems.BEACON_TOWER_ARROW);
			entries.add(ModItems.POISON_TOWER_ARROW);
			entries.add(ModBlocks.ARROW_TOWER);
			entries.add(ModBlocks.CANNON_TOWER);
			entries.add(ModBlocks.FROST_TOWER);
			entries.add(ModBlocks.LIGHTNING_TOWER);
			entries.add(ModBlocks.FLAME_TOWER);
			entries.add(ModBlocks.BALL_TOWER);
			entries.add(ModBlocks.SHARPSHOOTER_TOWER);
			entries.add(ModBlocks.SNARE_TOWER);
			entries.add(ModBlocks.BALLISTA_TOWER);
			entries.add(ModBlocks.MASON_TOWER);
			entries.add(ModBlocks.BEACON_TOWER);
			entries.add(ModBlocks.POISON_TOWER);
		});
	}
}
