package net.bubblesky.towerdefense.item;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.bubblesky.towerdefense.layout.LayoutStore;
import net.bubblesky.towerdefense.registry.ModItems;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * The Layout Wand — an in-game planning tool that plants named FLAGS and defines
 * rectangular REGIONS, both stored server-side in {@link LayoutStore} and exposed
 * over the mod's HTTP bridge so the AI agents build to the plan.
 *
 * <p><b>Click scheme</b> (all authoritative on the server):
 * <ul>
 *   <li><b>Shift + right-click a block</b> → plant a FLAG at the clicked face
 *       (auto-named {@code <initial>1}, {@code <initial>2}, … from the player's
 *       initial). Drops a colored-wool + lightning-rod marker and a floating
 *       armor-stand label with the name.</li>
 *   <li><b>Right-click block A, then block B</b> (no sneak) → define a rectangular
 *       REGION A→B (auto-named "Region 1", 2, …). First click sets corner 1 (with
 *       feedback); second click saves the region + clears the selection.</li>
 *   <li><b>Sneak + left-click near a flag</b> (within ~3 blocks) → remove that
 *       flag and its marker/label.</li>
 *   <li><b>Left-click (no sneak)</b> → clear the current region selection.</li>
 * </ul>
 *
 * <p>While a player HOLDS the wand, a throttled server tick outlines every region
 * and draws a column at every flag with particles sent only to that player.
 */
public class LayoutWandItem extends Item {
	/** Max distance (blocks) a sneak+left-click can be from a flag to remove it. */
	private static final double FLAG_REMOVE_RADIUS = 3.0;
	/** Particle refresh cadence + how far from a player we bother drawing. */
	private static final int PARTICLE_INTERVAL = 10;
	private static final double PARTICLE_MAX_DIST = 64.0;

	/** Pending region corner-A per player (cleared when the region is saved or reset). */
	private static final Map<UUID, BlockPos> PENDING_CORNER = new HashMap<>();

	private static final Block[] MARKER_WOOLS = {
		Blocks.RED_WOOL, Blocks.ORANGE_WOOL, Blocks.YELLOW_WOOL, Blocks.LIME_WOOL,
		Blocks.LIGHT_BLUE_WOOL, Blocks.MAGENTA_WOOL, Blocks.PINK_WOOL, Blocks.CYAN_WOOL,
		Blocks.PURPLE_WOOL, Blocks.WHITE_WOOL
	};

	public LayoutWandItem(Settings settings) {
		super(settings);
	}

	/** Wire the left-click + particle-tick hooks. Call once from the mod initializer. */
	public static void register() {
		AttackBlockCallback.EVENT.register(LayoutWandItem::onLeftClick);
		ServerTickEvents.END_SERVER_TICK.register(LayoutWandItem::onTick);
	}

	// ---- right-click: plant flag (sneak) or region corner (no sneak) -------

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		World world = context.getWorld();
		if (world.isClient) {
			return ActionResult.SUCCESS;
		}
		if (!(world instanceof ServerWorld serverWorld) || !(context.getPlayer() instanceof ServerPlayerEntity player)) {
			return ActionResult.PASS;
		}
		// Plant at the empty cell adjacent to the clicked face (top face → on top).
		BlockPos pos = context.getBlockPos().offset(context.getSide());
		String dim = dimensionId(serverWorld);

