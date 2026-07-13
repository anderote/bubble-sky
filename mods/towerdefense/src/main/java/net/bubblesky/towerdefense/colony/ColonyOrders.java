package net.bubblesky.towerdefense.colony;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.bubblesky.towerdefense.registry.ModEntities;
import net.bubblesky.towerdefense.registry.ModItems;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.jetbrains.annotations.Nullable;

/**
 * The COLONY logic hub — the single source of truth both the {@code /colony} Brigadier
 * command ({@link ColonyCommand}) and the by-name chat control ({@link ColonyChat})
 * route through, so a typed command and a chat sentence do exactly the same thing.
 *
 * <p>Owns the colony economy (recruit for gold, mirroring {@code TdCommand}'s
 * {@code countCoins}/{@code removeCoins}), the population cap, home-flag planting, the
 * roster lookups (by name / all), and the order + prioritize verbs.
 */
public final class ColonyOrders {
	private ColonyOrders() {
	}

	/** Gold cost to recruit one colonist. */
	public static final int RECRUIT_COST = 50;
	/** Maximum colonists alive at once. */
	public static final int POP_CAP = 10;
	/** Radius (blocks) around a player used to gather their colonist roster. */
	private static final double ROSTER_RADIUS = 200.0;

	// ---- flag --------------------------------------------------------------
	/** Plant the colony home flag at the player and drop a visible marker. */
	public static int flag(ServerPlayerEntity player) {
		ServerWorld world = (ServerWorld) player.getWorld();
		ColonyState state = ColonyState.get(world.getServer());
		String dim = world.getRegistryKey().getValue().toString();
		ColonyState.Flag flag = state.addFlag(player.getBlockPos(), dim);
		ColonyMarkers.placeFlag(world, flag.pos(), flag.name());
		player.sendMessage(Text.literal("Colony flag '" + flag.name() + "' planted at "
			+ flag.pos().toShortString() + " — you'll respawn here. Recruit colonists with /colony recruit.")
			.formatted(Formatting.GREEN), false);
		return 1;
	}

	// ---- recruit -----------------------------------------------------------
	/** Recruit one colonist for gold at the nearest flag (or the player if none), pop-capped. */
	public static int recruit(ServerPlayerEntity player) {
		ServerWorld world = (ServerWorld) player.getWorld();
		String dim = world.getRegistryKey().getValue().toString();
		ColonyState state = ColonyState.get(world.getServer());
		ColonyState.Flag flag = state.nearestFlag(player.getBlockPos(), dim);

		int alive = countColonists(world, player);
		if (alive >= POP_CAP) {
			player.sendMessage(Text.literal("Colony is at its population cap (" + POP_CAP + ").")
				.formatted(Formatting.RED), false);
			return 0;
		}
		int coins = countCoins(player);
		if (coins < RECRUIT_COST) {
			player.sendMessage(Text.literal("Not enough gold: need " + RECRUIT_COST
				+ ", have " + coins + ".").formatted(Formatting.RED), false);
			return 0;
		}

		BlockPos spawnPos = flag != null ? flag.pos() : player.getBlockPos();
		ColonistEntity colonist = ModEntities.COLONIST.spawn(world, spawnPos, SpawnReason.EVENT);
		if (colonist == null) {
			player.sendMessage(Text.literal("Couldn't spawn a colonist here — try open ground.")
				.formatted(Formatting.RED), false);
			return 0;
		}
		colonist.addCommandTag(ColonistEntity.COLONIST_TAG);
		colonist.setPersistent();
		colonist.setOwner(player.getUuid());
		colonist.setHome(flag != null ? flag.pos() : player.getBlockPos());
		colonist.setJob(ColonistEntity.Job.IDLE);

		removeCoins(player, RECRUIT_COST);
		int remaining = coins - RECRUIT_COST;
		String where = flag != null ? "at flag " + flag.name() : "at your position (no flag — plant one with /colony flag)";
		player.sendMessage(Text.literal("Recruited " + colonist.getColonistName() + " " + where
			+ " for " + RECRUIT_COST + " gold (" + remaining + " left). Give it work: /colony order "
			+ colonist.getColonistName() + " mine").formatted(Formatting.GREEN), false);
		return 1;
	}

