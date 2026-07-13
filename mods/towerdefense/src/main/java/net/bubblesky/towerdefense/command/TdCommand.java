package net.bubblesky.towerdefense.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity;
import net.bubblesky.towerdefense.entity.TdAllyEntity;
import net.bubblesky.towerdefense.game.TdMarkers;
import net.bubblesky.towerdefense.game.WaveManager;
import net.bubblesky.towerdefense.item.TowerBlockItem;
import net.bubblesky.towerdefense.layout.LayoutStore;
import net.bubblesky.towerdefense.registry.ModBlocks;
import net.bubblesky.towerdefense.registry.ModEntities;
import net.bubblesky.towerdefense.registry.ModItems;
import net.bubblesky.towerdefense.state.TdArenaState;
import net.bubblesky.towerdefense.tower.TowerKind;
import net.bubblesky.towerdefense.tower.TowerStructure;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

/**
 * The {@code /td} command family: the human-facing control panel for setting up
 * and running a tower-defense match, plus the coin-economy storefront
 * ({@code shop}/{@code buy}/{@code upgrade}). Arena state lives in
 * {@link TdArenaState}; the {@link WaveManager} tick loop drives the match.
 */
public final class TdCommand {
	private TdCommand() {
	}

	/** Default block distance from the base to the auto-placed spawn gates. */
	private static final int DEFAULT_ARENA_DISTANCE = 20;

	/** A buyable tower: its block plus the coin price to build it. */
	private record TowerDef(Block block, int price) {
	}

	/** The storefront catalogue (insertion order = display order). */
	private static final Map<String, TowerDef> TOWERS = new LinkedHashMap<>();

	static {
		// Prices are 5x the original values. The ball tower is intentionally OMITTED from
		// the shop (its block/entity stay registered but dormant — no longer purchasable).
		TOWERS.put("arrow_tower", new TowerDef(ModBlocks.ARROW_TOWER, 50));
		TOWERS.put("cannon_tower", new TowerDef(ModBlocks.CANNON_TOWER, 125));
		TOWERS.put("frost_tower", new TowerDef(ModBlocks.FROST_TOWER, 100));
		TOWERS.put("lightning_tower", new TowerDef(ModBlocks.LIGHTNING_TOWER, 200));
		TOWERS.put("flame_tower", new TowerDef(ModBlocks.FLAME_TOWER, 150));
	}

	/** Public, immutable view of a buyable tower (id + coin price) for client UIs. */
	public record ShopEntry(String id, int price) {
	}

	// ---- hireable allies ---------------------------------------------------
	/** Hard cap on how many allies may be alive in the arena at once. */
	private static final int MAX_ALLIES = 20;

	/** A hireable ally: its entity type plus the coin price to summon it. */
	private record AllyDef(EntityType<? extends TdAllyEntity> type, int price) {
	}

	/** The ally storefront (insertion order = display order). */
	private static final Map<String, AllyDef> ALLIES = new LinkedHashMap<>();

	static {
		ALLIES.put("footman", new AllyDef(ModEntities.ALLY_FOOTMAN, 15));
		ALLIES.put("archer", new AllyDef(ModEntities.ALLY_ARCHER, 20));
		ALLIES.put("knight", new AllyDef(ModEntities.ALLY_KNIGHT, 40));
	}

	/** Public, immutable view of a hireable ally (id + coin price) for client UIs. */
	public record HireEntry(String id, int price) {
	}

	/**
	 * The ally storefront as an ordered list — the single source of truth shared with
	 * the client menu so its Hire section always matches {@code /td hire}.
	 */
	public static java.util.List<HireEntry> hireCatalogue() {
		java.util.List<HireEntry> list = new java.util.ArrayList<>();
		for (Map.Entry<String, AllyDef> e : ALLIES.entrySet()) {
			list.add(new HireEntry(e.getKey(), e.getValue().price()));
		}
		return list;
	}

