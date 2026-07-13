package net.bubblesky.towerdefense.tower;

import java.util.UUID;
import net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity;
import net.bubblesky.towerdefense.state.TdArenaState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Places (and removes) a tower as a SINGLE functional block. Every tower kind —
 * arrow / cannon / frost / ball — is a one-block unit that fires from its outer
 * face toward the target (the muzzle offset lives in {@link AbstractTowerBlockEntity});
 * there is no decorative pole/orb structure. Because the placement cell is the empty
 * cell against whatever surface was targeted, a tower can sit on the ground or stick
 * to a wall.
 */
public final class TowerStructure {
	private TowerStructure() {
	}

	/**
	 * Place the functional tower {@code kind} at {@code pos} as a single block, stamp
	 * the placer, and register it with {@link TdArenaState} so {@code /td reset} can
	 * clear it later. Returns the tower position. Server-thread only.
	 */
	public static BlockPos build(ServerWorld world, BlockPos pos, TowerKind kind, @Nullable UUID placer) {
		return build(world, pos, kind, placer, 1);
	}

	/** Place the tower already upgraded to {@code tier}, stamping placer + the coins invested to reach it. */
	public static BlockPos build(ServerWorld world, BlockPos pos, TowerKind kind, @Nullable UUID placer, int tier) {
		world.setBlockState(pos, kind.block().getDefaultState());
		int base = net.bubblesky.towerdefense.command.TdCommand.priceOfPublic(world, pos);
		int invested = net.bubblesky.towerdefense.command.TdCommand.costToTier(base, tier);
		if (world.getBlockEntity(pos) instanceof AbstractTowerBlockEntity tower) {
			if (placer != null) {
				tower.setPlacer(placer);
			}
			tower.setTier(tier);
			tower.setInvested(invested);
		}
		TdArenaState.get(world.getServer()).addTower(pos);
		return pos;
	}

	/** Clear the single-block tower at {@code core} (no-op if it is not, or no longer, a tower). */
	public static void clear(ServerWorld world, BlockPos core) {
		BlockEntity be = world.getBlockEntity(core);
		if (be instanceof AbstractTowerBlockEntity) {
			world.setBlockState(core, Blocks.AIR.getDefaultState());
		}
	}
}
