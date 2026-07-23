package net.bubblesky.towerdefense.client;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.bubblesky.towerdefense.client.render.ColonistBipedRenderer;
import net.bubblesky.towerdefense.client.render.FlagArrowEntityRenderer;
import net.bubblesky.towerdefense.client.render.JuggernautBipedRenderer;
import net.bubblesky.towerdefense.client.render.ShellEntityRenderer;
import net.bubblesky.towerdefense.client.render.TdWolfRenderer;
import net.bubblesky.towerdefense.client.render.TowerArrowEntityRenderer;
import net.bubblesky.towerdefense.client.render.TowerBoltEntityRenderer;
import net.bubblesky.towerdefense.client.render.TdAllyBipedRenderer;
import net.bubblesky.towerdefense.client.render.TdBipedEntityRenderer;
import net.bubblesky.towerdefense.client.render.TdSkeletonBipedRenderer;
import net.bubblesky.towerdefense.client.render.TdSkeletonWarriorRenderer;
import net.bubblesky.towerdefense.client.screen.CharacterScreen;
import net.bubblesky.towerdefense.client.screen.ConstructionScreen;
import net.bubblesky.towerdefense.client.screen.TowerDefenseScreen;
import net.bubblesky.towerdefense.entity.TdAllyEntity;
import net.bubblesky.towerdefense.entity.TdEnemyEntity;
import net.bubblesky.towerdefense.progression.net.ProgressSyncPayload;
import net.bubblesky.towerdefense.registry.ModBlocks;
import net.bubblesky.towerdefense.registry.ModEntities;
import net.bubblesky.towerdefense.registry.ModFluids;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.fabric.api.client.render.fluid.v1.SimpleFluidRenderHandler;
import net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
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
 *   <li><b>Discoverable control menu</b>: registers a keybind (default {@code J})
 *       that opens {@link TowerDefenseScreen}, plus the always-on
 *       {@link TdClientHud} hint so the Tower Defense menu is visible by default.</li>
 * </ol>
 */
public class TowerDefenseModClient implements ClientModInitializer {

	/** Translation-key category for our keybind (see lang file). */
	private static final String KEY_CATEGORY = "key.categories.towerdefense";

	private static KeyBinding openMenuKey;
	private static KeyBinding hireKey;
	private static KeyBinding characterKey;
	private static KeyBinding inventoryKey;
	private static KeyBinding towersKey;
	private static KeyBinding toggleEnemyOutlineKey;
	private static KeyBinding constructionMenuKey;
	private static KeyBinding confirmConstructionKey;
	private static KeyBinding cancelConstructionKey;