	/**
	 * The storefront catalogue as an ordered list — the single source of truth
	 * shared with the client Tower Defense menu so its shop always reflects the
	 * real tower types and prices used by {@code /td buy}.
	 */
	public static java.util.List<ShopEntry> catalogue() {
		java.util.List<ShopEntry> list = new java.util.ArrayList<>();
		for (Map.Entry<String, TowerDef> e : TOWERS.entrySet()) {
			list.add(new ShopEntry(e.getKey(), e.getValue().price()));
		}
		return list;
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("td")
			// Open to everyone: the game runs on the coin economy, not op. Survival,
			// non-op players can set up and play a match entirely with /td.
			.requires(src -> true)
			.executes(TdCommand::help)
			.then(CommandManager.literal("tower")
				// Free instant-build is the one genuinely-admin cheat — keep it op-gated.
				.requires(src -> src.hasPermissionLevel(2))
				.executes(ctx -> tower(ctx, "arrow_tower"))
				.then(CommandManager.argument("type", StringArgumentType.word())
					.executes(ctx -> tower(ctx, StringArgumentType.getString(ctx, "type")))))
			.then(CommandManager.literal("shop").executes(TdCommand::shop))
			.then(CommandManager.literal("buy")
				.then(CommandManager.argument("type", StringArgumentType.word())
					.executes(ctx -> buy(ctx, StringArgumentType.getString(ctx, "type"), 1))
					.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 64))
						.executes(ctx -> buy(ctx, StringArgumentType.getString(ctx, "type"),
							IntegerArgumentType.getInteger(ctx, "count"))))))
			.then(CommandManager.literal("upgrade").executes(TdCommand::upgrade))
			.then(CommandManager.literal("hire")
				.then(CommandManager.argument("type", StringArgumentType.word())
					.executes(ctx -> hire(ctx, StringArgumentType.getString(ctx, "type")))))
			.then(CommandManager.literal("command")
				.then(CommandManager.argument("order", StringArgumentType.word())
					.executes(ctx -> command(ctx, StringArgumentType.getString(ctx, "order"), null))
					.then(CommandManager.argument("flag", StringArgumentType.word())
						.executes(ctx -> command(ctx, StringArgumentType.getString(ctx, "order"),
							StringArgumentType.getString(ctx, "flag"))))))
			.then(CommandManager.literal("spawn").executes(TdCommand::spawn))
			.then(CommandManager.literal("idol")
				.executes(ctx -> base(ctx, TdArenaState.DEFAULT_BASE_HP))
				.then(CommandManager.argument("hp", IntegerArgumentType.integer(1))
					.executes(ctx -> base(ctx, IntegerArgumentType.getInteger(ctx, "hp")))))
			// Legacy alias: /td base still works, but /td idol is the documented name.
			.then(CommandManager.literal("base")
				.executes(ctx -> base(ctx, TdArenaState.DEFAULT_BASE_HP))
				.then(CommandManager.argument("hp", IntegerArgumentType.integer(1))
					.executes(ctx -> base(ctx, IntegerArgumentType.getInteger(ctx, "hp")))))
			.then(CommandManager.literal("wave").executes(TdCommand::wave))
			.then(CommandManager.literal("start").executes(TdCommand::wave))
			.then(CommandManager.literal("arena")
				.executes(ctx -> arena(ctx, DEFAULT_ARENA_DISTANCE))
				.then(CommandManager.argument("distance", IntegerArgumentType.integer(4, 128))
					.executes(ctx -> arena(ctx, IntegerArgumentType.getInteger(ctx, "distance")))))
			.then(CommandManager.literal("restart").executes(TdCommand::restart))
			.then(CommandManager.literal("status").executes(TdCommand::status))
			.then(CommandManager.literal("reset").executes(TdCommand::reset))
			.then(CommandManager.literal("help").executes(TdCommand::help)));
	}

	/**
	 * The in-game manual: what the game is, how to start, every command, the tower
	 * roster + prices, the coin economy, the enemy/boss roster, and the HUD. Printed
	 * by {@code /td help} and by a bare {@code /td} with no subcommand.
	 */
	private static int help(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();

		src.sendFeedback(() -> Text.literal("========= Tower Defense =========")
			.formatted(Formatting.GOLD, Formatting.BOLD), false);

		header(src, "What it is");
		body(src, "A tower-defense game. You raise an Idol to defend, then waves of");
		body(src, "enemies march toward it. Build towers to stop them and survive as");
		body(src, "long as you can — you lose when the Idol's HP hits 0.");

		header(src, "Quick start (pick your spots)");
		src.sendFeedback(() -> Text.literal("  1) ").formatted(Formatting.GRAY)
			.append(Text.literal("/td idol").formatted(Formatting.YELLOW))
			.append(Text.literal(" — stand where you want the Idol; raises it there.")
				.formatted(Formatting.GRAY)), false);
		src.sendFeedback(() -> Text.literal("  2) ").formatted(Formatting.GRAY)
			.append(Text.literal("/td spawn").formatted(Formatting.YELLOW))
			.append(Text.literal(" — stand where enemies should pour in; repeat for more gates.")
				.formatted(Formatting.GRAY)), false);
		src.sendFeedback(() -> Text.literal("  3) ").formatted(Formatting.GRAY)
			.append(Text.literal("/td wave").formatted(Formatting.YELLOW))
			.append(Text.literal(" — begin the assault!").formatted(Formatting.GRAY)), false);
		body(src, "In a hurry? /td arena auto-places the Idol + 2 spawn gates for you.");

		header(src, "Commands");
		line(src, "/td idol [hp]", "raise the Idol to defend at your position (default 100 HP)");
		line(src, "/td spawn", "add an enemy spawn point at your position (repeatable)");
		line(src, "/td arena [dist]", "quick-setup: Idol here + 2 spawn gates (N/E) at dist blocks");
		line(src, "/td wave", "start the next wave (alias: /td start)");
		line(src, "/td tower [type]", "instantly build a tower where you're looking (free/op)");
		line(src, "/td shop", "list buyable towers and their coin prices");
		line(src, "/td buy <type>", "spend coins for a placeable tower block — place it to raise the tower");
		line(src, "/td upgrade", "spend coins to raise the tier of the tower you're aiming at");
		line(src, "/td hire <type>", "spend coins to summon an allied soldier (footman/archer/knight)");
		line(src, "/td command <order> [flag]", "order your allies: hold/attack/follow/move (flag = anchor)");
		line(src, "/td status", "show wave/Idol info (and the tower you're looking at)");
		line(src, "/td restart", "reset then re-arena here for a fresh run (start with /td wave)");
		line(src, "/td reset", "clear the arena (waves, enemies, spawns, Idol + markers)");
		line(src, "/td help", "show this guide");

		header(src, "Towers");
		int arrow = TOWERS.get("arrow_tower").price();
		int cannon = TOWERS.get("cannon_tower").price();
		int frost = TOWERS.get("frost_tower").price();
		int lightning = TOWERS.get("lightning_tower").price();
		int flame = TOWERS.get("flame_tower").price();
		line(src, "arrow_tower", arrow + " coins — fast, single-target, cheap");
		line(src, "cannon_tower", cannon + " coins — slow, splash/AoE damage");
		line(src, "frost_tower", frost + " coins — slows enemies down");
		line(src, "lightning_tower", lightning + " coins — powerful bolt that chains between enemies");
		line(src, "flame_tower", flame + " coins — fast flamethrower; torches crowds + burning ground");
		body(src, "Buy → get a placeable tower block → place it to raise the tower.");
		body(src, "The flame tower is a short-range incinerator; the others reach further out.");
		body(src, "Prefer shooting? A bought tower arrow fired from your bow still builds too.");
		body(src, "Aim at a built tower and /td upgrade to raise its tier for coins.");

		header(src, "Your bow");
		body(src, "Normal fire = combat arrow.  Fire with a tower arrow loaded = build a tower.");
		body(src, "SNEAK + fire = plant a Layout flag where the arrow lands (for ally orders).");

		header(src, "Allied soldiers");
		int aFoot = ALLIES.get("footman").price();
		int aArch = ALLIES.get("archer").price();
		int aKnight = ALLIES.get("knight").price();
		line(src, "footman", aFoot + " coins — melee ally that charges the enemy");
		line(src, "archer", aArch + " coins — ranged ally that shoots from afar");
		line(src, "knight", aKnight + " coins — heavy melee ally, tanky front line");
		body(src, "Hire with /td hire <type> (cap " + MAX_ALLIES + "). Orders via /td command:");
		body(src, "hold = defend a spot, attack = advance on the wave, follow = trail you,");
		body(src, "move [flag] = march to a flag/where you look, then hold there.");

		header(src, "Economy");
		body(src, "Kill enemies to drop coins; walk over them to pick them up. Spend");
		body(src, "coins with /td buy and /td upgrade. Tower kills also pay whoever");
		body(src, "placed (or last upgraded) that tower.");

		header(src, "Enemies & bosses");
		body(src, "An escalating army — goblins, footmen, archers, knights, undead —");
		body(src, "that grows bigger and tougher each wave. Every 5th wave a Warlord");
		body(src, "boss marches on the Idol; slaying it pays bonus coins.");

		header(src, "HUD");
		body(src, "Bossbar: current wave + Idol HP.  Sidebar: your coins.");
		body(src, "You lose when Idol HP reaches 0 — then /td restart to play again.");

		src.sendFeedback(() -> Text.literal("=================================")
			.formatted(Formatting.GOLD), false);
		return 1;
	}

	private static void header(ServerCommandSource src, String title) {
		src.sendFeedback(() -> Text.literal("» " + title).formatted(Formatting.AQUA, Formatting.BOLD), false);
	}

	private static void body(ServerCommandSource src, String text) {
		src.sendFeedback(() -> Text.literal("  " + text).formatted(Formatting.GRAY), false);
	}

	private static void line(ServerCommandSource src, String cmd, String desc) {
		src.sendFeedback(() -> Text.literal("  " + cmd).formatted(Formatting.YELLOW)
			.append(Text.literal(" - " + desc).formatted(Formatting.GRAY)), false);
	}

	// ---- shop / economy ----------------------------------------------------
	private static int shop(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity player = src.getPlayerOrThrow();
		int coins = countCoins(player);
		src.sendFeedback(() -> Text.literal("Tower Shop").formatted(Formatting.GOLD), false);
		for (Map.Entry<String, TowerDef> e : TOWERS.entrySet()) {
			int price = e.getValue().price();
			line(src, e.getKey(), price + " coins  (/td buy " + e.getKey() + ")");
		}
		src.sendFeedback(() -> Text.literal("  Your coins: " + coins).formatted(Formatting.AQUA), false);
		src.sendFeedback(() -> Text.literal("  Buy gives a placeable tower block — place it to raise the tower.")
			.formatted(Formatting.GRAY), false);
		src.sendFeedback(() -> Text.literal("  Upgrades cost (tower price x current tier); use /td upgrade.")
			.formatted(Formatting.GRAY), false);
		return 1;
	}

	/**
	 * Buy a tower: charge coins and hand the player ONE placeable tower BLOCK of that
	 * type. Placing the block raises the whole tower — the tall stick-structure for
	 * ARROW/CANNON/FROST, or the single sticky turret for BALL (see {@link TowerBlockItem}).
	 * The shoot-to-place tower arrows still exist for players who prefer them, but the
	 * shop now sells the simpler place-a-block path.
	 */
	private static int buy(CommandContext<ServerCommandSource> ctx, String type, int count) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity player = src.getPlayerOrThrow();

		TowerDef def = TOWERS.get(type);
		TowerKind kind = TowerKind.fromId(type);
		if (def == null || kind == null) {
			src.sendError(Text.literal("Unknown tower '" + type + "'. Try /td shop."));
			return 0;
		}
		int total = def.price() * count;
		int coins = countCoins(player);
		if (coins < total) {
			src.sendError(Text.literal("Not enough coins: need " + total + " for " + count + "x "
				+ type + ", have " + coins + "."));
			return 0;
		}
		// Hand out the placeable tower blocks as a stack (drops any that don't fit in the pack).
		ItemStack blocks = new ItemStack(def.block(), count);
		if (!player.getInventory().insertStack(blocks)) {
			player.dropItem(blocks, false);
		}
		removeCoins(player, total);
		int remaining = coins - total;
		String howTo = kind == TowerKind.BALL
			? " Place them on any block face (walls too) to mount the turrets."
			: " Place them on the ground to raise the towers.";
		src.sendFeedback(() -> Text.literal("Bought " + count + "x " + type + " for " + total
			+ " coins (" + remaining + " left)." + howTo)
			.formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int upgrade(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity player = src.getPlayerOrThrow();
		ServerWorld world = src.getWorld();

		AbstractTowerBlockEntity tower = lookedAtTower(player, world);
		if (tower == null) {
			src.sendError(Text.literal("Look at one of your towers within 30 blocks to upgrade it."));
			return 0;
		}
		if (tower.getTier() >= AbstractTowerBlockEntity.MAX_TIER) {
			src.sendError(Text.literal("That tower is already at max tier ("
				+ AbstractTowerBlockEntity.MAX_TIER + ")."));
			return 0;
		}
		int basePrice = priceOf(world, tower.getPos());
		int cost = basePrice * tower.getTier();
		int coins = countCoins(player);
		if (coins < cost) {
			src.sendError(Text.literal("Not enough coins to upgrade: need " + cost + ", have " + coins + "."));
			return 0;
		}
		int newTier = tower.getTier() + 1;
		tower.upgrade();
		// The upgrader (re)claims ownership so their upgraded shots keep paying them.
		tower.setPlacer(player.getUuid());
		removeCoins(player, cost);
		int remaining = coins - cost;
		src.sendFeedback(() -> Text.literal("Upgraded to tier " + newTier + " for " + cost
			+ " coins (" + remaining + " left).").formatted(Formatting.GREEN), false);
		return 1;
	}

	// ---- hireable allies ---------------------------------------------------
	/**
	 * Hire a friendly unit for coins: {@code /td hire <footman|archer|knight>}. Deducts
	 * the ally's price from the player's coins (same economy as {@code /td buy}), caps
	 * the squad at {@link #MAX_ALLIES}, and spawns the unit at the player, set to
	 * FOLLOW its owner until given another order.
	 */
	private static int hire(CommandContext<ServerCommandSource> ctx, String type) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity player = src.getPlayerOrThrow();
		ServerWorld world = src.getWorld();

		AllyDef def = ALLIES.get(type);
		if (def == null) {
			src.sendError(Text.literal("Unknown ally '" + type + "'. Try footman, archer or knight."));
			return 0;
		}
		int existing = countAllies(world);
		if (existing >= MAX_ALLIES) {
			src.sendError(Text.literal("Ally cap reached (" + MAX_ALLIES + "). Dismiss some first (/td reset)."));
			return 0;
		}
		int coins = countCoins(player);
		if (coins < def.price()) {
			src.sendError(Text.literal("Not enough coins: need " + def.price() + ", have " + coins + "."));
			return 0;
		}

		BlockPos spawnPos = player.getBlockPos();
		TdAllyEntity ally = def.type().spawn(world, spawnPos, SpawnReason.EVENT);
		if (ally == null) {
			src.sendError(Text.literal("Couldn't spawn the ally here — try open ground."));
			return 0;
		}
		ally.addCommandTag(TdAllyEntity.ALLY_TAG);
		ally.setPersistent();
		// Default order: trail the player who hired it.
		ally.setOrder(TdAllyEntity.Order.FOLLOW, null, player.getUuid());

		removeCoins(player, def.price());
		int remaining = coins - def.price();
		src.sendFeedback(() -> Text.literal("Hired " + type + " for " + def.price()
			+ " coins (" + remaining + " left). It will FOLLOW you — use /td command <hold|attack|follow|move>.")
			.formatted(Formatting.GREEN), false);
		return 1;
	}

	/**
	 * Order the player's allies: {@code /td command <hold|attack|follow|move> [flag]}.
	 * HOLD/MOVE anchor on the named Layout flag if given, else the player's position
	 * (MOVE uses the block the player is looking at when no flag is supplied). FOLLOW
	 * re-binds the squad to the commanding player. Affects every ally this player owns.
	 */
	private static int command(CommandContext<ServerCommandSource> ctx, String orderName, String flagName)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity player = src.getPlayerOrThrow();
		ServerWorld world = src.getWorld();

		TdAllyEntity.Order order;
		try {
			order = TdAllyEntity.Order.valueOf(orderName.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			src.sendError(Text.literal("Unknown order '" + orderName + "'. Use hold, attack, follow or move."));
			return 0;
		}

		// Resolve an anchor for HOLD / MOVE (flag > looked-at block > player position).
		Vec3d anchor = null;
		String anchorDesc = "";
		if (order == TdAllyEntity.Order.HOLD || order == TdAllyEntity.Order.MOVE) {
			if (flagName != null) {
				LayoutStore.Flag flag = LayoutStore.getFlag(flagName);
				if (flag == null) {
					src.sendError(Text.literal("No flag named '" + flagName + "'. Plant one by sneak-firing your bow (or the wand)."));
					return 0;
				}
				anchor = new Vec3d(flag.x() + 0.5, flag.y(), flag.z() + 0.5);
				anchorDesc = " at flag " + flag.name();
			} else if (order == TdAllyEntity.Order.MOVE) {
				HitResult hit = player.raycast(48.0, 1.0f, false);
				BlockPos p = hit.getType() == HitResult.Type.BLOCK
					? ((BlockHitResult) hit).getBlockPos() : player.getBlockPos();
				anchor = new Vec3d(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
				anchorDesc = " to " + p.toShortString();
			} else {
				anchor = new Vec3d(player.getX(), player.getY(), player.getZ());
				anchorDesc = " here";
			}
		}

		List<TdAllyEntity> squad = alliesOf(world, player.getUuid());
		if (squad.isEmpty()) {
			src.sendError(Text.literal("You have no allies. Hire some with /td hire <type>."));
			return 0;
		}
		for (TdAllyEntity ally : squad) {
			ally.setOrder(order, anchor, player.getUuid());
		}
		final String desc = anchorDesc;
		final int n = squad.size();
		src.sendFeedback(() -> Text.literal(n + " all" + (n == 1 ? "y" : "ies") + " ordered to "
			+ order.name() + desc + ".").formatted(Formatting.GREEN), false);
		return 1;
	}

	/** All live allies in the arena (or near the player) owned by {@code owner}. */
	private static List<TdAllyEntity> alliesOf(ServerWorld world, java.util.UUID owner) {
		Box box = allyScanBox(world);
		return world.getEntitiesByClass(TdAllyEntity.class, box,
			a -> a.isAlive() && owner.equals(a.getOwner()));
	}

	/** Count every live ally in the arena (for the hire cap). */
	private static int countAllies(ServerWorld world) {
		return world.getEntitiesByClass(TdAllyEntity.class, allyScanBox(world),
			a -> a.isAlive()).size();
	}

	/** A broad box covering the arena (around the base) or the whole loaded area. */
	private static Box allyScanBox(ServerWorld world) {
		TdArenaState st = TdArenaState.get(world.getServer());
		if (st.base != null) {
			return new Box(st.base).expand(160.0);
		}
		// No base yet: scan a large box around the world spawn as a fallback.
		BlockPos s = world.getSpawnPos();
		return new Box(s).expand(160.0);
	}

	// ---- op / free placement -----------------------------------------------
	private static int tower(CommandContext<ServerCommandSource> ctx, String type) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity player = src.getPlayerOrThrow();
		ServerWorld world = src.getWorld();

		TowerKind kind = TowerKind.fromId(type);
		if (kind == null) {
			src.sendError(Text.literal("Unknown tower type '" + type + "'. Placing arrow_tower."));
			kind = TowerKind.ARROW;
			type = "arrow_tower";
		}
		BlockPos placePos = placementTarget(src, player, world);
		if (placePos == null) {
			return 0;
		}
		// Free-build the full stick-tower structure (op cheat, no coins).
		TowerStructure.build(world, placePos, kind, player.getUuid());
		final String placed = type;
		src.sendFeedback(() -> Text.literal("Placed " + placed + " at " + placePos.toShortString())
			.formatted(Formatting.GREEN), false);
		return 1;
	}

	// ---- placement / raycast helpers ---------------------------------------
	private static BlockPos placementTarget(ServerCommandSource src, ServerPlayerEntity player, ServerWorld world) {
		HitResult hit = player.raycast(30.0, 1.0f, false);
		if (hit.getType() != HitResult.Type.BLOCK) {
			src.sendError(Text.literal("Look at a block within 30 blocks to place a tower."));
			return null;
		}
		BlockHitResult bhr = (BlockHitResult) hit;
		BlockPos placePos = bhr.getBlockPos().offset(bhr.getSide());
		if (!world.getBlockState(placePos).isReplaceable()) {
			src.sendError(Text.literal("No room to place a tower there."));
			return null;
		}
		return placePos;
	}

	/**
	 * The tower the player is aiming at (within 30 blocks), or null. Because a tower's
	 * working core sits INSIDE its decorative ball, the raycast usually lands on a shell
	 * block, so we scan a small radius around the hit for the nearest tower core.
	 */
	private static AbstractTowerBlockEntity lookedAtTower(ServerPlayerEntity player, ServerWorld world) {
		HitResult hit = player.raycast(30.0, 1.0f, false);
		if (hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		BlockPos hitPos = ((BlockHitResult) hit).getBlockPos();
		AbstractTowerBlockEntity best = null;
		int bestSq = Integer.MAX_VALUE;
		int r = 2; // ball radius (2) covers reaching the core from any shell face
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					BlockPos p = hitPos.add(dx, dy, dz);
					if (world.getBlockEntity(p) instanceof AbstractTowerBlockEntity tower) {
						int sq = dx * dx + dy * dy + dz * dz;
						if (sq < bestSq) {
							bestSq = sq;
							best = tower;
						}
					}
				}
			}
		}
		return best;
	}

	/** Look up the catalogue price of the tower block at a position (default 10). */
	private static int priceOf(ServerWorld world, BlockPos pos) {
		Block block = world.getBlockState(pos).getBlock();
		for (TowerDef def : TOWERS.values()) {
			if (def.block() == block) {
				return def.price();
			}
		}
		return 10;
	}

	/** Public catalogue price of the tower block at a position (default 10). */
	public static int priceOfPublic(ServerWorld world, BlockPos pos) {
		return priceOf(world, pos);
	}

	// ---- coin helpers ------------------------------------------------------
	private static int countCoins(ServerPlayerEntity player) {
		int total = 0;
		for (ItemStack stack : player.getInventory().getMainStacks()) {
			if (stack.isOf(ModItems.COIN)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	public static int countCoinsPublic(ServerPlayerEntity player) {
		return countCoins(player);
	}

	private static void removeCoins(ServerPlayerEntity player, int amount) {
		int remaining = amount;
		var stacks = player.getInventory().getMainStacks();
		for (int i = 0; i < stacks.size() && remaining > 0; i++) {
			ItemStack stack = stacks.get(i);
			if (stack.isOf(ModItems.COIN)) {
				int take = Math.min(remaining, stack.getCount());
				stack.decrement(take);
				remaining -= take;
			}
		}
	}

	public static void removeCoinsPublic(ServerPlayerEntity player, int amount) {
		removeCoins(player, amount);
	}

	// ---- arena setup / status ----------------------------------------------
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
		TdMarkers.placeSpawn(src.getWorld(), pos, st.spawnPoints.size());
		src.sendFeedback(() -> Text.literal("Enemy spawn #" + st.spawnPoints.size()
			+ " added at " + pos.toShortString() + ". "
			+ (st.base == null ? "Now set the Idol with /td idol." : "Run /td wave to begin!"))
			.formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int base(CommandContext<ServerCommandSource> ctx, int hp) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity player = src.getPlayerOrThrow();
		TdArenaState st = TdArenaState.get(src.getServer());
		// Clear a previous Idol marker before moving it.
		if (st.base != null) {
			TdMarkers.clearIdol(src.getWorld(), st.base);
		}
		st.base = player.getBlockPos();
		st.baseMaxHp = hp;
		st.baseHp = hp;
		st.worldId = src.getWorld().getRegistryKey().getValue().toString();
		st.gameOver = false;
		st.markDirty();
		TdMarkers.placeIdol(src.getWorld(), st.base);
		src.sendFeedback(() -> Text.literal("Idol raised at " + st.base.toShortString()
			+ " with " + hp + " HP. "
			+ (st.spawnPoints.isEmpty() ? "Now add a spawn with /td spawn." : "Run /td wave to begin!"))
			.formatted(Formatting.GREEN), false);
		return 1;
	}

	/**
	 * One-command arena setup: place the base at the player's feet (default HP) and
	 * drop two spawn gates {@code distance} blocks away (north and east), so a match
	 * is ready to run with just {@code /td arena} then {@code /td wave}. Replaces any
	 * previous spawn points so it is safely re-runnable.
	 */
	private static int arena(CommandContext<ServerCommandSource> ctx, int distance) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity player = src.getPlayerOrThrow();
		TdArenaState st = TdArenaState.get(src.getServer());
		ServerWorld world = src.getWorld();
		BlockPos here = player.getBlockPos();

		// Clear any existing markers before laying out a fresh arena here.
		TdMarkers.clearAll(world, st);

		st.base = here;
		st.baseMaxHp = TdArenaState.DEFAULT_BASE_HP;
		st.baseHp = TdArenaState.DEFAULT_BASE_HP;
		st.worldId = world.getRegistryKey().getValue().toString();
		st.gameOver = false;
		st.spawnPoints.clear();
		// Place gates at the terrain SURFACE at each (x,z), not the idol's flat Y, so on
		// hilly/natural terrain the gates aren't buried in a hill (which would make enemy
		// spawns fail). The WaveManager also re-snaps at spawn time as a safety net.
		st.spawnPoints.add(surfaceGate(world, here.north(distance)));
		st.spawnPoints.add(surfaceGate(world, here.east(distance)));
		st.markDirty();

		// Visible markers: the Idol pillar + a gate at each auto-placed spawn.
		TdMarkers.placeIdol(world, st.base);
		for (int i = 0; i < st.spawnPoints.size(); i++) {
			TdMarkers.placeSpawn(world, st.spawnPoints.get(i), i + 1);
		}

		src.sendFeedback(() -> Text.literal("Arena ready: Idol at " + here.toShortString()
			+ " (" + st.baseMaxHp + " HP) with 2 spawn gates " + distance
			+ " blocks N/E. Run /td wave to begin.").formatted(Formatting.GREEN), true);
		return 1;
	}

	/**
	 * Reset the arena and immediately re-set it up at the player's position — a
	 * one-command "play again" after a game over (or any time). Equivalent to
	 * {@code /td reset} followed by {@code /td arena}.
	 */
	private static int restart(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		reset(ctx);
		int result = arena(ctx, DEFAULT_ARENA_DISTANCE);
		ctx.getSource().sendFeedback(() -> Text.literal("Arena restarted — good luck! (/td wave to start)")
			.formatted(Formatting.GOLD), true);
		return result;
	}

	/** Snap a gate position to the terrain surface at its (x,z) so it isn't buried. */
	private static BlockPos surfaceGate(ServerWorld world, BlockPos pos) {
		int surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, pos.getX(), pos.getZ());
		return new BlockPos(pos.getX(), surfaceY, pos.getZ());
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
		src.sendFeedback(() -> Text.literal("  Idol HP: "
			+ (st.base == null ? "no idol" : st.baseHp + "/" + st.baseMaxHp))
			.formatted(Formatting.YELLOW), false);
		src.sendFeedback(() -> Text.literal("  Enemy spawns: " + st.spawnPoints.size())
			.formatted(Formatting.YELLOW), false);
		if (!st.gameOver && st.base != null) {
			int next = st.currentWave + 1;
			if (WaveManager.isBossWave(next)) {
				src.sendFeedback(() -> Text.literal("  Next up: BOSS WAVE " + next + " (Warlord)")
					.formatted(Formatting.LIGHT_PURPLE), false);
			}
		}

		// If the player is aiming at a tower, report its tier too.
		if (src.getEntity() instanceof ServerPlayerEntity player) {
			AbstractTowerBlockEntity tower = lookedAtTower(player, src.getWorld());
			if (tower != null) {
				String name = src.getWorld().getBlockState(tower.getPos()).getBlock()
					.getName().getString();
				src.sendFeedback(() -> Text.literal("  Aiming at: " + name + " (tier "
					+ tower.getTier() + "/" + AbstractTowerBlockEntity.MAX_TIER + ")")
					.formatted(Formatting.AQUA), false);
			}
		}
		return 1;
	}

	private static int reset(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		TdArenaState st = TdArenaState.get(src.getServer());
		ServerWorld arena = st.getArenaWorld(src.getServer());
		if (st.base != null && arena != null) {
			// Clear both wave enemies AND friendly allies from the field.
			arena.getOtherEntities(null, new net.minecraft.util.math.Box(st.base).expand(128.0),
				e -> e.getCommandTags().contains(WaveManager.ENEMY_TAG)
					|| e.getCommandTags().contains(TdAllyEntity.ALLY_TAG))
				.forEach(net.minecraft.entity.Entity::discard);
		}
		// Towers are PERMANENT: a reset no longer removes them. They stay as normal
		// breakable blocks in the world (break one to get its tower block back).
		// Remove the Idol + spawn markers (blocks + labels) before wiping their positions.
		TdMarkers.clearAll(arena, st);
		// Release any force-loaded arena chunks before wiping the idol/spawn positions.
		WaveManager.releaseArenaChunks(src.getServer(), st);
		st.clear();
		src.sendFeedback(() -> Text.literal("Arena reset — Idol and spawns cleared (towers kept).").formatted(Formatting.GREEN), true);
		return 1;
	}
}