		if (player.isSneaking()) {
			plantFlag(serverWorld, player, pos, dim);
		} else {
			regionClick(serverWorld, player, pos, dim);
		}
		return ActionResult.SUCCESS;
	}

	private void plantFlag(ServerWorld world, ServerPlayerEntity player, BlockPos pos, String dim) {
		String letter = initialOf(player.getName().getString());
		String name = LayoutStore.nextFlagName(letter);
		LayoutStore.putFlag(name, pos.getX(), pos.getY(), pos.getZ(), dim);
		placeMarker(world, name, pos);
		player.sendMessage(Text.literal("Planted flag ")
			.append(Text.literal(name).formatted(Formatting.AQUA))
			.append(Text.literal(String.format(" at (%d, %d, %d)", pos.getX(), pos.getY(), pos.getZ()))), false);
	}

	private void regionClick(ServerWorld world, ServerPlayerEntity player, BlockPos pos, String dim) {
		BlockPos a = PENDING_CORNER.get(player.getUuid());
		if (a == null) {
			PENDING_CORNER.put(player.getUuid(), pos);
			player.sendMessage(Text.literal("Region corner 1 set at ")
				.append(Text.literal(String.format("(%d, %d, %d)", pos.getX(), pos.getY(), pos.getZ()))
					.formatted(Formatting.YELLOW))
				.append(Text.literal(" — right-click the opposite corner.")), false);
			return;
		}
		PENDING_CORNER.remove(player.getUuid());
		String name = LayoutStore.nextRegionName();
		LayoutStore.putRegion(name, a.getX(), a.getY(), a.getZ(), pos.getX(), pos.getY(), pos.getZ(), dim);
		player.sendMessage(Text.literal("Defined ")
			.append(Text.literal(name).formatted(Formatting.GREEN))
			.append(Text.literal(String.format(" (%d,%d,%d)→(%d,%d,%d)",
				a.getX(), a.getY(), a.getZ(), pos.getX(), pos.getY(), pos.getZ()))), false);
	}

	// ---- left-click: remove flag (sneak) or clear selection ----------------

	private static ActionResult onLeftClick(net.minecraft.entity.player.PlayerEntity player, World world,
			Hand hand, BlockPos pos, Direction direction) {
		ItemStack held = player.getMainHandStack();
		if (held == null || held.getItem() != ModItems.LAYOUT_WAND) {
			return ActionResult.PASS;
		}
		if (world.isClient || !(player instanceof ServerPlayerEntity serverPlayer)) {
			// Cancel the break client-side too so the wand never damages blocks.
			return ActionResult.SUCCESS;
		}
		if (serverPlayer.isSneaking()) {
			LayoutStore.Flag near = LayoutStore.nearestFlag(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
				FLAG_REMOVE_RADIUS);
			if (near == null) {
				serverPlayer.sendMessage(Text.literal("No flag within "
					+ (int) FLAG_REMOVE_RADIUS + " blocks to remove."), false);
			} else {
				LayoutStore.removeFlag(near.name());
				clearMarker((ServerWorld) world, near);
				serverPlayer.sendMessage(Text.literal("Removed flag ")
					.append(Text.literal(near.name()).formatted(Formatting.RED)), false);
			}
		} else {
			BlockPos had = PENDING_CORNER.remove(serverPlayer.getUuid());
			serverPlayer.sendMessage(Text.literal(had != null
				? "Cleared the region selection." : "No region selection to clear."), false);
		}
		return ActionResult.SUCCESS;
	}

	// ---- markers -----------------------------------------------------------

	private void placeMarker(ServerWorld world, String name, BlockPos pos) {
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

	private static void clearMarker(ServerWorld world, LayoutStore.Flag flag) {
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

	private static String tagOf(String name) {
		return "layoutflag_" + name.toLowerCase().replaceAll("[^a-z0-9_]", "_");
	}

	// ---- particle visualization (only while a player holds the wand) -------

	private static int tickCounter;

	private static void onTick(MinecraftServer server) {
		if (tickCounter++ % PARTICLE_INTERVAL != 0) {
			return;
		}
		if (LayoutStore.flags().isEmpty() && LayoutStore.regions().isEmpty()) {
			return;
		}
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			if (!holdingWand(player)) {
				continue;
			}
			ServerWorld world = player.getWorld();
			double px = player.getX(), py = player.getY(), pz = player.getZ();
			for (LayoutStore.Flag f : LayoutStore.flags()) {
				if (within(f.x(), f.y(), f.z(), px, py, pz)) {
					drawFlagColumn(world, player, f);
				}
			}
			for (LayoutStore.Region r : LayoutStore.regions()) {
				double cx = (r.ax() + r.bx()) / 2.0, cy = (r.ay() + r.by()) / 2.0, cz = (r.az() + r.bz()) / 2.0;
				if (within(cx, cy, cz, px, py, pz)) {
					drawRegionOutline(world, player, r);
				}
			}
		}
	}

	private static boolean holdingWand(ServerPlayerEntity player) {
		return player.getMainHandStack().getItem() == ModItems.LAYOUT_WAND
			|| player.getOffHandStack().getItem() == ModItems.LAYOUT_WAND;
	}

	private static boolean within(double x, double y, double z, double px, double py, double pz) {
		double dx = x - px, dy = y - py, dz = z - pz;
		return dx * dx + dy * dy + dz * dz <= PARTICLE_MAX_DIST * PARTICLE_MAX_DIST;
	}

	private static void drawFlagColumn(ServerWorld world, ServerPlayerEntity player, LayoutStore.Flag f) {
		double x = f.x() + 0.5, z = f.z() + 0.5;
		for (int dy = 0; dy <= 3; dy++) {
			world.spawnParticles(player, ParticleTypes.END_ROD, true, false,
				x, f.y() + dy, z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	private static void drawRegionOutline(ServerWorld world, ServerPlayerEntity player, LayoutStore.Region r) {
		int x0 = Math.min(r.ax(), r.bx()), x1 = Math.max(r.ax(), r.bx());
		int y0 = Math.min(r.ay(), r.by()), y1 = Math.max(r.ay(), r.by());
		int z0 = Math.min(r.az(), r.bz()), z1 = Math.max(r.az(), r.bz());
		DustParticleEffect dust = new DustParticleEffect(0x30E060, 1.4f); // green
		// Sample the 12 edges of the box at ~2-block spacing.
		for (int y : new int[] {y0, y1 + 1}) {
			edgeX(world, player, dust, x0, x1 + 1, y, z0);
			edgeX(world, player, dust, x0, x1 + 1, y, z1 + 1);
			edgeZ(world, player, dust, z0, z1 + 1, y, x0);
			edgeZ(world, player, dust, z0, z1 + 1, y, x1 + 1);
		}
		for (int x : new int[] {x0, x1 + 1}) {
			for (int z : new int[] {z0, z1 + 1}) {
				for (int y = y0; y <= y1 + 1; y += 2) {
					world.spawnParticles(player, dust, true, false, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
				}
			}
		}
	}

	private static void edgeX(ServerWorld world, ServerPlayerEntity player, DustParticleEffect dust,
			int x0, int x1, int y, int z) {
		for (int x = x0; x <= x1; x += 2) {
			world.spawnParticles(player, dust, true, false, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	private static void edgeZ(ServerWorld world, ServerPlayerEntity player, DustParticleEffect dust,
			int z0, int z1, int y, int x) {
		for (int z = z0; z <= z1; z += 2) {
			world.spawnParticles(player, dust, true, false, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	// ---- misc --------------------------------------------------------------

	private static String initialOf(String playerName) {
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

	private static String dimensionId(ServerWorld world) {
		return world.getRegistryKey().getValue().getPath(); // "overworld", "the_nether", …
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
