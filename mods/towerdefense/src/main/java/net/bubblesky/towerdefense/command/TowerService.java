package net.bubblesky.towerdefense.command;

import net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity;
import net.bubblesky.towerdefense.state.TdArenaState;
import net.bubblesky.towerdefense.tower.TowerKind;
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
			// Independent spending: the refund goes to the SELLER only (wave/kill income is
			// shared/equal, but each player controls their own balance).
			TdCommand.grantCoinsPublic(player, refund);
		}
		return new Result(true, "Sold tower for " + refund + " coins.");
	}

	/**
	 * Recycle a tower: owner-only teardown that returns the placeable tower ITEM (the same
	 * block item {@code /td buy} hands out for this kind) instead of any coins. The block is
	 * returned at base tier — any coins invested in upgrades are forfeit, mirroring how buying a
	 * fresh tower always starts at tier 1. The item is inserted into the player's pack, or
	 * dropped at their feet if there is no room. No coin refund is given (they get the block).
	 */
	public static Result recycle(ServerWorld world, ServerPlayerEntity player, BlockPos pos) {
		if (!(world.getBlockEntity(pos) instanceof AbstractTowerBlockEntity tower)) {
			return new Result(false, "That tower no longer exists.");
		}
		if (!player.getUuid().equals(tower.getPlacerUuid())) {
			return new Result(false, "You can only recycle your own towers.");
		}
		// Snapshot the kind BEFORE teardown (clear() replaces the block entity with air).
		TowerKind kind = tower.kind();
		TowerStructure.clear(world, pos);
		TdArenaState.get(world.getServer()).removeTower(pos);
		ItemStack stack = new ItemStack(kind.block());
		if (!player.getInventory().insertStack(stack)) {
			player.dropItem(stack, false); // pack full — drop at the player's feet
		}
		return new Result(true, "Recycled tower — the block is back in your inventory.");
	}

	/**
	 * Destroy a tower: owner-only teardown with NO refund and NO item returned — the tower and
	 * everything invested in it are gone. Distinct from {@link #recycle} (which hands the block
	 * back) and {@link #sell} (which returns coins); this is the "just remove it" option.
	 */
	public static Result destroy(ServerWorld world, ServerPlayerEntity player, BlockPos pos) {
		if (!(world.getBlockEntity(pos) instanceof AbstractTowerBlockEntity tower)) {
			return new Result(false, "That tower no longer exists.");
		}
		if (!player.getUuid().equals(tower.getPlacerUuid())) {
			return new Result(false, "You can only destroy your own towers.");
		}
		TowerStructure.clear(world, pos);
		TdArenaState.get(world.getServer()).removeTower(pos);
		return new Result(true, "Destroyed tower.");
	}
}
