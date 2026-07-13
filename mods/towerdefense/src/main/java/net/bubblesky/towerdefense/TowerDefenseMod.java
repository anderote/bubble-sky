package net.bubblesky.towerdefense;

import net.bubblesky.towerdefense.bridge.AgentBridge;
import net.bubblesky.towerdefense.colony.ColonyChat;
import net.bubblesky.towerdefense.colony.ColonyCommand;
import net.bubblesky.towerdefense.colony.ColonyRespawn;
import net.bubblesky.towerdefense.command.TdCommand;
import net.bubblesky.towerdefense.item.LayoutWandItem;
import net.bubblesky.towerdefense.layout.LayoutStore;
import net.bubblesky.towerdefense.progression.ProgressEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.bubblesky.towerdefense.game.TdHud;
import net.bubblesky.towerdefense.game.WaveManager;
import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.bubblesky.towerdefense.registry.ModBlocks;
import net.bubblesky.towerdefense.registry.ModEntities;
import net.bubblesky.towerdefense.registry.ModItemGroups;
import net.bubblesky.towerdefense.registry.ModItems;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tower Defense: a HUMAN-FACING Fabric game mod for Minecraft 1.21.6.
 *
 * <p>First vertical slice:
 * <ul>
 *   <li>3 medieval weapons (spear, mace, war hammer) with combat stats.</li>
 *   <li>An {@code arrow_tower} block with a BlockEntity that auto-shoots
 *       arrows at the nearest hostile mob within range.</li>
 *   <li>A {@code coin} currency item that hostile mobs drop on death — the
 *       seed of the coin economy / shop loop.</li>
 * </ul>
 *
 * <p>Coexistence note: unlike the server-side {@code bubble-sky} mod, this mod
 * registers CUSTOM blocks/items/entities. Mineflayer bots (Grok/Codex swarm)
 * and vanilla clients will not understand this content — that is expected and
 * fine for a human game mod. Players need this mod installed client-side to see
 * the custom block/items.
 */
public class TowerDefenseMod implements ModInitializer {
	public static final String MOD_ID = "towerdefense";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Coins dropped when a hostile mob is killed. */
	private static final int COIN_DROP_MIN = 1;
	private static final int COIN_DROP_MAX = 3;

	@Override
	public void onInitialize() {
		ModItems.initialize();
		ModBlocks.initialize();
		ModBlockEntities.initialize();
		ModEntities.initialize();
		ModEntities.registerAttributes();
		ModItemGroups.initialize();

		registerCoinDrops();
		registerJoinHint();
		registerStartingGear();

		// Game loop: the /td command family + the endless wave state machine.
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			TdCommand.register(dispatcher));
		WaveManager.register();
		TdHud.register();

