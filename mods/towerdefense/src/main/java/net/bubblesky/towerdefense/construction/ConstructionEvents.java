package net.bubblesky.towerdefense.construction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.bubblesky.towerdefense.construction.ConstructionGeometry.Operation;
import net.bubblesky.towerdefense.construction.net.ConfirmConstructionPayload;
import net.bubblesky.towerdefense.construction.net.UndoConstructionPayload;
import net.bubblesky.towerdefense.progression.PlayerClass;
import net.bubblesky.towerdefense.progression.ProgressState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Server-authoritative construction spell engine. Large flatten jobs are applied in small batches
 * instead of freezing a server tick. Each player gets one active job and one safe, block-state-only
 * undo snapshot; block entities and unbreakable blocks are never touched.
 */
public final class ConstructionEvents {
	private ConstructionEvents() {
	}

	private static final int MAX_SCANNED = 120_000;
	private static final int MAX_CHANGES = 80_000;
	private static final int BLOCKS_PER_TICK = 900;
	private static final Map<UUID, BuildJob> ACTIVE = new HashMap<>();
	private static final Map<UUID, UndoHistory> UNDO = new HashMap<>();

	public static void register() {
		PayloadTypeRegistry.playC2S().register(ConfirmConstructionPayload.ID, ConfirmConstructionPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(UndoConstructionPayload.ID, UndoConstructionPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ConfirmConstructionPayload.ID,
			(payload, context) -> confirm(context.player(), payload.config()));
		ServerPlayNetworking.registerGlobalReceiver(UndoConstructionPayload.ID,
			(payload, context) -> undo(context.player()));
		ServerTickEvents.END_SERVER_TICK.register(ConstructionEvents::tick);
	}

	private static void confirm(ServerPlayerEntity player, ConstructionConfig raw) {
		if (!isEngineer(player)) {
			message(player, "Advanced Build Spells are Engineer-only for now. Use /td class engineer.",
				Formatting.RED);
			return;
		}
		if (ACTIVE.containsKey(player.getUuid())) {
			message(player, "A construction spell is already building.", Formatting.RED);
			return;
		}
		ConstructionConfig config = raw.normalized();
		int scanned = ConstructionGeometry.estimatedScannedBlocks(config);
		if (scanned > MAX_SCANNED) {
			message(player, "That preview is too large (" + scanned + " cells; max " + MAX_SCANNED + ").", Formatting.RED);
			return;
		}
		ServerWorld world = player.getWorld();
		List<Change> changes = prepare(world, player, config);
		if (changes.isEmpty()) {
			message(player, "Nothing can be changed there with replace mode " + config.replaceMode().display() + ".", Formatting.YELLOW);
			return;
		}
		if (changes.size() > MAX_CHANGES) {
			message(player, "That would change " + changes.size() + " blocks; reduce its size or clear/fill depth.", Formatting.RED);
			return;
		}
		Map<Item, Integer> requiredItems = new LinkedHashMap<>();
		for (Change change : changes) {
			if (!change.after().isAir()) {
				requiredItems.merge(change.after().getBlock().asItem(), 1, Integer::sum);
			}
		}
		if (config.consumeMaterials() && !player.isCreative() && !takeItems(player, requiredItems)) {
			message(player, "Not enough matching blocks/stairs for this build; add them to your inventory"
				+ " or set Cost to Magic/free.", Formatting.RED);
			return;
		}
		BuildJob job = new BuildJob(world, player.getUuid(), changes,
			config.type().display(), false, new ArrayList<>());
		ACTIVE.put(player.getUuid(), job);
		message(player, config.type().display() + " confirmed: " + changes.size()
			+ " block changes queued. Use Build Spells → Undo after it finishes.", Formatting.GREEN);
	}

	private static List<Change> prepare(ServerWorld world, ServerPlayerEntity player, ConstructionConfig config) {
		List<Change> changes = new ArrayList<>();
		BlockState placed = config.material().block().getDefaultState();
		BlockState stair = config.material().stairBlock().getDefaultState()
			.with(Properties.HORIZONTAL_FACING, player.getHorizontalFacing());
		for (ConstructionGeometry.PlannedCell cell :
				ConstructionGeometry.cells(player.getBlockPos(), player.getHorizontalFacing(), config)) {
			BlockPos pos = cell.pos();
			if (!world.isInBuildLimit(pos)) {
				continue;
			}
			BlockState before = world.getBlockState(pos);
			if (protectedBlock(world, pos, before)) {
				continue;
			}
			BlockState after;
			if (cell.operation() == Operation.CLEAR) {
				if (before.isAir() || !canReplace(before, config.replaceMode())) {
					continue;
				}
				after = Blocks.AIR.getDefaultState();
			} else if (cell.operation() == Operation.FILL_AIR) {
				if (!(before.isAir() || before.isReplaceable())) {
					continue;
				}
				after = placed;
			} else {
				BlockState target = cell.operation() == Operation.STAIR ? stair : placed;
				if (before.equals(target) || !canReplace(before, config.replaceMode())) {
					continue;
				}
				after = target;
			}
			if (!before.equals(after)) {
				changes.add(new Change(pos.toImmutable(), before, after));
			}
		}
		return changes;
	}

	private static boolean protectedBlock(ServerWorld world, BlockPos pos, BlockState state) {
		return state.hasBlockEntity() || world.getBlockEntity(pos) != null || state.getHardness(world, pos) < 0;
	}

	private static boolean canReplace(BlockState state, ConstructionConfig.ReplaceMode mode) {
		if (state.isAir() || state.isReplaceable()) {
			return true;
		}
		if (mode == ConstructionConfig.ReplaceMode.SAFE) {
			return false;
		}
		if (mode == ConstructionConfig.ReplaceMode.ANY) {
			return true;
		}
		return state.isIn(BlockTags.DIRT)
			|| state.isIn(BlockTags.SAND)
			|| state.isIn(BlockTags.TERRACOTTA)
			|| state.isIn(BlockTags.BASE_STONE_OVERWORLD)
			|| state.isIn(BlockTags.BASE_STONE_NETHER)
			|| state.isIn(BlockTags.LOGS)
			|| state.isIn(BlockTags.LEAVES)
			|| state.isIn(BlockTags.ICE)
			|| state.isIn(BlockTags.SNOW)
			|| state.isOf(Blocks.GRAVEL)
			|| state.isOf(Blocks.CLAY)
			|| !state.getFluidState().isEmpty();
	}

	private static boolean takeItems(ServerPlayerEntity player, Map<Item, Integer> required) {
		Map<Item, Integer> available = new HashMap<>();
		for (int slot = 0; slot < player.getInventory().size(); slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (required.containsKey(stack.getItem())) {
				available.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
		for (Map.Entry<Item, Integer> entry : required.entrySet()) {
			if (available.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
				return false;
			}
		}
		for (Map.Entry<Item, Integer> entry : required.entrySet()) {
			int remaining = entry.getValue();
			for (int slot = 0; slot < player.getInventory().size() && remaining > 0; slot++) {
				ItemStack stack = player.getInventory().getStack(slot);
				if (!stack.isOf(entry.getKey())) {
					continue;
				}
				int used = Math.min(remaining, stack.getCount());
				stack.decrement(used);
				remaining -= used;
			}
		}
		player.getInventory().markDirty();
		return true;
	}

	private static void undo(ServerPlayerEntity player) {
		if (!isEngineer(player)) {
			message(player, "Advanced Build Spells are Engineer-only for now. Use /td class engineer.",
				Formatting.RED);
			return;
		}
		if (ACTIVE.containsKey(player.getUuid())) {
			message(player, "Wait for the current construction spell to finish before undoing.", Formatting.RED);
			return;
		}
		UndoHistory history = UNDO.remove(player.getUuid());
		if (history == null) {
			message(player, "Nothing to undo yet.", Formatting.YELLOW);
			return;
		}
		ServerWorld world = player.getServer().getWorld(history.world());
		if (world == null) {
			message(player, "The undo world is not loaded.", Formatting.RED);
			return;
		}
		List<Change> reverse = new ArrayList<>();
		for (int i = history.changes().size() - 1; i >= 0; i--) {
			Change change = history.changes().get(i);
			if (world.getBlockState(change.pos()).equals(change.after())) {
				reverse.add(new Change(change.pos(), change.after(), change.before()));
			}
		}
		if (reverse.isEmpty()) {
			message(player, "The last build was already changed; nothing safe to undo.", Formatting.YELLOW);
			return;
		}
		ACTIVE.put(player.getUuid(), new BuildJob(world, player.getUuid(), reverse,
			"Undo", true, new ArrayList<>()));
		message(player, "Undo queued: restoring " + reverse.size() + " blocks.", Formatting.GREEN);
	}

	private static boolean isEngineer(ServerPlayerEntity player) {
		return player.getServer() != null
			&& ProgressState.get(player.getServer()).forPlayer(player.getUuid()).getActiveClass()
				== PlayerClass.ENGINEER;
	}

	private static void tick(MinecraftServer server) {
		if (ACTIVE.isEmpty()) {
			return;
		}
		for (BuildJob job : List.copyOf(ACTIVE.values())) {
			int end = Math.min(job.index + BLOCKS_PER_TICK, job.changes.size());
			while (job.index < end) {
				Change change = job.changes.get(job.index++);
				if (!job.world.getBlockState(change.pos()).equals(change.before())) {
					continue; // someone edited this cell after confirmation; preserve their work
				}
				if (job.world.setBlockState(change.pos(), change.after(), Block.NOTIFY_ALL)) {
					job.applied.add(change);
				}
			}
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(job.playerId);
			if (job.index < job.changes.size()) {
				if (player != null && job.index % (BLOCKS_PER_TICK * 10) < BLOCKS_PER_TICK) {
					int pct = (int) Math.round(job.index * 100.0 / job.changes.size());
					player.sendMessage(Text.literal(job.label + " building… " + pct + "%")
						.formatted(Formatting.AQUA), true);
				}
				continue;
			}
			ACTIVE.remove(job.playerId);
			if (!job.undo && !job.applied.isEmpty()) {
				UNDO.put(job.playerId, new UndoHistory(job.world.getRegistryKey(), List.copyOf(job.applied)));
			}
			if (player != null) {
				message(player, job.label + " complete: " + job.applied.size() + " blocks changed.",
					Formatting.GREEN);
			}
		}
	}

	private static void message(ServerPlayerEntity player, String text, Formatting color) {
		player.sendMessage(Text.literal(text).formatted(color), false);
	}

	private record Change(BlockPos pos, BlockState before, BlockState after) {
	}

	private record UndoHistory(RegistryKey<World> world, List<Change> changes) {
	}

	private static final class BuildJob {
		private final ServerWorld world;
		private final UUID playerId;
		private final List<Change> changes;
		private final String label;
		private final boolean undo;
		private final List<Change> applied;
		private int index;

		private BuildJob(ServerWorld world, UUID playerId, List<Change> changes, String label,
				boolean undo, List<Change> applied) {
			this.world = world;
			this.playerId = playerId;
			this.changes = changes;
			this.label = label;
			this.undo = undo;
			this.applied = applied;
		}
	}
}
