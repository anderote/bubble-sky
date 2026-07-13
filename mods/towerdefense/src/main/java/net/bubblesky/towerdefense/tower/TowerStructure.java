package net.bubblesky.towerdefense.tower;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity;
import net.bubblesky.towerdefense.state.TdArenaState;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Builds (and tears down) the tall thin "stick tower" that a landed tower arrow
 * raises: a 1x1 decorative {@link TowerKind#pole() pole} rising {@link #POLE_HEIGHT}
 * blocks, topped by a small {@link TowerKind#ball() ball}/orb, with the FUNCTIONAL
 * tower {@link Block} (the ArrowTower/Cannon/Frost core, whose block entity does the
 * firing) sitting at the ball's centre so its range/targeting works from up high.
 *
 * <p>The structure is fully deterministic from the core position + kind, so removal
 * ({@link #clear}) recomputes the same cells and only clears blocks that still match
 * this kind's palette — best-effort, never griefing unrelated builds.
 */
public final class TowerStructure {
	private TowerStructure() {
	}

	/** Height of the 1x1 pole (blocks {@code base .. base+POLE_HEIGHT-1}); orb sits above. */
	public static final int POLE_HEIGHT = 6;

	/**
	 * Raise a stick tower at {@code base} (the empty cell the arrow landed in) and
	 * place the functional core at the top. Registers the core with {@link TdArenaState}
	 * so {@code /td reset} can clear the whole structure later. Returns the core position.
	 * Server-thread only.
	 */
	public static BlockPos build(ServerWorld world, BlockPos base, TowerKind kind, @Nullable UUID placer) {
		BlockPos core = base.up(POLE_HEIGHT);

		// 1x1 pole.
		for (int i = 0; i < POLE_HEIGHT; i++) {
			world.setBlockState(base.up(i), kind.pole().getDefaultState());
		}
		// Ball/orb around the core (skip the very centre — the core goes there).
		for (BlockPos off : ballOffsets(kind.ballRadius())) {
			if (off.getX() == 0 && off.getY() == 0 && off.getZ() == 0) {
				continue;
			}
			world.setBlockState(core.add(off), kind.ball().getDefaultState());
		}
		// The working core LAST so nothing overwrites it, then stamp the placer.
		world.setBlockState(core, kind.block().getDefaultState());
		if (placer != null && world.getBlockEntity(core) instanceof AbstractTowerBlockEntity tower) {
			tower.setPlacer(placer);
		}

		TdArenaState.get(world.getServer()).addTower(core);
		return core;
	}

	/**
	 * Clear the stick tower whose core is at {@code core}: read the kind from the core
	 * block entity, then air out every pole/ball cell that still matches this kind's
	 * palette plus the core itself. No-op if {@code core} is not (or no longer) a tower.
	 */
	public static void clear(ServerWorld world, BlockPos core) {
		BlockEntity be = world.getBlockEntity(core);
		if (!(be instanceof AbstractTowerBlockEntity tower)) {
			return;
		}
		TowerKind kind = tower.kind();
		BlockPos base = core.down(POLE_HEIGHT);
		for (int i = 0; i < POLE_HEIGHT; i++) {
			removeIfPalette(world, base.up(i), kind);
		}
		for (BlockPos off : ballOffsets(kind.ballRadius())) {
			if (off.getX() == 0 && off.getY() == 0 && off.getZ() == 0) {
				continue;
			}
			removeIfPalette(world, core.add(off), kind);
		}
		world.setBlockState(core, Blocks.AIR.getDefaultState());
	}

	private static void removeIfPalette(ServerWorld world, BlockPos pos, TowerKind kind) {
		Block b = world.getBlockState(pos).getBlock();
		if (b == kind.pole() || b == kind.ball()) {
			world.setBlockState(pos, Blocks.AIR.getDefaultState());
		}
	}

	/** Offsets forming a rounded orb of the given radius (Euclidean fill). */
	private static List<BlockPos> ballOffsets(int r) {
		List<BlockPos> out = new ArrayList<>();
		int lim = r * r + r; // slightly-inflated sphere reads as a round ball
		for (int x = -r; x <= r; x++) {
			for (int y = -r; y <= r; y++) {
				for (int z = -r; z <= r; z++) {
					if (x * x + y * y + z * z <= lim) {
						out.add(new BlockPos(x, y, z));
					}
				}
			}
		}
		return out;
	}
}
