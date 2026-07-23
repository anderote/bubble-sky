package net.bubblesky.towerdefense.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.bubblesky.towerdefense.command.TdCommand;
import net.bubblesky.towerdefense.entity.TdAllyEntity;
import net.bubblesky.towerdefense.state.TdArenaState;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * Persistent standing-army economy: paid hires draw wages after waves, build veterancy,
 * and gain/lose morale. Temporary spell summons have wage 0 and are intentionally ignored.
 */
public final class ArmyManager {
	private ArmyManager() {
	}

	public static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof TdAllyEntity fallen) || !fallen.isStandingArmy()
					|| !(entity.getWorld() instanceof ServerWorld world) || fallen.getOwner() == null) {
				return;
			}
			for (TdAllyEntity survivor : owned(world, fallen.getOwner())) {
				if (survivor != fallen) survivor.changeMorale(-12);
			}
			ServerPlayerEntity owner = world.getServer().getPlayerManager().getPlayer(fallen.getOwner());
			if (owner != null) {
				owner.sendMessage(Text.literal(fallen.getArmyRole()
					+ " fell in battle. The squad loses 12 morale.").formatted(Formatting.RED));
			}
		});
	}

	/** Pay wages from each online owner's bank and update persistent unit morale/veterancy. */
	public static void settleWave(ServerWorld world, int wave) {
		if (wave < 5) return; // early recruits get a short grace period.
		Map<UUID, List<TdAllyEntity>> squads = new LinkedHashMap<>();
		for (TdAllyEntity ally : all(world)) {
			if (ally.isStandingArmy() && ally.getOwner() != null) {
				squads.computeIfAbsent(ally.getOwner(), ignored -> new ArrayList<>()).add(ally);
			}
		}
		for (Map.Entry<UUID, List<TdAllyEntity>> entry : squads.entrySet()) {
			ServerPlayerEntity owner = world.getServer().getPlayerManager().getPlayer(entry.getKey());
			if (owner == null) continue; // never charge an offline player.
			int wages = entry.getValue().stream().mapToInt(TdAllyEntity::getWage).sum();
			boolean paid = TdCommand.countCoinsPublic(owner) >= wages;
			if (paid) TdCommand.removeCoinsPublic(owner, wages);
			for (TdAllyEntity ally : entry.getValue()) {
				if (paid) {
					ally.completeServiceWave();
					ally.changeMorale(6);
				} else {
					ally.changeMorale(-25);
				}
			}
			owner.sendMessage(Text.literal(paid
				? "Army wages paid: " + wages + " coins. Morale +6; veterancy earned."
				: "Couldn't cover " + wages + " coins of army wages. Morale -25.")
				.formatted(paid ? Formatting.AQUA : Formatting.RED));
		}
	}

	public static Text status(ServerWorld world, UUID owner) {
		List<TdAllyEntity> squad = owned(world, owner);
		if (squad.isEmpty()) return Text.literal("Standing army: no paid units. Hire with /td hire <type>.");
		int wages = squad.stream().mapToInt(TdAllyEntity::getWage).sum();
		int morale = Math.round((float) squad.stream().mapToInt(TdAllyEntity::getMorale).sum() / squad.size());
		return Text.literal("Standing army: " + squad.size() + " units • " + wages
			+ " wages/wave from wave 5 • average morale " + morale
			+ " • /td army rally").formatted(Formatting.AQUA);
	}

	/** Spend coins to restore squad morale. Returns a player-facing result. */
	public static Text rally(ServerWorld world, ServerPlayerEntity owner) {
		List<TdAllyEntity> squad = owned(world, owner.getUuid());
		if (squad.isEmpty()) return Text.literal("You have no standing army to rally.").formatted(Formatting.RED);
		int cost = Math.max(10, squad.size() * 3);
		int bank = TdCommand.countCoinsPublic(owner);
		if (bank < cost) return Text.literal("Rally costs " + cost + " coins; bank has " + bank + ".")
			.formatted(Formatting.RED);
		TdCommand.removeCoinsPublic(owner, cost);
		for (TdAllyEntity ally : squad) ally.changeMorale(25);
		return Text.literal("The banner rises! Spent " + cost + " coins; squad morale +25.")
			.formatted(Formatting.GREEN);
	}

	public static List<TdAllyEntity> owned(ServerWorld world, UUID owner) {
		return all(world).stream().filter(a -> owner.equals(a.getOwner()) && a.isStandingArmy()).toList();
	}

	private static List<TdAllyEntity> all(ServerWorld world) {
		TdArenaState state = TdArenaState.get(world.getServer());
		BlockPos center = state.base != null ? state.base : world.getSpawnPos();
		return world.getEntitiesByClass(TdAllyEntity.class, new Box(center).expand(256.0),
			a -> a.isAlive());
	}
}
