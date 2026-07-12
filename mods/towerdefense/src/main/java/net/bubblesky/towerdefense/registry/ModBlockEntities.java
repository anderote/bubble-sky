package net.bubblesky.towerdefense.registry;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.bubblesky.towerdefense.blockentity.ArrowTowerBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * Registers block entity types. Depends on {@link ModBlocks} being initialized
 * first (its static initializer references {@link ModBlocks#ARROW_TOWER}).
 */
public final class ModBlockEntities {
	private ModBlockEntities() {
	}

	public static final BlockEntityType<ArrowTowerBlockEntity> ARROW_TOWER =
		Registry.register(Registries.BLOCK_ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, "arrow_tower"),
			FabricBlockEntityTypeBuilder.create(ArrowTowerBlockEntity::new, ModBlocks.ARROW_TOWER).build());

	/** Forces class load so the static registration above runs. */
	public static void initialize() {
	}
}
