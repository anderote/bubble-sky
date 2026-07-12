package net.bubblesky.towerdefense;

import net.bubblesky.towerdefense.bridge.AgentBridge;
import net.bubblesky.towerdefense.command.TdCommand;
import net.bubblesky.towerdefense.item.LayoutWandItem;
import net.bubblesky.towerdefense.layout.LayoutStore;
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
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
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

		// Game loop: the /td command family + the endless wave state machine.
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			TdCommand.register(dispatcher));
		WaveManager.register();
		TdHud.register();

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
