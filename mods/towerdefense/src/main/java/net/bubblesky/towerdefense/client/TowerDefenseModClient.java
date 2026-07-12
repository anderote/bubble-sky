package net.bubblesky.towerdefense.client;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.bubblesky.towerdefense.client.render.TdBipedEntityRenderer;
import net.bubblesky.towerdefense.client.screen.TowerDefenseScreen;
import net.bubblesky.towerdefense.entity.TdEnemyEntity;
import net.bubblesky.towerdefense.registry.ModBlocks;
import net.bubblesky.towerdefense.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.BlockRenderLayer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.EntityType;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client entrypoint. Two jobs:
 *
 * <ol>
 *   <li><b>Rendering</b>: binds each roster {@link EntityType} to a
 *       {@link TdBipedEntityRenderer} carrying that enemy's skin, and marks the
 *       acid block translucent. Players need this mod installed client-side to see
 *       the custom textures.</li>
 *   <li><b>Discoverable control menu</b>: registers a keybind (default {@code G})
 *       that opens {@link TowerDefenseScreen}, plus the always-on
 *       {@link TdClientHud} hint so the Tower Defense menu is visible by default.</li>
 * </ol>
 */
public class TowerDefenseModClient implements ClientModInitializer {

	/** Translation-key category for our keybind (see lang file). */
	private static final String KEY_CATEGORY = "key.categories.towerdefense";

	private static KeyBinding openMenuKey;

	@Override
	public void onInitializeClient() {
		bind(ModEntities.GOBLIN_SKIRMISHER, "goblin_skirmisher");
		bind(ModEntities.FOOTMAN, "footman");
		bind(ModEntities.ARCHER, "archer");
		bind(ModEntities.MAN_AT_ARMS, "man_at_arms");
		bind(ModEntities.UNDEAD_SOLDIER, "undead_soldier");
		bind(ModEntities.HEAVY_KNIGHT, "heavy_knight");

		// Acid renders as a translucent green liquid.
		BlockRenderLayerMap.putBlock(ModBlocks.ACID, BlockRenderLayer.TRANSLUCENT);

		// ---- Tower Defense control menu -----------------------------------
		openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.towerdefense.open_menu",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_G,
			KEY_CATEGORY));

		// Open the screen on keypress (works whether or not a match is running,
		// so players can START one from the menu).
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openMenuKey.wasPressed()) {
				if (client.currentScreen == null && client.player != null) {
					client.setScreen(new TowerDefenseScreen());
				}
			}
		});

		// Always-on discoverability HUD hint (shows the keybind + live status).
		TdClientHud.register(openMenuKey);
	}

	private static void bind(EntityType<? extends TdEnemyEntity> type, String skin) {
		Identifier texture = Identifier.of(TowerDefenseMod.MOD_ID, "textures/entity/" + skin + ".png");
		EntityRendererRegistry.register(type, ctx -> new TdBipedEntityRenderer(ctx, texture));
	}
}
