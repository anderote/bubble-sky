package net.bubblesky.towerdefense.colony;

import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * The physical, in-world marker for a colony HOME FLAG — the visual twin of
 * {@link net.bubblesky.towerdefense.game.TdMarkers}. Plants a short lodestone plinth
 * topped by a banner ("the flag") and a floating name label so players can SEE where
 * their colony is anchored.
 *
 * <p>Cosmetic only: the colony logic keys off {@link ColonyState}, not this block, so
 * placement is best-effort and never required for correctness.
 */
public final class ColonyMarkers {
	private ColonyMarkers() {
	}

	/** Entity tag on every floating colony-flag label so they can be culled later. */
	private static final String FLAG_TAG = "td_colony_label";

	/** Raise a colony flag marker at {@code pos}: lodestone base + banner + floating label. */
	public static void placeFlag(ServerWorld world, BlockPos pos, String name) {
		if (world == null || pos == null) {
			return;
		}
		world.setBlockState(pos, Blocks.LODESTONE.getDefaultState());
		world.setBlockState(pos.up(), Blocks.YELLOW_BANNER.getDefaultState());
		label(world, pos.up(2), "🏴 " + name, FLAG_TAG);
	}

	/** Summon an invisible floating name label (armor stand) tagged for later removal. */
	private static void label(ServerWorld world, BlockPos pos, String text, String tag) {
		String clean = text.replace("\"", "").replace("\\", "");
		String cmd = String.format(
			"summon minecraft:armor_stand %.1f %.1f %.1f "
			+ "{Invisible:1b,Marker:1b,NoGravity:1b,CustomNameVisible:1b,"
			+ "CustomName:'\"%s\"',Tags:[\"%s\"]}",
			pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5, clean, tag);
		runSilent(world.getServer(), cmd);
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
