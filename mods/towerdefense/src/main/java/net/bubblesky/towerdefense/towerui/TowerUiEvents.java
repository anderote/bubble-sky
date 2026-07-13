package net.bubblesky.towerdefense.towerui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity;
import net.bubblesky.towerdefense.command.TdCommand;
import net.bubblesky.towerdefense.command.TowerService;
import net.bubblesky.towerdefense.state.TdArenaState;
import net.bubblesky.towerdefense.towerui.net.TowerActionPayload;
import net.bubblesky.towerdefense.towerui.net.TowerRosterPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/** Registers the My Towers panel networking and handles its actions server-side. */
public final class TowerUiEvents {
	private TowerUiEvents() {
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(TowerRosterPayload.ID, TowerRosterPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(TowerActionPayload.ID, TowerActionPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(TowerActionPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			ServerWorld world = (ServerWorld) player.getWorld();
			BlockPos pos = BlockPos.fromLong(payload.posId());
			TowerService.Result r = null;
			if (payload.action() == TowerActionPayload.ACTION_UPGRADE) {
				r = TowerService.upgrade(world, player, pos);
			} else if (payload.action() == TowerActionPayload.ACTION_SELL) {
				r = TowerService.sell(world, player, pos);
			}
			if (r != null && !r.ok()) {
				player.sendMessage(Text.literal(r.message()).formatted(Formatting.RED), true);
			}
			sendRoster(player);
		});
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
				maxed));
		}
		ServerPlayNetworking.send(player, new TowerRosterPayload(TdCommand.countCoinsPublic(player), rows));
	}
}
