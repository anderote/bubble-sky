package net.bubblesky.towerdefense.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.bubblesky.towerdefense.game.WaveManager;
import net.bubblesky.towerdefense.registry.ModBlocks;
import net.bubblesky.towerdefense.state.TdArenaState;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/**
 * The {@code /td} command family: the human-facing control panel for setting up
 * and running a tower-defense match. All arena state is stored in
 * {@link TdArenaState} (world-saved data), so commands just mutate that and the
 * {@link WaveManager} tick loop does the rest.
 */
public final class TdCommand {
	private TdCommand() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("td")
			.requires(src -> src.hasPermissionLevel(2))
			.executes(TdCommand::help)
			.then(CommandManager.literal("tower")
				.executes(ctx -> tower(ctx, "arrow_tower"))
				.then(CommandManager.argument("type", StringArgumentType.word())
					.executes(ctx -> tower(ctx, StringArgumentType.getString(ctx, "type")))))
			.then(CommandManager.literal("spawn").executes(TdCommand::spawn))
			.then(CommandManager.literal("base")
				.executes(ctx -> base(ctx, TdArenaState.DEFAULT_BASE_HP))
				.then(CommandManager.argument("hp", IntegerArgumentType.integer(1))
					.executes(ctx -> base(ctx, IntegerArgumentType.getInteger(ctx, "hp")))))
			.then(CommandManager.literal("wave").executes(TdCommand::wave))
			.then(CommandManager.literal("start").executes(TdCommand::wave))
			.then(CommandManager.literal("status").executes(TdCommand::status))
			.then(CommandManager.literal("reset").executes(TdCommand::reset)));
	}

	private static int help(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		src.sendFeedback(() -> Text.literal("Tower Defense commands:").formatted(Formatting.GOLD), false);
		line(src, "/td base [hp]", "set the base to defend at your position (default 100 HP)");
		line(src, "/td spawn", "add an enemy spawn point at your position");
		line(src, "/td tower [type]", "place a tower where you're looking (default arrow_tower)");
		line(src, "/td wave", "start the next wave (alias: /td start)");
		line(src, "/td status", "show wave, enemies alive, base HP, spawn count");
		line(src, "/td reset", "clear the arena (waves, enemies, spawns, base)");
		return 1;
	}

	private static void line(ServerCommandSource src, String cmd, String desc) {
		src.sendFeedback(() -> Text.literal("  " + cmd).formatted(Formatting.YELLOW)
			.append(Text.literal(" - " + desc).formatted(Formatting.GRAY)), false);
	}

	private static int tower(CommandContext<ServerCommandSource> ctx, String type) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity player = src.getPlayerOrThrow();
		ServerWorld world = src.getWorld();

		// Only the arrow_tower exists so far; accept the type for forward-compat.
		if (!type.equals("arrow_tower")) {
			src.sendError(Text.literal("Unknown tower type '" + type + "'. Placing arrow_tower."));
		}

		HitResult hit = player.raycast(30.0, 1.0f, false);
		if (hit.getType() != HitResult.Type.BLOCK) {
			src.sendError(Text.literal("Look at a block within 30 blocks to place a tower."));
			return 0;
		}
		BlockHitResult bhr = (BlockHitResult) hit;
		BlockPos placePos = bhr.getBlockPos().offset(bhr.getSide());
		if (!world.getBlockState(placePos).isReplaceable()) {
			src.sendError(Text.literal("No room to place a tower there."));
			return 0;
		}
		world.setBlockState(placePos, ModBlocks.ARROW_TOWER.getDefaultState());
		src.sendFeedback(() -> Text.literal("Placed arrow_tower at " + placePos.toShortString())
			.formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int spawn(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity player = src.getPlayerOrThrow();
		TdArenaState st = TdArenaState.get(src.getServer());
		BlockPos pos = player.getBlockPos();
		st.spawnPoints.add(pos);
		if (st.worldId.isEmpty()) {
			st.worldId = src.getWorld().getRegistryKey().getValue().toString();
		}
		st.markDirty();
		src.sendFeedback(() -> Text.literal("Spawn point #" + st.spawnPoints.size()
			+ " added at " + pos.toShortString()).formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int base(CommandContext<ServerCommandSource> ctx, int hp) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity player = src.getPlayerOrThrow();
		TdArenaState st = TdArenaState.get(src.getServer());
		st.base = player.getBlockPos();
		st.baseMaxHp = hp;
		st.baseHp = hp;
		st.worldId = src.getWorld().getRegistryKey().getValue().toString();
		st.gameOver = false;
		st.markDirty();
		src.sendFeedback(() -> Text.literal("Base set at " + st.base.toShortString()
			+ " with " + hp + " HP.").formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int wave(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		Text result = WaveManager.startNextWave(src.getServer());
		src.sendFeedback(() -> result, true);
		return 1;
	}

	private static int status(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		TdArenaState st = TdArenaState.get(src.getServer());
		int alive = 0;
		ServerWorld arena = st.getArenaWorld(src.getServer());
		if (st.base != null && arena != null) {
			alive = (int) arena.getOtherEntities(null,
				new net.minecraft.util.math.Box(st.base).expand(96.0),
				e -> e.isAlive() && e.getCommandTags().contains(WaveManager.ENEMY_TAG)).size();
		}
		final int aliveF = alive;
		src.sendFeedback(() -> Text.literal("Tower Defense status").formatted(Formatting.GOLD), false);
		src.sendFeedback(() -> Text.literal("  Phase: " + st.phase + "  Wave: " + st.currentWave
			+ (st.gameOver ? " (GAME OVER)" : "")).formatted(Formatting.YELLOW), false);
		src.sendFeedback(() -> Text.literal("  Enemies alive: " + aliveF
			+ (st.enemiesRemaining > 0 ? " (+" + st.enemiesRemaining + " to spawn)" : ""))
			.formatted(Formatting.YELLOW), false);
		src.sendFeedback(() -> Text.literal("  Base HP: "
			+ (st.base == null ? "no base" : st.baseHp + "/" + st.baseMaxHp))
			.formatted(Formatting.YELLOW), false);
		src.sendFeedback(() -> Text.literal("  Spawn points: " + st.spawnPoints.size())
			.formatted(Formatting.YELLOW), false);
		return 1;
	}

	private static int reset(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		TdArenaState st = TdArenaState.get(src.getServer());
		// Discard any live enemies before wiping the arena config.
		ServerWorld arena = st.getArenaWorld(src.getServer());
		if (st.base != null && arena != null) {
			arena.getOtherEntities(null, new net.minecraft.util.math.Box(st.base).expand(128.0),
				e -> e.getCommandTags().contains(WaveManager.ENEMY_TAG)).forEach(net.minecraft.entity.Entity::discard);
		}
		st.clear();
		src.sendFeedback(() -> Text.literal("Arena reset.").formatted(Formatting.GREEN), true);
		return 1;
	}
}