	// ---- roster lookups ----------------------------------------------------
	/** Count colonists owned by {@code player} anywhere near them (for the pop cap). */
	public static int countColonists(ServerWorld world, ServerPlayerEntity player) {
		return ownedBy(world, player).size();
	}

	/** All live colonists owned by {@code player} within the roster radius. */
	public static List<ColonistEntity> ownedBy(ServerWorld world, ServerPlayerEntity player) {
		UUID owner = player.getUuid();
		Box box = new Box(player.getBlockPos()).expand(ROSTER_RADIUS);
		return world.getEntitiesByClass(ColonistEntity.class, box,
			c -> c.isAlive() && owner.equals(c.getOwner()));
	}

	/**
	 * Resolve the colonists a {@code <name|all|colonists>} selector addresses: {@code all}
	 * / {@code colonists} = every colonist the player owns; otherwise the ones whose name
	 * matches (case-insensitive). Falls back to all colonists near the player if none are
	 * owned (so a shared colony still responds).
	 */
	public static List<ColonistEntity> resolve(ServerWorld world, ServerPlayerEntity player, String selector) {
		List<ColonistEntity> owned = ownedBy(world, player);
		List<ColonistEntity> pool = owned;
		if (pool.isEmpty()) {
			Box box = new Box(player.getBlockPos()).expand(ROSTER_RADIUS);
			pool = world.getEntitiesByClass(ColonistEntity.class, box, ColonistEntity::isAlive);
		}
		String sel = selector.toLowerCase(Locale.ROOT);
		if (sel.equals("all") || sel.equals("colonists") || sel.equals("everyone")) {
			return pool;
		}
		List<ColonistEntity> matches = new ArrayList<>();
		for (ColonistEntity c : pool) {
			if (c.getColonistName().equalsIgnoreCase(selector)) {
				matches.add(c);
			}
		}
		return matches;
	}

	// ---- order / prioritize ------------------------------------------------
	/**
	 * Apply a verb to the resolved colonists. Recognised verbs: mine/chop/hunt/forage/haul
	 * (set that gather job), idle/stop (stand down), come (re-home on the caller), and
	 * prioritize (bump a work type to the top). {@code target} is the optional ore keyword
	 * for {@code mine} or the work type for {@code prioritize}. Returns a feedback string.
	 */
	@Nullable
	public static String apply(ServerPlayerEntity player, List<ColonistEntity> colonists,
			String verb, @Nullable String target) {
		if (colonists.isEmpty()) {
			return null; // caller decides whether to complain
		}
		String v = verb.toLowerCase(Locale.ROOT);
		ServerWorld world = (ServerWorld) player.getWorld();

		switch (v) {
			case "prioritize", "priority", "prefer" -> {
				ColonistEntity.Job work = parseJob(target);
				if (work == null) {
					return "Prioritize what? Use mine, chop, hunt, forage or haul.";
				}
				for (ColonistEntity c : colonists) {
					c.prioritize(work);
				}
				return colonists.size() + " colonist" + plural(colonists) + " now prioritize "
					+ work.name().toLowerCase(Locale.ROOT) + ".";
			}
			case "come", "follow", "here" -> {
				BlockPos here = player.getBlockPos();
				for (ColonistEntity c : colonists) {
					c.setHome(here);
					c.setJob(ColonistEntity.Job.IDLE);
				}
				return colonists.size() + " colonist" + plural(colonists) + " coming to you.";
			}
			case "stop", "idle", "rest" -> {
				for (ColonistEntity c : colonists) {
					c.setJob(ColonistEntity.Job.IDLE);
				}
				return colonists.size() + " colonist" + plural(colonists) + " standing down.";
			}
			case "mine", "chop", "hunt", "forage", "haul" -> {
				ColonistEntity.Job job = parseJob(v);
				BlockPos lookedAtChest = lookedAtChest(player, world);
				for (ColonistEntity c : colonists) {
					if (job == ColonistEntity.Job.MINE) {
						c.setOreFilter(target); // null clears back to the default valuable set
					}
					c.setPreferredChest(lookedAtChest);
					c.setJob(job);
				}
				String extra = (job == ColonistEntity.Job.MINE && target != null) ? " " + target : "";
				return colonists.size() + " colonist" + plural(colonists) + " set to "
					+ job.name().toLowerCase(Locale.ROOT) + extra + ".";
			}
			default -> {
				return null; // not a colony verb
			}
		}
	}

