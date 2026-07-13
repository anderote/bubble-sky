package net.bubblesky.towerdefense.registry;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.bubblesky.towerdefense.blockentity.ArrowTowerBlockEntity;
import net.bubblesky.towerdefense.blockentity.BallTowerBlockEntity;
import net.bubblesky.towerdefense.blockentity.CannonTowerBlockEntity;
import net.bubblesky.towerdefense.blockentity.FlameTowerBlockEntity;
import net.bubblesky.towerdefense.blockentity.FrostTowerBlockEntity;
import net.bubblesky.towerdefense.blockentity.LightningTowerBlockEntity;
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

	public static final BlockEntityType<CannonTowerBlockEntity> CANNON_TOWER =
		Registry.register(Registries.BLOCK_ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, "cannon_tower"),
			FabricBlockEntityTypeBuilder.create(CannonTowerBlockEntity::new, ModBlocks.CANNON_TOWER).build());

	public static final BlockEntityType<FrostTowerBlockEntity> FROST_TOWER =
		Registry.register(Registries.BLOCK_ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, "frost_tower"),
			FabricBlockEntityTypeBuilder.create(FrostTowerBlockEntity::new, ModBlocks.FROST_TOWER).build());

	public static final BlockEntityType<LightningTowerBlockEntity> LIGHTNING_TOWER =
		Registry.register(Registries.BLOCK_ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, "lightning_tower"),
			FabricBlockEntityTypeBuilder.create(LightningTowerBlockEntity::new, ModBlocks.LIGHTNING_TOWER).build());

	public static final BlockEntityType<FlameTowerBlockEntity> FLAME_TOWER =
		Registry.register(Registries.BLOCK_ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, "flame_tower"),
			FabricBlockEntityTypeBuilder.create(FlameTowerBlockEntity::new, ModBlocks.FLAME_TOWER).build());

	public static final BlockEntityType<BallTowerBlockEntity> BALL_TOWER =
		Registry.register(Registries.BLOCK_ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, "ball_tower"),
			FabricBlockEntityTypeBuilder.create(BallTowerBlockEntity::new, ModBlocks.BALL_TOWER).build());

	/** Forces class load so the static registration above runs. */
	public static void initialize() {
	}
}
