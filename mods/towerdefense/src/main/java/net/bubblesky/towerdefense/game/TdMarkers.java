package net.bubblesky.towerdefense.game;

import net.bubblesky.towerdefense.state.TdArenaState;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Physical, in-world markers for the pick-your-spots setup flow: a glowing GOLD
 * "Idol" pillar at the point being defended, and a soul-lit gate at each enemy
 * spawn point, each topped with a floating name label so players can SEE where
 * they placed things.
 *
 * <p>Purely cosmetic + navigational — the game logic in {@link WaveManager} keys
 * off {@link TdArenaState} (not these blocks) — so placement/removal is
 * best-effort and never required for correctness. Removal only clears a block if
 * it still matches the marker type we placed, so it won't grief unrelated builds.
 */
public final class TdMarkers {
	private TdMarkers() {
	}

	/** Entity tag on the floating "The Idol" label so it can be killed on reset. */
	private static final String IDOL_TAG = "td_idol_label";
	/** Entity tag on every floating spawn-gate label so they can be killed on reset. */
	private static final String SPAWN_TAG = "td_spawn_label";

	/** Raise the Idol as a small SHRINE the enemies attack: a 3x3 gold plinth (also a
	 *  valid beacon base, so the beam projects) with a beacon beam at its centre and a
	 *  glowing sea-lantern at each corner, topped by a floating "The Idol" label. */
	public static void placeIdol(ServerWorld world, BlockPos pos) {
		if (world == null || pos == null) {
			return;
		}
		clearIdol(world, pos);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				world.setBlockState(pos.add(dx, 0, dz), Blocks.GOLD_BLOCK.getDefaultState());
			}
		}
		world.setBlockState(pos.up(), Blocks.BEACON.getDefaultState());
		world.setBlockState(pos.add(1, 1, 1), Blocks.SEA_LANTERN.getDefaultState());
		world.setBlockState(pos.add(-1, 1, 1), Blocks.SEA_LANTERN.getDefaultState());
		world.setBlockState(pos.add(1, 1, -1), Blocks.SEA_LANTERN.getDefaultState());
		world.setBlockState(pos.add(-1, 1, -1), Blocks.SEA_LANTERN.getDefaultState());
		label(world, pos.up(3), "The Idol", IDOL_TAG);
	}

	/** Mark an enemy spawn gate: crying-obsidian base + soul lantern + numbered label. */
	public static void placeSpawn(ServerWorld world, BlockPos pos, int index) {
		if (world == null || pos == null) {
			return;
		}
		world.setBlockState(pos, Blocks.CRYING_OBSIDIAN.getDefaultState());
		world.setBlockState(pos.up(), Blocks.SOUL_LANTERN.getDefaultState());
		label(world, pos.up(2), "Enemy Spawn #" + index, SPAWN_TAG);
	}

	/** Remove the idol pillar blocks at {@code pos} (only if still ours) + its label. */
	public static void clearIdol(ServerWorld world, BlockPos pos) {
		if (world == null || pos == null) {
			return;
		}
		removeIfMatches(world, pos, Blocks.GOLD_BLOCK);
		removeIfMatches(world, pos.up(), Blocks.GOLD_BLOCK);
		removeIfMatches(world, pos.up(2), Blocks.BEACON);
		killLabels(world.getServer(), IDOL_TAG);
	}

	/** Remove a spawn-gate marker at {@code pos} (only if still ours). Labels via {@link #clearAll}. */
	public static void clearSpawn(ServerWorld world, BlockPos pos) {
		if (world == null || pos == null) {
			return;
		}
		removeIfMatches(world, pos, Blocks.CRYING_OBSIDIAN);
		removeIfMatches(world, pos.up(), Blocks.SOUL_LANTERN);
	}

	/** Best-effort removal of every idol + spawn marker (blocks + labels) for the arena. */
	public static void clearAll(ServerWorld world, TdArenaState st) {
		if (world == null || st == null) {
			return;
		}
		if (st.base != null) {
			clearIdol(world, st.base);
		}
		for (BlockPos sp : st.spawnPoints) {
			clearSpawn(world, sp);
		}
		killLabels(world.getServer(), IDOL_TAG);
		killLabels(world.getServer(), SPAWN_TAG);
	}

	private static void removeIfMatches(ServerWorld world, BlockPos pos, Block expected) {
		if (world.getBlockState(pos).isOf(expected)) {
			world.setBlockState(pos, Blocks.AIR.getDefaultState());
		}
	}

	/** Summon an invisible, floating name label armor stand tagged for later removal. */
	private static void label(ServerWorld world, BlockPos pos, String text, String tag) {
		String clean = text.replace("\"", "").replace("\\", "");
		String cmd = String.format(
			"summon minecraft:armor_stand %.1f %.1f %.1f "
			+ "{Invisible:1b,Marker:1b,NoGravity:1b,CustomNameVisible:1b,"
			+ "CustomName:'\"%s\"',Tags:[\"%s\"]}",
			pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5, clean, tag);
		runSilent(world.getServer(), cmd);
	}

	private static void killLabels(MinecraftServer server, String tag) {
		runSilent(server, "kill @e[tag=" + tag + "]");
	}

	/** Run a server command from a silent, level-4 source (no console/chat spam). */
	private static void runSilent(MinecraftServer server, String cmd) {
		if (server == null) {
			return;
		}
		ServerCommandSource source = server.getCommandSource()
			.withOutput(CommandOutput.DUMMY)
			.withLevel(4)
			.withSilent();
		server.getCommandManager().executeWithPrefix(source, cmd);
	}
}