	/** Multi-line roster status: each colonist, its job, home flag and carry count. */
	public static void status(ServerPlayerEntity player) {
		ServerWorld world = (ServerWorld) player.getWorld();
		List<ColonistEntity> roster = ownedBy(world, player);
		player.sendMessage(Text.literal("Colony roster (" + roster.size() + "/" + POP_CAP + ")")
			.formatted(Formatting.GOLD), false);
		if (roster.isEmpty()) {
			player.sendMessage(Text.literal("  No colonists yet — /colony recruit (" + RECRUIT_COST
				+ " gold).").formatted(Formatting.GRAY), false);
			return;
		}
		for (ColonistEntity c : roster) {
			int carried = 0;
			for (int i = 0; i < c.getInventory().size(); i++) {
				carried += c.getInventory().getStack(i).getCount();
			}
			String home = c.getHome() != null ? c.getHome().toShortString() : "none";
			String prio = c.getPriorities().stream()
				.map(j -> j.name().toLowerCase(Locale.ROOT))
				.reduce((a, b) -> a + ">" + b).orElse("");
			player.sendMessage(Text.literal("  " + c.getColonistName() + ": "
				+ c.getJob().name().toLowerCase(Locale.ROOT) + "  carry=" + carried
				+ "  home=" + home + "  [" + prio + "]").formatted(Formatting.YELLOW), false);
		}
	}

	// ---- helpers -----------------------------------------------------------
	@Nullable
	private static ColonistEntity.Job parseJob(@Nullable String name) {
		if (name == null) {
			return null;
		}
		try {
			return ColonistEntity.Job.valueOf(name.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String plural(List<?> list) {
		return list.size() == 1 ? "" : "s";
	}

	/** The chest/barrel the player is aiming at within 6 blocks, or null. */
	@Nullable
	private static BlockPos lookedAtChest(ServerPlayerEntity player, ServerWorld world) {
		HitResult hit = player.raycast(6.0, 1.0f, false);
		if (hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		BlockPos pos = ((BlockHitResult) hit).getBlockPos();
		String path = net.minecraft.registry.Registries.BLOCK
			.getId(world.getBlockState(pos).getBlock()).getPath();
		if (path.equals("chest") || path.equals("trapped_chest") || path.equals("barrel")) {
			return pos;
		}
		return null;
	}

	// ---- coin economy (mirrors TdCommand) ----------------------------------
	private static int countCoins(ServerPlayerEntity player) {
		int total = 0;
		for (ItemStack stack : player.getInventory().getMainStacks()) {
			if (stack.isOf(ModItems.COIN)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/**
	 * Colony recruit spend. Routed through {@link net.bubblesky.towerdefense.command.TdCommand#removeCoinsPublic}
	 * so it uses the shared-gold path: the recruit cost is deducted from EVERY online
	 * player, keeping co-op teammates' balances in lockstep with tower purchases.
	 */
	private static void removeCoins(ServerPlayerEntity player, int amount) {
		net.bubblesky.towerdefense.command.TdCommand.removeCoinsPublic(player, amount);
	}
}
