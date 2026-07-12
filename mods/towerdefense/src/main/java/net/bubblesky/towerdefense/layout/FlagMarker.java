package net.bubblesky.towerdefense.layout;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Shared flag-planting helper: the ONE place that turns "a name + a position" into
 * a stored {@link LayoutStore.Flag} plus its physical in-world marker (a colored-wool
 * base, a lightning-rod spire, and a floating armor-stand label).
 *
 * <p>Both the {@code LayoutWandItem} (shift-right-click) and the {@code FlagBowItem}
 * (arrow impact, via {@code FlagArrowEntity}) call {@link #plantFlag} so wand-planted
 * and bow-planted flags are byte-for-byte identical and land in the same
 * {@link LayoutStore} list / HTTP bridge / persistence file.
 */
public final class FlagMarker {
	private FlagMarker() {
	}

	private static final Block[] MARKER_WOOLS = {
		Blocks.RED_WOOL, Blocks.ORANGE_WOOL, Blocks.YELLOW_WOOL, Blocks.LIME_WOOL,
		Blocks.LIGHT_BLUE_WOOL, Blocks.MAGENTA_WOOL, Blocks.PINK_WOOL, Blocks.CYAN_WOOL,
		Blocks.PURPLE_WOOL, Blocks.WHITE_WOOL
	};

	/**
	 * Store a named flag in {@link LayoutStore} AND place its physical marker.
	 * Returns the stored record. Server-thread only.
	 */
	public static LayoutStore.Flag plantFlag(ServerWorld world, BlockPos pos, String name, String dim) {
		LayoutStore.Flag flag = LayoutStore.putFlag(name, pos.getX(), pos.getY(), pos.getZ(), dim);
		placeMarker(world, name, pos);
		return flag;
	}

	/** Drop the wool base + lightning-rod spire + floating name label at {@code pos}. */
	public static void placeMarker(ServerWorld world, String name, BlockPos pos) {
		Block wool = MARKER_WOOLS[Math.floorMod(name.hashCode(), MARKER_WOOLS.length)];
		world.setBlockState(pos, wool.getDefaultState());
		world.setBlockState(pos.up(), Blocks.LIGHTNING_ROD.getDefaultState());
		// Floating name label: an invisible, no-gravity marker armor stand, tagged
		// so it (and only it) can be removed later.
		String label = name.replace("\"", "").replace("\\", "");
		String cmd = String.format(
			"summon minecraft:armor_stand %.1f %.1f %.1f "
			+ "{Invisible:1b,Marker:1b,NoGravity:1b,CustomNameVisible:1b,"
			+ "CustomName:'\"%s\"',Tags:[\"layoutflag\",\"%s\"]}",
			pos.getX() + 0.5, pos.getY() + 1.3, pos.getZ() + 0.5, label, tagOf(name));
		runSilent(world.getServer(), cmd);
	}

	/** Remove a flag's wool base, lightning rod, and floating label. */
	public static void clearMarker(ServerWorld world, LayoutStore.Flag flag) {
		BlockPos pos = new BlockPos(flag.x(), flag.y(), flag.z());
		if (world.getBlockState(pos.up()).isOf(Blocks.LIGHTNING_ROD)) {
			world.setBlockState(pos.up(), Blocks.AIR.getDefaultState());
		}
		Block below = world.getBlockState(pos).getBlock();
		for (Block w : MARKER_WOOLS) {
			if (below == w) {
				world.setBlockState(pos, Blocks.AIR.getDefaultState());
				break;
			}
		}
		runSilent(world.getServer(), "kill @e[tag=" + tagOf(flag.name()) + "]");
	}

	/** Dimension path id ("overworld", "the_nether", …) for the flag's {@code dim} field. */
	public static String dimensionId(ServerWorld world) {
		return world.getRegistryKey().getValue().getPath();
	}

	/** First letter of a player's name (uppercased) for auto-naming, or "A" as a fallback. */
	public static String initialOf(String playerName) {
		if (playerName != null) {
			for (int i = 0; i < playerName.length(); i++) {
				char c = playerName.charAt(i);
				if (Character.isLetter(c)) {
					return String.valueOf(Character.toUpperCase(c));
				}
			}
		}
		return "A";
	}

	private static String tagOf(String name) {
		return "layoutflag_" + name.toLowerCase().replaceAll("[^a-z0-9_]", "_");
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
