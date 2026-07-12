package net.bubblesky.towerdefense.client;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.bubblesky.towerdefense.client.render.TdBipedEntityRenderer;
import net.bubblesky.towerdefense.entity.TdEnemyEntity;
import net.bubblesky.towerdefense.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.util.Identifier;

/**
 * Client entrypoint: binds each roster {@link EntityType} to a
 * {@link TdBipedEntityRenderer} carrying that enemy's skin. Players need this mod
 * installed client-side to see the custom textures; a vanilla client (or a bot)
 * sees nothing renderable for these entities, which is expected for a human game
 * mod.
 */
public class TowerDefenseModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		bind(ModEntities.GOBLIN_SKIRMISHER, "goblin_skirmisher");
		bind(ModEntities.FOOTMAN, "footman");
		bind(ModEntities.ARCHER, "archer");
		bind(ModEntities.MAN_AT_ARMS, "man_at_arms");
		bind(ModEntities.UNDEAD_SOLDIER, "undead_soldier");
		bind(ModEntities.HEAVY_KNIGHT, "heavy_knight");
	}

	private static void bind(EntityType<? extends TdEnemyEntity> type, String skin) {
		Identifier texture = Identifier.of(TowerDefenseMod.MOD_ID, "textures/entity/" + skin + ".png");
		EntityRendererRegistry.register(type, ctx -> new TdBipedEntityRenderer(ctx, texture));
	}
}
