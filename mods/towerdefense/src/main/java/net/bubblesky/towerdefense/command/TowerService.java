package net.bubblesky.towerdefense.command;

import net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity;
import net.bubblesky.towerdefense.registry.ModItems;
import net.bubblesky.towerdefense.state.TdArenaState;
import net.bubblesky.towerdefense.tower.TowerStructure;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Shared upgrade/sell operations for towers, used by both {@code /td} and the My Towers panel. */
public final class TowerService {
	/** Fraction of coins invested returned on sell. */
	public static final double REFUND_FRACTION = 0.5;

	public record Result(boolean ok, String message) {
	}

	private TowerService() {
	}

	/** Cost to upgrade this tower to its next tier = base catalogue price x current tier. */
	public static int upgradeCost(ServerWorld world, AbstractTowerBlockEntity tower) {
		return TdCommand.priceOfPublic(world, tower.getPos()) * tower.getTier();
	}

	/** Coins returned if this tower is sold now. Falls back to the base price for towers
	 *  placed before invested-tracking existed, so old towers still refund something. */
	public static int refund(ServerWorld world, AbstractTowerBlockEntity tower) {
		int invested = Math.max(tower.getInvested(), TdCommand.priceOfPublic(world, tower.getPos()));
		return (int) Math.floor(invested * REFUND_FRACTION);
	}

	public static Result upgrade(ServerWorld world, ServerPlayerEntity player, BlockPos pos) {
		if (!(world.getBlockEntity(pos) instanceof AbstractTowerBlockEntity tower)) {
			return new Result(false, "That tower no longer exists.");
		}
		if (!player.getUuid().equals(tower.getPlacerUuid())) {
			return new Result(false, "You can only upgrade your own towers.");
		}
		if (tower.getTier() >= AbstractTowerBlockEntity.MAX_TIER) {
			return new Result(false, "That tower is already at max tier.");
		}
		int cost = upgradeCost(world, tower);
		if (TdCommand.countCoinsPublic(player) < cost) {
			return new Result(false, "Not enough coins to upgrade: need " + cost + ".");
		}
		TdCommand.removeCoinsPublic(player, cost);
		tower.upgrade();
		tower.setPlacer(player.getUuid());
		tower.addInvested(cost);
		return new Result(true, "Upgraded to tier " + tower.getTier() + " for " + cost + " coins.");
	}

	public static Result sell(ServerWorld world, ServerPlayerEntity player, BlockPos pos) {
		if (!(world.getBlockEntity(pos) instanceof AbstractTowerBlockEntity tower)) {
			return new Result(false, "That tower no longer exists.");
		}
		if (!player.getUuid().equals(tower.getPlacerUuid())) {
			return new Result(false, "You can only sell your own towers.");
		}
		int refund = refund(world, tower);
		TowerStructure.clear(world, pos);
		TdArenaState.get(world.getServer()).removeTower(pos);
		if (refund > 0) {
			ItemStack coins = new ItemStack(ModItems.COIN, refund);
			if (!player.getInventory().insertStack(coins)) {
				player.dropItem(coins, false);
			}
		}
		return new Result(true, "Sold tower for " + refund + " coins.");
	}
}