		// Colony layer: the /colony command + the by-name chat control, both routing
		// through the shared ColonyOrders hub. Colonists are recruited with gold and do
		// autonomous rule-based work (mine/chop/hunt/forage/haul) around a home flag.
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			ColonyCommand.register(dispatcher));
		ColonyChat.register();
		// Respawn at your colony home flag (no bed needed) instead of world spawn.
		ColonyRespawn.register();

		// RPG progression: permanent XP/levels/skill points. Registers its own XP-on-kill
		// AFTER_DEATH listener (separate from WaveManager's boss bounty), the allocate/sync
		// payloads, and the join/respawn/world-change stat (re)application. See progression/.
		ProgressEvents.register();

		// Modded agent bridge: in-JVM HTTP API so AI agents can observe/act on the
		// modded world without the vanilla protocol. Localhost-bound + token-gated;
		// starts on SERVER_STARTED if enabled in config. See net.bubblesky.towerdefense.bridge.
		AgentBridge.init();

		// Layout Wand planning tool: server-side flag/region store + the wand's
		// left-click + particle-visualization hooks. The store is loaded from the
		// run dir once the server starts (world/config dir is available then).
		ServerLifecycleEvents.SERVER_STARTED.register(server -> LayoutStore.init(server.getRunDirectory()));
		LayoutWandItem.register();

		LOGGER.info("[towerdefense] initialized: {} weapons + 3 towers (arrow/cannon/frost) + shop/upgrades + coin economy + wave game loop + 6-enemy roster + boss waves + HUD",
			ModItems.WEAPON_COUNT);
	}

	/**
	 * Discoverability: greet each joining player with a single chat line pointing
	 * them at the Tower Defense menu (keybind or {@code /td}). One message only, so
	 * it stays out of the way. The default menu key is {@code J} (rebindable in
	 * Controls); clients without this mod still get the {@code /td} pointer.
	 */
	private void registerJoinHint() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			handler.player.sendMessage(
				Text.literal("Tower Defense ready — press ").formatted(Formatting.GRAY)
					.append(Text.literal("[J]").formatted(Formatting.YELLOW))
					.append(Text.literal(" (menu key) or type ").formatted(Formatting.GRAY))
					.append(Text.literal("/td").formatted(Formatting.YELLOW))
					.append(Text.literal(" to open the menu.").formatted(Formatting.GRAY)),
				false));
	}

	/** Persistent player tag marking that the starting kit has already been granted. */
	private static final String KIT_TAG = "td_starter_kit";
	/** Gold (coins) granted once with the survival starter kit. */
	private static final int STARTER_GOLD = 100;

	/**
	 * Starting gear: the first time a player joins with the mod installed, grant a
	 * survival TD starter kit — a bow + 64 arrows, a wooden sword, and a full set of
	 * leather armor (equipped) — so a survival, non-op player can fight and set up a
	 * match immediately.
	 *
	 * <p>Idempotent: guarded by the persistent {@link #KIT_TAG} scoreboard tag on the
	 * player (which survives in player NBT across rejoins/reloads). {@code addCommandTag}
	 * returns {@code true} only when the tag was newly added, so the kit is granted
	 * exactly once per player and never re-issued on subsequent joins.
	 */
	private void registerStartingGear() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity player = handler.player;
			if (!player.addCommandTag(KIT_TAG)) {
				return; // already kitted — do not re-grant
			}
			// Equip a medium chainmail set into the armor slots.
			player.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
			player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
			player.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.CHAINMAIL_LEGGINGS));
			player.equipStack(EquipmentSlot.FEET, new ItemStack(Items.CHAINMAIL_BOOTS));
			// Weapons + ammo into the inventory (drops nearby if somehow full). The bow
			// is the unified TD bow: fire = combat arrow, sneak-fire = plant a flag, and
			// a bought tower arrow fired from it shoot-to-places that tower.
			giveOrDrop(player, new ItemStack(Items.WOODEN_SWORD));
			giveOrDrop(player, new ItemStack(ModItems.TD_BOW));
			giveOrDrop(player, new ItemStack(Items.ARROW, 64));
			// Seed gold so a fresh player can afford their first towers straight away.
			giveOrDrop(player, new ItemStack(ModItems.COIN, STARTER_GOLD));
			player.sendMessage(Text.literal("Starter kit granted: TD bow + 64 arrows, wooden sword, chainmail armor, "
				+ STARTER_GOLD + " gold. Sneak+fire to plant flags; buy a tower then place/fire it to build.")
				.formatted(Formatting.GREEN), false);
		});
	}

	/** Insert a stack into the player's inventory, dropping it at their feet if full. */
	private static void giveOrDrop(ServerPlayerEntity player, ItemStack stack) {
		if (!player.getInventory().insertStack(stack)) {
			player.dropItem(stack, false);
		}
	}

	/**
	 * Combat economy hook: when a hostile mob is killed <em>by a player</em> on
	 * the server, drop a few coins at its location. This seeds the
	 * coin -> shop -> instant-build loop (the shop itself is a later phase).
	 *
	 * <p>Mirrors the CC0 "Mob Money" behavior (reward the player killer). The
	 * attacker is resolved from the damage source, so once arrow towers set a
	 * player owner on their arrows, tower kills will pay out too — a natural
	 * next step (see report).
	 */
	private void registerCoinDrops() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (!(entity.getWorld() instanceof ServerWorld world)) {
				return;
			}
			if (!(entity instanceof HostileEntity)) {
				return;
			}
			// Only reward player kills. getAttacker() resolves to the player for
			// direct melee and for any projectile the player owns.
			if (!(damageSource.getAttacker() instanceof PlayerEntity)) {
				return;
			}
			int count = COIN_DROP_MIN + world.random.nextInt(COIN_DROP_MAX - COIN_DROP_MIN + 1);
			ItemEntity coinEntity = new ItemEntity(
				world,
				entity.getX(), entity.getBodyY(0.5), entity.getZ(),
				new ItemStack(ModItems.COIN, count));
			coinEntity.setToDefaultPickupDelay();
			world.spawnEntity(coinEntity);
		});
	}
}
