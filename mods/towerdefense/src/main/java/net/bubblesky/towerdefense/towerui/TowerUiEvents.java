package net.bubblesky.towerdefense.towerui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity;
import net.bubblesky.towerdefense.command.TdCommand;
import net.bubblesky.towerdefense.command.TowerService;
import net.bubblesky.towerdefense.state.TdArenaState;
import net.bubblesky.towerdefense.towerui.net.OpenTowerMenuPayload;
import net.bubblesky.towerdefense.towerui.net.TowerActionPayload;
import net.bubblesky.towerdefense.towerui.net.TowerRosterPayload;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/** Registers the My Towers panel networking and handles its actions server-side. */
public final class TowerUiEvents {
	private TowerUiEvents() {
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(TowerRosterPayload.ID, TowerRosterPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(OpenTowerMenuPayload.ID, OpenTowerMenuPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(TowerActionPayload.ID, TowerActionPayload.CODEC);
		// Enemy-outline toggle: an empty C2S signal from the "toggle enemy outline" keybind.
		PayloadTypeRegistry.playC2S().register(
			net.bubblesky.towerdefense.towerui.net.ToggleEnemyGlowPayload.ID,
			net.bubblesky.towerdefense.towerui.net.ToggleEnemyGlowPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(TowerActionPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			ServerWorld world = (ServerWorld) player.getWorld();
			BlockPos pos = BlockPos.fromLong(payload.posId());
			TowerService.Result r = null;
			if (payload.action() == TowerActionPayload.ACTION_UPGRADE) {
				r = TowerService.upgrade(world, player, pos);
			} else if (payload.action() == TowerActionPayload.ACTION_SELL) {
				r = TowerService.sell(world, player, pos);
			} else if (payload.action() == TowerActionPayload.ACTION_RECYCLE) {
				r = TowerService.recycle(world, player, pos);
			} else if (payload.action() == TowerActionPayload.ACTION_DESTROY) {
				r = TowerService.destroy(world, player, pos);
			}
			if (r != null) {
				if (!r.ok()) {
					player.sendMessage(Text.literal(r.message()).formatted(Formatting.RED), true);
				} else if (payload.action() == TowerActionPayload.ACTION_RECYCLE
						|| payload.action() == TowerActionPayload.ACTION_DESTROY) {
					// Recycle/Destroy are new terminal actions — give explicit success feedback
					// (upgrade/sell stay silent-on-success, matching their existing behaviour).
					player.sendMessage(Text.literal(r.message()).formatted(Formatting.GREEN), true);
				}
			}
			// If the tower is still standing (an Upgrade), push a fresh menu snapshot so an open
			// context menu refreshes in place; sold/recycled/destroyed towers are gone, so the
			// client screen closes itself instead.
			if (world.getBlockEntity(pos) instanceof AbstractTowerBlockEntity) {
				sendOpenMenu(player, world, pos);
			}
			sendRoster(player);
		});

		// Enemy-outline toggle: flip the shared, server-authoritative GLOBAL glow flag and re-sync
		// every live wave enemy. It is a mod-wide toggle (affects all players), so we route straight
		// to WaveManager which also broadcasts the ON/OFF feedback to everyone.
		ServerPlayNetworking.registerGlobalReceiver(
			net.bubblesky.towerdefense.towerui.net.ToggleEnemyGlowPayload.ID,
			(payload, context) -> net.bubblesky.towerdefense.game.WaveManager.toggleEnemyGlow(
				context.player().getServer()));

		// OPEN TRIGGER: punching / start-mining a tower block opens its context menu instead of
		// breaking it. Fires on both logical sides for the main hand; we CANCEL the break for
		// towers (return SUCCESS) and, server-side, push the menu snapshot to the puncher. Non-
		// tower blocks fall through (PASS) so normal mining is untouched.
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (hand != Hand.MAIN_HAND) {
				return ActionResult.PASS;
			}
			if (!(world.getBlockEntity(pos) instanceof AbstractTowerBlockEntity)) {
				return ActionResult.PASS; // normal block — let vanilla mining proceed
			}
			if (player instanceof ServerPlayerEntity serverPlayer && world instanceof ServerWorld serverWorld) {
				sendOpenMenu(serverPlayer, serverWorld, pos);
			}
			return ActionResult.SUCCESS; // swallow the break on both sides; the screen takes over
		});
	}

	/** Snapshot the tower at {@code pos} and push an {@link OpenTowerMenuPayload} to {@code player}. */
	public static void sendOpenMenu(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
		if (!(world.getBlockEntity(pos) instanceof AbstractTowerBlockEntity tower)) {
			return;
		}
		boolean maxed = tower.getTier() >= AbstractTowerBlockEntity.MAX_TIER;
		boolean isOwner = player.getUuid().equals(tower.getPlacerUuid());
		ServerPlayNetworking.send(player, new OpenTowerMenuPayload(
			pos.asLong(),
			tower.kind().ordinal(),
			tower.getTier(),
			AbstractTowerBlockEntity.MAX_TIER,
			tower.getKills(),
			tower.getVeterancy(),
			AbstractTowerBlockEntity.MAX_VETERANCY,
			tower.killsToNextVeterancy(),
			(int) Math.round(tower.displayDamageMultiplier() * 100),
			(int) Math.round(tower.displayRange()),
			tower.displayCooldownTicks(),
			tower.getInvested(),
			TowerService.refund(world, tower),
			maxed ? -1 : TowerService.upgradeCost(world, tower),
			isOwner,
			ownerName(world, tower.getPlacerUuid())));
	}

	/** Best-effort display name for a placer UUID: online player, else user cache, else "Unknown". */
	private static String ownerName(ServerWorld world, @Nullable UUID uuid) {
		if (uuid == null) {
			return "Unknown";
		}
		ServerPlayerEntity online = world.getServer().getPlayerManager().getPlayer(uuid);
		if (online != null) {
			return online.getGameProfile().getName();
		}
		try {
			var cache = world.getServer().getUserCache();
			if (cache != null) {
				var profile = cache.getByUuid(uuid);
				if (profile.isPresent()) {
					return profile.get().getName();
				}
			}
		} catch (Exception ignored) {
			// user cache lookups are best-effort; fall through to the placeholder
		}
		return "Unknown";
	}

	/** Build and push the player's tower roster (their placed towers, self-healing stale entries). */
	public static void sendRoster(ServerPlayerEntity player) {
		ServerWorld world = (ServerWorld) player.getWorld();
		UUID me = player.getUuid();
		TdArenaState st = TdArenaState.get(world.getServer());
		List<TowerRosterPayload.Row> rows = new ArrayList<>();
		for (BlockPos pos : List.copyOf(st.towers)) {
			if (!(world.getBlockEntity(pos) instanceof AbstractTowerBlockEntity tower)) {
				continue; // stale entry (broken/removed) — skip; self-heal on sell removes it
			}
			if (!me.equals(tower.getPlacerUuid())) {
				continue;
			}
			boolean maxed = tower.getTier() >= AbstractTowerBlockEntity.MAX_TIER;
			rows.add(new TowerRosterPayload.Row(
				pos.asLong(),
				tower.kind().ordinal(),
				tower.getTier(),
				(int) Math.round(tower.displayRange()),
				tower.displayCooldownTicks(),
				(int) Math.round(tower.displayDamageMultiplier() * 100),
				maxed ? 0 : TowerService.upgradeCost(world, tower),
				TowerService.refund(world, tower),
				maxed,
				tower.getVeterancy(),
				tower.getKills()));
		}
		ServerPlayNetworking.send(player, new TowerRosterPayload(TdCommand.countCoinsPublic(player), rows));
	}
}