	@Override
	public void onInitializeClient() {
		bind(ModEntities.GOBLIN_SKIRMISHER, "goblin_skirmisher");
		bind(ModEntities.FOOTMAN, "footman");
		bind(ModEntities.ARCHER, "archer");
		bind(ModEntities.MAN_AT_ARMS, "man_at_arms");
		bind(ModEntities.UNDEAD_SOLDIER, "undead_soldier");
		bind(ModEntities.HEAVY_KNIGHT, "heavy_knight");
		bind(ModEntities.BARBARIAN, "barbarian");
		bind(ModEntities.BARBARIAN_SAPPER, "barbarian_sapper");
		// New variety roster. Gargoyle + Hexer are reskinned bipeds like the rest; the
		// Juggernaut is a biped drawn ~1.4x larger; the Direwolf uses the vanilla wolf model.
		bind(ModEntities.GARGOYLE, "gargoyle");
		bind(ModEntities.HEXER, "hexer");
		EntityRendererRegistry.register(ModEntities.JUGGERNAUT, ctx -> new JuggernautBipedRenderer(ctx,
			Identifier.of(TowerDefenseMod.MOD_ID, "textures/entity/juggernaut.png")));
		EntityRendererRegistry.register(ModEntities.DIREWOLF, ctx -> new TdWolfRenderer(ctx,
			Identifier.of(TowerDefenseMod.MOD_ID, "textures/entity/direwolf.png")));

		// Friendly ally roster (blue-tinted biped skins).
		bindAlly(ModEntities.ALLY_FOOTMAN, "ally_footman");
		bindAlly(ModEntities.ALLY_ARCHER, "ally_archer");
		bindAlly(ModEntities.ALLY_KNIGHT, "ally_knight");
		// The Warlord's summoned skeleton archer: a friendly ally under the hood, but
		// drawn with the vanilla skeleton model + skin (see TdSkeletonBipedRenderer).
		EntityRendererRegistry.register(ModEntities.ALLY_SKELETON, TdSkeletonBipedRenderer::new);
		// The Necromancer's raised skeleton WARRIOR: same vanilla skeleton model + skin, but
		// holding a sword (a friendly ally under the hood; see TdSkeletonWarriorRenderer).
		EntityRendererRegistry.register(ModEntities.ALLY_SKELETON_WARRIOR, TdSkeletonWarriorRenderer::new);

		// Colony worker — the colonist skin on the same biped model.
		EntityRendererRegistry.register(ModEntities.COLONIST, ctx -> new ColonistBipedRenderer(ctx,
			Identifier.of(TowerDefenseMod.MOD_ID, "textures/entity/colonist.png")));

		// Flag / tower arrows: rendered as vanilla-looking arrows in flight.
		EntityRendererRegistry.register(ModEntities.FLAG_ARROW, FlagArrowEntityRenderer::new);
		EntityRendererRegistry.register(ModEntities.TOWER_ARROW, TowerArrowEntityRenderer::new);
		// The arrow/ball towers' combat bolt: also drawn as a vanilla arrow (it just vanishes on hit).
		EntityRendererRegistry.register(ModEntities.TOWER_BOLT, TowerBoltEntityRenderer::new);
		// The cannon's artillery shell: a spinning dark fire-charge round that bursts on impact.
		EntityRendererRegistry.register(ModEntities.SHELL, ShellEntityRenderer::new);

		// Acid renders as a translucent green liquid. It's now a real fluid, so we (a) mark
		// its block translucent and (b) register a FluidRenderHandler for both the still and
		// flowing acid fluids. Simplest approach: reuse the vanilla water still/flow sprites
		// with a green tint (SimpleFluidRenderHandler.coloredWater). This registration is
		// client-only (lives in the client entrypoint) so the dedicated server never touches it.
		BlockRenderLayerMap.putBlock(ModBlocks.ACID, BlockRenderLayer.TRANSLUCENT);
		SimpleFluidRenderHandler acidRenderHandler = SimpleFluidRenderHandler.coloredWater(0x4CC94C);
		FluidRenderHandlerRegistry.INSTANCE.register(ModFluids.STILL_ACID, ModFluids.FLOWING_ACID, acidRenderHandler);
		FluidRenderHandlerRegistry.INSTANCE.setBlockTransparency(ModBlocks.ACID, true);

		// ---- Tower Defense control menu -----------------------------------
		openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.towerdefense.open_menu",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_J,
			KEY_CATEGORY));
		hireKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.towerdefense.open_hire",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_H,
			KEY_CATEGORY));
		// RPG: P opens the Character screen, I opens the vanilla inventory. Distinct from
		// the existing J (menu) / H (hire) keys so nothing collides.
		characterKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.towerdefense.open_character",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_P,
			KEY_CATEGORY));
		inventoryKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.towerdefense.open_inventory",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_I,
			KEY_CATEGORY));
		towersKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.towerdefense.open_towers",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_K,
			KEY_CATEGORY));
		// N toggles the red enemy OUTLINE (the glow on wave enemies) for everyone. It sends an
		// empty C2S signal; the server owns the shared flag and re-syncs all live enemies. This
		// is NOT gated on being in-world with no screen (unlike the menu keys) so it can be hit
		// any time; rebindable in Controls.
		toggleEnemyOutlineKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.towerdefense.toggle_enemy_outline",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_N,
			KEY_CATEGORY));
		constructionMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.towerdefense.open_construction",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_B,
			KEY_CATEGORY));
		confirmConstructionKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.towerdefense.confirm_construction",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_ENTER,
			KEY_CATEGORY));
		cancelConstructionKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.towerdefense.cancel_construction",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_BACKSPACE,
			KEY_CATEGORY));
		ClientConstructionPreview.register(constructionMenuKey, confirmConstructionKey, cancelConstructionKey);

		// Cache each progression snapshot the server pushes (drives the HUD + Character screen).
		ClientPlayNetworking.registerGlobalReceiver(ProgressSyncPayload.ID,
			(payload, context) -> ClientProgress.update(payload));
		// Cache each tower-roster snapshot the server pushes (drives the My Towers screen).
		ClientPlayNetworking.registerGlobalReceiver(
			net.bubblesky.towerdefense.towerui.net.TowerRosterPayload.ID,
			(payload, context) -> net.bubblesky.towerdefense.client.ClientTowers.update(payload));

		// Per-tower context menu: sent when the player punches a tower block (and again after an
		// in-menu Upgrade). If that tower's menu is already open, refresh it in place; otherwise
		// open a fresh one. Marshalled onto the client thread since it touches the current screen.
		ClientPlayNetworking.registerGlobalReceiver(
			net.bubblesky.towerdefense.towerui.net.OpenTowerMenuPayload.ID,
			(payload, context) -> context.client().execute(() -> {
				var mc = context.client();
				if (mc.currentScreen instanceof net.bubblesky.towerdefense.client.screen.TowerMenuScreen menu
						&& menu.posId() == payload.posId()) {
					menu.updateSnapshot(payload);
				} else {
					mc.setScreen(new net.bubblesky.towerdefense.client.screen.TowerMenuScreen(payload));
				}
			}));

		// Open the right screen from a keybind. Always drain the press queues; open only
		// when in-world with no other screen up. J = full menu, H = hire, P = character,
		// I = vanilla inventory.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean openMenu = false;
			while (openMenuKey.wasPressed()) openMenu = true;
			while (hireKey.wasPressed()) openMenu = true;
			boolean openCharacter = false;
			while (characterKey.wasPressed()) openCharacter = true;
			boolean openInventory = false;
			while (inventoryKey.wasPressed()) openInventory = true;
			boolean openTowers = false;
			while (towersKey.wasPressed()) openTowers = true;
			// Enemy-outline toggle: drain presses and fire the empty C2S signal to the server,
			// which flips the shared GLOBAL glow flag. Handled BEFORE the screen/in-world guard
			// so it works even with a menu open (it needs a connection, not an empty screen).
			boolean toggleOutline = false;
			while (toggleEnemyOutlineKey.wasPressed()) toggleOutline = true;
			if (toggleOutline && client.player != null) {
				ClientPlayNetworking.send(new net.bubblesky.towerdefense.towerui.net.ToggleEnemyGlowPayload());
			}
			boolean openConstruction = false;
			while (constructionMenuKey.wasPressed()) openConstruction = true;
			boolean confirmConstruction = false;
			while (confirmConstructionKey.wasPressed()) confirmConstruction = true;
			boolean cancelConstruction = false;
			while (cancelConstructionKey.wasPressed()) cancelConstruction = true;
			if (client.currentScreen == null && client.player != null) {
				if (confirmConstruction) {
					ClientConstructionPreview.confirm();
				}
				if (cancelConstruction) {
					ClientConstructionPreview.cancel();
				}
				if (openConstruction) {
					client.setScreen(new ConstructionScreen());
					return;
				}
			}
			if (client.currentScreen != null || client.player == null) {
				return;
			}
			if (openMenu) {
				client.setScreen(new TowerDefenseScreen());
			} else if (openCharacter) {
				client.setScreen(new CharacterScreen());
			} else if (openInventory) {
				client.setScreen(new InventoryScreen(client.player));
			} else if (openTowers) {
				ClientPlayNetworking.send(new net.bubblesky.towerdefense.towerui.net.TowerActionPayload(
					0L, net.bubblesky.towerdefense.towerui.net.TowerActionPayload.ACTION_REFRESH));
				client.setScreen(new net.bubblesky.towerdefense.client.screen.MyTowersScreen());
			}
		});

		// Always-on discoverability HUD hint (shows the keybind + live status).
		TdClientHud.register(openMenuKey);
	}

	private static void bind(EntityType<? extends TdEnemyEntity> type, String skin) {
		Identifier texture = Identifier.of(TowerDefenseMod.MOD_ID, "textures/entity/" + skin + ".png");
		EntityRendererRegistry.register(type, ctx -> new TdBipedEntityRenderer(ctx, texture));
	}

	private static void bindAlly(EntityType<? extends TdAllyEntity> type, String skin) {
		Identifier texture = Identifier.of(TowerDefenseMod.MOD_ID, "textures/entity/" + skin + ".png");
		EntityRendererRegistry.register(type, ctx -> new TdAllyBipedRenderer(ctx, texture));
	}
}
