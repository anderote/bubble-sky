package net.bubblesky.towerdefense.colony;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The {@code /colony} command family — the human control panel for the colony layer,
 * cloned from {@code TdCommand}'s register/permission pattern ({@code requires(src->true)}
 * so survival, non-op players play with gold, not op). All of it routes through the
 * shared {@link ColonyOrders} hub so it stays in lock-step with the chat controls.
 *
 * <ul>
 *   <li>{@code /colony flag} — plant the colony home flag at you + drop a marker.</li>
 *   <li>{@code /colony recruit} — buy a colonist for {@value ColonyOrders#RECRUIT_COST} gold.</li>
 *   <li>{@code /colony order <name|all> <job>} — assign a work job.</li>
 *   <li>{@code /colony prioritize <name|all> <job>} — bump a work type to top priority.</li>
 *   <li>{@code /colony status} — list the roster + current jobs.</li>
 * </ul>
 */
public final class ColonyCommand {
	private ColonyCommand() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("colony")
			.requires(src -> true)
			.executes(ColonyCommand::help)
			.then(CommandManager.literal("help").executes(ColonyCommand::help))
			.then(CommandManager.literal("flag")
				.executes(ctx -> ColonyOrders.flag(ctx.getSource().getPlayerOrThrow())))
			.then(CommandManager.literal("recruit")
				.executes(ctx -> ColonyOrders.recruit(ctx.getSource().getPlayerOrThrow())))
			.then(CommandManager.literal("status")
				.executes(ctx -> {
					ColonyOrders.status(ctx.getSource().getPlayerOrThrow());
					return 1;
				}))
			.then(CommandManager.literal("order")
				.then(CommandManager.argument("who", StringArgumentType.word())
					.then(CommandManager.argument("job", StringArgumentType.word())
						.executes(ctx -> order(ctx, "job", null))
						.then(CommandManager.argument("target", StringArgumentType.word())
							.executes(ctx -> order(ctx, "job", "target"))))))
			.then(CommandManager.literal("prioritize")
				.then(CommandManager.argument("who", StringArgumentType.word())
					.then(CommandManager.argument("job", StringArgumentType.word())
						.executes(ColonyCommand::prioritize)))));
	}

	private static int order(CommandContext<ServerCommandSource> ctx, String jobArg, String targetArg)
			throws CommandSyntaxException {
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		ServerWorld world = (ServerWorld) player.getWorld();
		String who = StringArgumentType.getString(ctx, "who");
		String job = StringArgumentType.getString(ctx, jobArg);
		String target = targetArg != null ? StringArgumentType.getString(ctx, targetArg) : null;

		List<ColonistEntity> colonists = ColonyOrders.resolve(world, player, who);
		if (colonists.isEmpty()) {
			player.sendMessage(Text.literal("No colonist matched '" + who
				+ "'. Try a name or 'all'.").formatted(Formatting.RED), false);
			return 0;
		}
		String feedback = ColonyOrders.apply(player, colonists, job, target);
		if (feedback == null) {
			player.sendMessage(Text.literal("Unknown job '" + job
				+ "'. Use mine, chop, hunt, forage, haul, idle or come.").formatted(Formatting.RED), false);
			return 0;
		}
		player.sendMessage(Text.literal(feedback).formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int prioritize(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		ServerWorld world = (ServerWorld) player.getWorld();
		String who = StringArgumentType.getString(ctx, "who");
		String job = StringArgumentType.getString(ctx, "job");

		List<ColonistEntity> colonists = ColonyOrders.resolve(world, player, who);
		if (colonists.isEmpty()) {
			player.sendMessage(Text.literal("No colonist matched '" + who
				+ "'. Try a name or 'all'.").formatted(Formatting.RED), false);
			return 0;
		}
		String feedback = ColonyOrders.apply(player, colonists, "prioritize", job);
		if (feedback == null) {
			player.sendMessage(Text.literal("Unknown work '" + job + "'.").formatted(Formatting.RED), false);
			return 0;
		}
		player.sendMessage(Text.literal(feedback).formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int help(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		src.sendFeedback(() -> Text.literal("========= Colony =========")
			.formatted(Formatting.GOLD, Formatting.BOLD), false);
		line(src, "/colony flag", "plant the colony home flag where you stand");
		line(src, "/colony recruit", "hire a colonist for " + ColonyOrders.RECRUIT_COST
			+ " gold (cap " + ColonyOrders.POP_CAP + ")");
		line(src, "/colony order <name|all> <job> [ore]",
			"set work: mine/chop/hunt/forage/haul/idle/come");
		line(src, "/colony prioritize <name|all> <job>", "bump a work type to the top");
		line(src, "/colony status", "list your colonists + their jobs");
		src.sendFeedback(() -> Text.literal("You can also command by name in chat: ")
			.formatted(Formatting.GRAY)
			.append(Text.literal("\"Alden mine iron\"").formatted(Formatting.YELLOW))
			.append(Text.literal(", \"colonists forage\", \"Bram prioritize chop\".")
				.formatted(Formatting.GRAY)), false);
		return 1;
	}

	private static void line(ServerCommandSource src, String cmd, String desc) {
		src.sendFeedback(() -> Text.literal("  " + cmd).formatted(Formatting.YELLOW)
			.append(Text.literal(" - " + desc).formatted(Formatting.GRAY)), false);
	}
}
