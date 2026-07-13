package net.bubblesky.towerdefense.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.LinkedHashMap;
import java.util.Map;
import net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity;
import net.bubblesky.towerdefense.entity.TdFriendlyEntity;
import net.bubblesky.towerdefense.game.WaveManager;
import net.bubblesky.towerdefense.registry.ModBlocks;
import net.bubblesky.towerdefense.registry.ModEntities;
import net.bubblesky.towerdefense.registry.ModItems;
import net.bubblesky.towerdefense.state.TdArenaState;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
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
import net.minecraft.util.math.Direction;
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
	private record DefenseDef(int price, String description) {}
	private record HireDef(EntityType<TdFriendlyEntity> type, int price, String displayName) {}
	private static final Map<String, DefenseDef> DEFENSES = new LinkedHashMap<>();
	private static final Map<String, HireDef> HIRES = new LinkedHashMap<>();
	private static final int BARRACKS_RANGE = 16;
	private static final int MAX_HIRED_UNITS = 12;

	static {
		TOWERS.put("arrow_tower", new TowerDef(ModBlocks.ARROW_TOWER, 10));
		TOWERS.put("cannon_tower", new TowerDef(ModBlocks.CANNON_TOWER, 25));
		TOWERS.put("frost_tower", new TowerDef(ModBlocks.FROST_TOWER, 20));
		DEFENSES.put("wall", new DefenseDef(5, "3-wide, 2-high obstacle"));
		DEFENSES.put("barracks", new DefenseDef(30, "recruits and rallies infantry"));
		HIRES.put("militia", new HireDef(ModEntities.HIRED_MILITIA, 8, "Militia"));
		HIRES.put("archer", new HireDef(ModEntities.HIRED_ARCHER, 12, "Archer"));
		HIRES.put("shield_guard", new HireDef(ModEntities.HIRED_SHIELD_GUARD, 18, "Shield Guard"));
		HIRES.put("heavy_knight", new HireDef(ModEntities.HIRED_HEAVY_KNIGHT, 28, "Heavy Knight"));
		HIRES.put("wizard", new HireDef(ModEntities.HIRED_WIZARD, 30, "Wizard"));
	}

	/** Public, immutable view of a buyable tower (id + coin price) for client UIs. */
	public record ShopEntry(String id, int price) {
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
		for (Map.Entry<String, DefenseDef> e : DEFENSES.entrySet()) {
			list.add(new ShopEntry(e.getKey(), e.getValue().price()));
		}
		return list;
	}

	/** Public infantry catalogue for the client menu. */
	public static java.util.List<ShopEntry> hireCatalogue() {
		java.util.List<ShopEntry> list = new java.util.ArrayList<>();
		for (Map.Entry<String, HireDef> e : HIRES.entrySet()) {
			list.add(new ShopEntry(e.getKey(), e.getValue().price()));
		}
		return list;
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("td")
			.requires(src -> src.hasPermissionLevel(2))
			.executes(TdCommand::help)
			.then(CommandManager.literal("tower")
				.executes(ctx -> tower(ctx, "arrow_tower"))
				.then(CommandManager.argument("type", StringArgumentType.word())
					.executes(ctx -> tower(ctx, StringArgumentType.getString(ctx, "type")))))
			.then(CommandManager.literal("shop").executes(TdCommand::shop))
			.then(CommandManager.literal("buy")
				.then(CommandManager.argument("type", StringArgumentType.word())
					.executes(ctx -> buy(ctx, StringArgumentType.getString(ctx, "type")))))
			.then(CommandManager.literal("hire")
				.then(CommandManager.argument("type", StringArgumentType.word())
					.executes(ctx -> hire(ctx, StringArgumentType.getString(ctx, "type")))))
			.then(CommandManager.literal("upgrade").executes(TdCommand::upgrade))
			.then(CommandManager.literal("spawn").executes(TdCommand::spawn))
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
		body(src, "A tower-defense game. You set up a base, then waves of enemies");
		body(src, "march toward it. Build towers to stop them and survive as long");
		body(src, "as you can — the base falls when its HP hits 0.");

		header(src, "Quick start");
		src.sendFeedback(() -> Text.literal("  1) ").formatted(Formatting.GRAY)
			.append(Text.literal("/td arena").formatted(Formatting.YELLOW))
			.append(Text.literal(" — stand where you want the base; sets base + 2 spawn gates.")
				.formatted(Formatting.GRAY)), false);
		src.sendFeedback(() -> Text.literal("  2) ").formatted(Formatting.GRAY)
			.append(Text.literal("/td wave").formatted(Formatting.YELLOW))
			.append(Text.literal(" — begin the assault!").formatted(Formatting.GRAY)), false);

		header(src, "Commands");
		line(src, "/td arena [dist]", "quick-setup: base here + 2 spawn gates (N/E) at dist blocks");
		line(src, "/td base [hp]", "set the base to defend at your position (default 100 HP)");
		line(src, "/td spawn", "add an enemy spawn point at your position");
		line(src, "/td wave", "start the next wave (alias: /td start)");
		line(src, "/td tower [type]", "place a tower where you're looking (free/op; default arrow_tower)");
		line(src, "/td shop", "list buyable towers and their coin prices");
		line(src, "/td buy <type>", "spend coins to build a tower where you're looking");
		line(src, "/td hire <type>", "hire infantry near a purchased barracks");
		line(src, "/td upgrade", "spend coins to raise the tier of the tower you're looking at");
		line(src, "/td status", "show wave/base info (and the tower you're looking at)");
		line(src, "/td restart", "reset then re-arena here for a fresh run (start with /td wave)");
		line(src, "/td reset", "clear the arena (waves, enemies, spawns, base)");
		line(src, "/td help", "show this guide");

		header(src, "Towers");
		int arrow = TOWERS.get("arrow_tower").price();
		int cannon = TOWERS.get("cannon_tower").price();
		int frost = TOWERS.get("frost_tower").price();
		line(src, "arrow_tower", arrow + " coins — fast, single-target, cheap");
		line(src, "cannon_tower", cannon + " coins — slow, splash/AoE damage");
		line(src, "frost_tower", frost + " coins — slows enemies down");
		body(src, "Aim at a tower and /td upgrade to raise its tier for coins.");
		line(src, "wall", "5 coins — a 3-wide, 2-high blocking section");
		line(src, "barracks", "30 coins — unlocks infantry hiring nearby");

		header(src, "Hired infantry");
		for (Map.Entry<String, HireDef> e : HIRES.entrySet()) {
			line(src, e.getKey(), e.getValue().price() + " coins — " + e.getValue().displayName());
		}

		header(src, "Economy");
		body(src, "Kill enemies to drop coins; walk over them to pick them up. Spend");
		body(src, "coins with /td buy and /td upgrade. Tower kills also pay whoever");
		body(src, "placed (or last upgraded) that tower.");

		header(src, "Enemies & bosses");
		body(src, "An escalating army — goblins, footmen, archers, knights, undead —");
		body(src, "that grows bigger and tougher each wave. Every 5th wave a Warlord");
		body(src, "boss marches on the base; slaying it pays bonus coins.");

		header(src, "HUD");
		body(src, "Bossbar: current wave + base HP.  Sidebar: your coins.");
		body(src, "You lose when base HP reaches 0 — then /td restart to play again.");

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
		src.sendFeedback(() -> Text.literal("Defense Shop").formatted(Formatting.GOLD), false);
		for (Map.Entry<String, TowerDef> e : TOWERS.entrySet()) {
			int price = e.getValue().price();
			line(src, e.getKey(), price + " coins  (/td buy " + e.getKey() + ")");
		}
		for (Map.Entry<String, DefenseDef> e : DEFENSES.entrySet()) {
			line(src, e.getKey(), e.getValue().price() + " coins — " + e.getValue().description()
				+ "  (/td buy " + e.getKey() + ")");
		}
		src.sendFeedback(() -> Text.literal("Infantry (requires a barracks)").formatted(Formatting.GOLD), false);
		for (Map.Entry<String, HireDef> e : HIRES.entrySet()) {
			line(src, e.getKey(), e.getValue().price() + " coins  (/td hire " + e.getKey() + ")");
		}
		src.sendFeedback(() -> Text.literal("  Your coins: " + coins).formatted(Formatting.AQUA), false);
		src.sendFeedback(() -> Text.literal("  Upgrades cost (tower price x current tier); use /td upgrade.")
			.formatted(Formatting.GRAY), false);
		return 1;
	}

	private static int buy(CommandContext<ServerCommandSource> ctx, String type) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity player = src.getPlayerOrThrow();
		ServerWorld world = src.getWorld();

		TowerDef tower = TOWERS.get(type);
		DefenseDef defense = DEFENSES.get(type);
		if (tower == null && defense == null) {
			src.sendError(Text.literal("Unknown defense '" + type + "'. Try /td shop."));
			return 0;
		}
		int price = tower != null ? tower.price() : defense.price();
		int coins = countCoins(player);
		if (coins < price) {
			src.sendError(Text.literal("Not enough coins: need " + price + ", have " + coins + "."));
			return 0;
		}
		BlockPos placePos = placementTarget(src, player, world);
		if (placePos == null) {
			return 0;
		}
		boolean placed = tower != null
			? placeTower(world, placePos, tower.block(), player)
			: placeDefense(src, player, world, placePos, type);
		if (!placed) return 0;
		removeCoins(player, price);
		int remaining = coins - price;
		src.sendFeedback(() -> Text.literal("Bought " + type + " for " + price
			+ " coins (" + remaining + " left).").formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int hire(CommandContext<ServerCommandSource> ctx, String type)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity player = src.getPlayerOrThrow();
		ServerWorld world = src.getWorld();
		HireDef def = HIRES.get(type);
		if (def == null) {
			src.sendError(Text.literal("Unknown infantry type '" + type + "'. Try /td shop."));
			return 0;
		}
		BlockPos rally = placementTarget(src, player, world);
		if (rally == null) return 0;
		if (!hasBarracksNear(world, rally)) {
			src.sendError(Text.literal("Build a barracks within " + BARRACKS_RANGE + " blocks first."));
			return 0;
		}
		long owned = world.getEntitiesByClass(TdFriendlyEntity.class, new Box(rally).expand(128),
			e -> player.getUuid().equals(e.getOwnerUuid())).size();
		if (owned >= MAX_HIRED_UNITS) {
			src.sendError(Text.literal("Infantry cap reached (" + MAX_HIRED_UNITS + ")."));
			return 0;
		}
		int coins = countCoins(player);
		if (coins < def.price()) {
			src.sendError(Text.literal("Not enough coins: need " + def.price() + ", have " + coins + "."));
			return 0;
		}
		TdFriendlyEntity unit = def.type().create(world, SpawnReason.COMMAND);
		if (unit == null) return 0;
		unit.refreshPositionAndAngles(rally.getX() + 0.5, rally.getY(), rally.getZ() + 0.5, player.getYaw(), 0);
		unit.assign(player.getUuid(), rally);
		unit.setCustomName(Text.literal("Hired " + def.displayName()));
		if (!world.spawnEntity(unit)) return 0;
		removeCoins(player, def.price());
		src.sendFeedback(() -> Text.literal("Hired " + def.displayName() + " for " + def.price()
			+ " coins. Rally: " + rally.toShortString()).formatted(Formatting.GREEN), false);
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

	// ---- op / free placement -----------------------------------------------
	private static int tower(CommandContext<ServerCommandSource> ctx, String type) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity player = src.getPlayerOrThrow();
		ServerWorld world = src.getWorld();

		TowerDef def = TOWERS.get(type);
		if (def == null) {
			src.sendError(Text.literal("Unknown tower type '" + type + "'. Placing arrow_tower."));
			def = TOWERS.get("arrow_tower");
			type = "arrow_tower";
		}
		BlockPos placePos = placementTarget(src, player, world);
		if (placePos == null) {
			return 0;
		}
		placeTower(world, placePos, def.block(), player);
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

	/** Place the tower and stamp the placer UUID so its kills credit that player. */
	private static boolean placeTower(ServerWorld world, BlockPos pos, Block block, ServerPlayerEntity placer) {
		if (!world.setBlockState(pos, block.getDefaultState())) return false;
		if (world.getBlockEntity(pos) instanceof AbstractTowerBlockEntity tower) {
			tower.setPlacer(placer.getUuid());
		}
		return true;
	}

	private static boolean placeDefense(ServerCommandSource src, ServerPlayerEntity player, ServerWorld world,
			BlockPos origin, String type) {
		if ("wall".equals(type)) {
			Direction side = player.getHorizontalFacing().rotateYClockwise();
			for (int across = -1; across <= 1; across++) for (int up = 0; up <= 1; up++) {
				if (!world.getBlockState(origin.offset(side, across).up(up)).isReplaceable()) {
					src.sendError(Text.literal("The wall footprint is obstructed."));
					return false;
				}
			}
			for (int across = -1; across <= 1; across++) for (int up = 0; up <= 1; up++)
				world.setBlockState(origin.offset(side, across).up(up), Blocks.COBBLED_DEEPSLATE.getDefaultState());
			return true;
		}
		for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = 0; y <= 2; y++) {
			if (!world.getBlockState(origin.add(x, y, z)).isReplaceable()) {
				src.sendError(Text.literal("The 3x3 barracks footprint is obstructed."));
				return false;
			}
		}
		for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
			world.setBlockState(origin.add(x, 0, z), Blocks.SPRUCE_PLANKS.getDefaultState());
		world.setBlockState(origin, ModBlocks.BARRACKS.getDefaultState());
		for (int x : new int[] {-1, 1}) for (int z : new int[] {-1, 1})
			world.setBlockState(origin.add(x, 1, z), Blocks.SPRUCE_FENCE.getDefaultState());
		for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
			world.setBlockState(origin.add(x, 2, z), Blocks.SPRUCE_SLAB.getDefaultState());
		return true;
	}

	private static boolean hasBarracksNear(ServerWorld world, BlockPos center) {
		for (BlockPos p : BlockPos.iterate(center.add(-BARRACKS_RANGE, -4, -BARRACKS_RANGE),
				center.add(BARRACKS_RANGE, 4, BARRACKS_RANGE))) {
			if (world.getBlockState(p).isOf(ModBlocks.BARRACKS)) return true;
		}
		return false;
	}

	/** The tower the player is aiming at (within 30 blocks), or null. */
	private static AbstractTowerBlockEntity lookedAtTower(ServerPlayerEntity player, ServerWorld world) {
		HitResult hit = player.raycast(30.0, 1.0f, false);
		if (hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		BlockPos pos = ((BlockHitResult) hit).getBlockPos();
		BlockEntity be = world.getBlockEntity(pos);
		return be instanceof AbstractTowerBlockEntity tower ? tower : null;
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
		BlockPos here = player.getBlockPos();

		st.base = here;
		st.baseMaxHp = TdArenaState.DEFAULT_BASE_HP;
		st.baseHp = TdArenaState.DEFAULT_BASE_HP;
		st.worldId = src.getWorld().getRegistryKey().getValue().toString();
		st.gameOver = false;
		st.spawnPoints.clear();
		// Place gates at the terrain SURFACE at each (x,z), not the base's flat Y, so on
		// hilly/natural terrain the gates aren't buried in a hill (which would make enemy
		// spawns fail). The WaveManager also re-snaps at spawn time as a safety net.
		ServerWorld world = src.getWorld();
		st.spawnPoints.add(surfaceGate(world, here.north(distance)));
		st.spawnPoints.add(surfaceGate(world, here.east(distance)));
		st.markDirty();

		src.sendFeedback(() -> Text.literal("Arena ready: base at " + here.toShortString()
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
		src.sendFeedback(() -> Text.literal("  Base HP: "
			+ (st.base == null ? "no base" : st.baseHp + "/" + st.baseMaxHp))
			.formatted(Formatting.YELLOW), false);
		src.sendFeedback(() -> Text.literal("  Spawn points: " + st.spawnPoints.size())
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
			arena.getOtherEntities(null, new net.minecraft.util.math.Box(st.base).expand(128.0),
				e -> e.getCommandTags().contains(WaveManager.ENEMY_TAG) || e instanceof TdFriendlyEntity)
				.forEach(net.minecraft.entity.Entity::discard);
		}
		// Release any force-loaded arena chunks before wiping the base/spawn positions.
		WaveManager.releaseArenaChunks(src.getServer(), st);
		st.clear();
		src.sendFeedback(() -> Text.literal("Arena reset.").formatted(Formatting.GREEN), true);
		return 1;
	}
}
