package net.bubblesky.towerdefense.colony;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * The rule-based WORK brain for a {@link ColonistEntity} — the colonist analogue of
 * the ally's {@code AllyOrderGoal}. One goal drives every job. Each decision cycle it
 * picks the highest-priority {@link ColonistEntity.Job} that has an available target
 * within {@link #HOME_RADIUS} of the colonist's home flag, then executes it:
 *
 * <ul>
 *   <li>{@code MINE} — walk to the nearest matching ore, break it over time (a
 *       break-progress timer + {@code swingHand} + crack overlay/particles, mirroring
 *       {@code WaveManager.digTowardBase}), and stash the ore's drop in the backpack.</li>
 *   <li>{@code CHOP} — same break loop, targeting logs, felling a tree one log at a time.</li>
 *   <li>{@code HUNT} — approach the nearest animal and melee it until it dies, then
 *       sweep up its drops.</li>
 *   <li>{@code FORAGE} — pick up loose item drops near home.</li>
 *   <li>{@code HAUL} — deposit the backpack into the nearest home chest, then resume
 *       the top gather job. No chest / a full chest → warn once and idle.</li>
 *   <li>{@code IDLE} — wander back toward the home flag.</li>
 * </ul>
 *
 * <p>All of this runs strictly on the server thread (the goal only executes when the
 * colonist lives in a {@link ServerWorld}): it breaks blocks, mutates a
 * {@link SimpleInventory}, and inserts into chest inventories — the three things
 * colonists do that allies never did.
 */
public class ColonyWorkGoal extends Goal {
	/** How far from its home flag a colonist will range to find work (blocks). */
	public static final double HOME_RADIUS = 28.0;

	/** Reach to a block being mined/chopped or a chest being filled (blocks, squared below). */
	private static final double REACH = 3.4;
	private static final double REACH_SQ = REACH * REACH;
	/** Reach to snap up a loose item drop. */
	private static final double PICKUP_REACH_SQ = 2.0 * 2.0;
	/** Melee reach on a hunted animal. */
	private static final double ATTACK_REACH_SQ = 2.6 * 2.6;
	/** Movement speed multiplier for all colonist travel. */
	private static final double SPEED = 1.0;

	/** Ticks of sustained mining/chopping before a block breaks. */
	private static final int BREAK_TICKS = 45;
	/** Emit crack particles + a hit sound every this-many break ticks. */
	private static final int FX_STRIDE = 6;
	/** Ticks between melee swings while hunting. */
	private static final int ATTACK_COOLDOWN = 12;
	/** Ticks of "laying" between each placed wall block, so a segment rises visibly over time. */
	private static final int BUILD_INTERVAL = 12;
	/** Reach to place a wall cell (a touch longer than mining REACH so a colonist standing at the
	 *  base can top out a full-height column without having to climb the wall it is raising). */
	private static final double BUILD_REACH_SQ = 5.5 * 5.5;
	/** Ticks between job re-evaluations (targets persist between cycles). */
	private static final int DECIDE_INTERVAL = 12;
	/** Ticks between navigation re-paths so we don't spam the navigator. */
	private static final int REPATH_INTERVAL = 10;
	/** Give up on a block target after this many ticks of failing to reach it. */
	private static final int STUCK_LIMIT = 100;
	/** Periodically forget abandoned targets so they can be retried. */
	private static final int IGNORE_CLEAR_INTERVAL = 600;

	// ---- block scan extents (kept bounded so scans stay cheap) --------------
	private static final int SCAN_XZ = 16;
	private static final int ORE_DOWN = 12;
	private static final int ORE_UP = 4;
	private static final int LOG_DOWN = 4;
	private static final int LOG_UP = 14;
	private static final int CHEST_Y = 6;

	private final ColonistEntity colonist;

	private int decisionTimer;
	private int repathTimer;
	private int stuckTimer;
	private int attackTimer;
	private int ignoreClearTimer;
	private int breakProgress;
	private int lastBreakStage = -1;
	/** Countdown between wall-block placements while executing a BUILD target. */
	private int buildTimer;

	/** The block currently being dug (ore for MINE, log for CHOP), or null. */
	@Nullable
	private BlockPos targetBlock;
	/** The chest currently being hauled to, or null. */
	@Nullable
	private BlockPos targetChest;
	/** The animal being hunted, or null. */
	@Nullable
	private AnimalEntity targetAnimal;
	/** The loose item being fetched, or null. */
	@Nullable
	private ItemEntity targetItem;

	/** Block targets temporarily abandoned as unreachable (packed positions). */
	private final Set<Long> ignored = new HashSet<>();

	public ColonyWorkGoal(ColonistEntity colonist) {
		this.colonist = colonist;
		this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		return true;
	}

	@Override
	public boolean shouldContinue() {
		return true;
	}

	@Override
	public boolean shouldRunEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		if (!(colonist.getWorld() instanceof ServerWorld world)) {
			return;
		}
		if (++ignoreClearTimer >= IGNORE_CLEAR_INTERVAL) {
			ignoreClearTimer = 0;
			ignored.clear();
		}
		// An assigned defensive-wall task OVERRIDES the rule-based brain: while a build target
		// is present we skip decide() entirely and raise the segment; stepBuild clears the
		// target (→ IDLE → decide) the moment the wall is complete, restoring normal work.
		ColonistEntity.BuildTarget build = colonist.getBuildTarget();
		if (build != null) {
			stepBuild(world, build);
			return;
		}
		if (--decisionTimer <= 0) {
			decisionTimer = DECIDE_INTERVAL;
			decide(world);
		}
		switch (colonist.getJob()) {
			case MINE, CHOP -> stepDig(world);
			case HAUL -> stepHaul(world);
			case FORAGE -> stepForage(world);
			case HUNT -> stepHunt(world);
			default -> stepIdle(world);
		}
	}

	// ---- decision ----------------------------------------------------------
	/** Choose this colonist's job for the next cycle from its priority ordering. */
	private void decide(ServerWorld world) {
		ColonistEntity.Job chosen;
		if (colonist.isInventoryFull()) {
			chosen = ColonistEntity.Job.HAUL;
		} else {
			chosen = null;
			for (ColonistEntity.Job p : colonist.getPriorities()) {
				if (p == ColonistEntity.Job.HAUL || p == ColonistEntity.Job.IDLE) {
					continue; // HAUL is fullness-driven, IDLE is the fallback
				}
				if (hasWork(world, p)) {
					chosen = p;
					break;
				}
			}
			if (chosen == null) {
				chosen = colonist.isInventoryEmpty() ? ColonistEntity.Job.IDLE : ColonistEntity.Job.HAUL;
			}
		}
		if (chosen != colonist.getJob()) {
			clearTargets(world);
			colonist.setJob(chosen);
		}
	}

	/**
	 * True if job {@code p} has an available target near home. Populates the relevant
	 * target cache as a side effect (so the executing step doesn't rescan), and reuses
	 * a still-valid cached target rather than scanning afresh every cycle.
	 */
	private boolean hasWork(ServerWorld world, ColonistEntity.Job p) {
		return switch (p) {
			case MINE -> {
				if (targetBlock != null && isOre(world.getBlockState(targetBlock))) {
					yield true;
				}
				targetBlock = findNearestBlock(world, true);
				yield targetBlock != null;
			}
			case CHOP -> {
				if (targetBlock != null && isLog(world.getBlockState(targetBlock))) {
					yield true;
				}
				targetBlock = findNearestBlock(world, false);
				yield targetBlock != null;
			}
			case FORAGE -> {
				if (targetItem != null && targetItem.isAlive()) {
					yield true;
				}
				targetItem = findNearestItem(world);
				yield targetItem != null;
			}
			case HUNT -> {
				if (targetAnimal != null && targetAnimal.isAlive()) {
					yield true;
				}
				targetAnimal = findNearestAnimal(world);
				yield targetAnimal != null;
			}
			// BUILD is never auto-picked by decide() (it is not in the priority rotation); it
			// only "has work" when a foreman/command has handed the colonist a target.
			case BUILD -> colonist.getBuildTarget() != null;
			default -> false;
		};
	}

	private void clearTargets(ServerWorld world) {
		if (targetBlock != null) {
			world.setBlockBreakingInfo(colonist.getId(), targetBlock, -1);
		}
		targetBlock = null;
		targetChest = null;
		targetAnimal = null;
		targetItem = null;
		breakProgress = 0;
		lastBreakStage = -1;
		stuckTimer = 0;
	}

	// ---- MINE / CHOP -------------------------------------------------------
	private void stepDig(ServerWorld world) {
		boolean mining = colonist.getJob() == ColonistEntity.Job.MINE;
		if (targetBlock == null) {
			targetBlock = findNearestBlock(world, mining);
			if (targetBlock == null) {
				return; // nothing to dig — next decision will move us off this job
			}
		}
		BlockState state = world.getBlockState(targetBlock);
		if (mining ? !isOre(state) : !isLog(state)) {
			// Someone/something removed it — drop the target and rescan next tick.
			world.setBlockBreakingInfo(colonist.getId(), targetBlock, -1);
			targetBlock = null;
			breakProgress = 0;
			lastBreakStage = -1;
			return;
		}
		double d2 = colonist.squaredDistanceTo(
			targetBlock.getX() + 0.5, targetBlock.getY() + 0.5, targetBlock.getZ() + 0.5);
		if (d2 > REACH_SQ) {
			travelTo(targetBlock.getX() + 0.5, targetBlock.getY() + 0.5, targetBlock.getZ() + 0.5);
			if (++stuckTimer >= STUCK_LIMIT) {
				// Can't reach it (buried / walled off) — blacklist and look elsewhere.
				ignored.add(targetBlock.asLong());
				world.setBlockBreakingInfo(colonist.getId(), targetBlock, -1);
				targetBlock = null;
				breakProgress = 0;
				stuckTimer = 0;
			}
			return;
		}
		// In reach: stop and mine.
		stuckTimer = 0;
		colonist.getNavigation().stop();
		colonist.getLookControl().lookAt(
			targetBlock.getX() + 0.5, targetBlock.getY() + 0.5, targetBlock.getZ() + 0.5);
		colonist.swingHand(Hand.MAIN_HAND);
		breakProgress++;

		int stage = Math.min(9, breakProgress * 10 / BREAK_TICKS);
		if (stage != lastBreakStage) {
			lastBreakStage = stage;
			world.setBlockBreakingInfo(colonist.getId(), targetBlock, stage);
		}
		if (breakProgress % FX_STRIDE == 0) {
			world.playSound(null, targetBlock, state.getSoundGroup().getHitSound(),
				SoundCategory.BLOCKS, 0.4f, 0.9f);
			world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state),
				targetBlock.getX() + 0.5, targetBlock.getY() + 0.5, targetBlock.getZ() + 0.5,
				5, 0.3, 0.3, 0.3, 0.0);
		}
		if (breakProgress >= BREAK_TICKS) {
			completeDig(world, targetBlock, state);
		}
	}

	/** Break {@code pos}: collect its loot into the backpack, then remove the block. */
	private void completeDig(ServerWorld world, BlockPos pos, BlockState state) {
		BlockEntity be = world.getBlockEntity(pos);
		List<ItemStack> drops = Block.getDroppedStacks(state, world, pos, be, colonist,
			colonist.getMainHandStack());
		for (ItemStack drop : drops) {
			ItemStack leftover = colonist.getInventory().addStack(drop);
			if (!leftover.isEmpty()) {
				// Backpack overflowed mid-break — spill the remainder so it isn't lost.
				colonist.dropStack(world, leftover);
			}
		}
		world.setBlockBreakingInfo(colonist.getId(), pos, -1);
		world.breakBlock(pos, false);
		world.playSound(null, pos, state.getSoundGroup().getBreakSound(),
			SoundCategory.BLOCKS, 0.7f, 1.0f);
		targetBlock = null;
		breakProgress = 0;
		lastBreakStage = -1;
	}

	// ---- BUILD -------------------------------------------------------------
	/**
	 * Raise (or, in {@code repairOnly} mode, patch) the assigned wall segment — the defensive
	 * mirror of the Warlord. The segment is a line of {@link ColonistEntity.BuildTarget#length()}
	 * columns stepping one block per column along {@link ColonistEntity.BuildTarget#dir()} from
	 * the origin, each column filled from {@code origin.y} up for {@code height} blocks.
	 *
	 * <p>Each tick we pick the LOWEST cell still needing fill (column by column, bottom-up), path
	 * to it if out of reach, and — paced by {@link #BUILD_INTERVAL} so the wall rises visibly —
	 * free-place one block there (no inventory cost: the colony is a helper). "Needs fill" is air
	 * or a replaceable block (grass, water); {@code repairOnly} restricts it to air only, so a
	 * repair never overwrites standing blocks. Existing solid/wall blocks are always skipped, so a
	 * build never griefs terrain. When no cell remains the segment is done → clear the target,
	 * dropping the colonist back to rule-based work.
	 */
	private void stepBuild(ServerWorld world, ColonistEntity.BuildTarget target) {
		BlockPos cell = nextBuildCell(world, target);
		if (cell == null) {
			// Segment complete — release the colonist and let decide() run immediately.
			colonist.clearBuildTarget();
			clearTargets(world);
			decisionTimer = 0;
			buildTimer = 0;
			return;
		}
		double d2 = colonist.squaredDistanceTo(cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5);
		boolean reachable = d2 <= BUILD_REACH_SQ;
		if (!reachable && stuckTimer < STUCK_LIMIT) {
			// Walk to the base of the segment (its origin) — a stable stand-point from which the
			// whole column tops out within BUILD_REACH — re-pathing until we arrive or time out.
			BlockPos origin = target.origin();
			travelTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
			stuckTimer++;
			return;
		}
		// In reach (or we gave up walking): stop, face the cell, and lay one block per interval.
		stuckTimer = 0;
		colonist.getNavigation().stop();
		colonist.getLookControl().lookAt(cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5);
		colonist.swingHand(Hand.MAIN_HAND);
		if (buildTimer > 0) {
			buildTimer--;
			return;
		}
		buildTimer = BUILD_INTERVAL;
		placeWallBlock(world, cell, resolveBuildBlock(target.blockId()));
	}

	/**
	 * The next cell in the segment that still needs a block, scanning columns in order and each
	 * column bottom-up, or {@code null} when the whole segment is filled.
	 */
	@Nullable
	private BlockPos nextBuildCell(ServerWorld world, ColonistEntity.BuildTarget target) {
		BlockPos origin = target.origin();
		Direction dir = target.dir();
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int i = 0; i < target.length(); i++) {
			for (int h = 0; h < target.height(); h++) {
				cursor.set(origin.getX() + dir.getOffsetX() * i, origin.getY() + h,
					origin.getZ() + dir.getOffsetZ() * i);
				if (needsFill(world.getBlockState(cursor), target.repairOnly())) {
					return cursor.toImmutable();
				}
			}
		}
		return null;
	}

	/** True if a cell should be (re)filled: air always; a replaceable block only for a fresh raise. */
	private static boolean needsFill(BlockState state, boolean repairOnly) {
		if (state.isAir()) {
			return true;
		}
		return !repairOnly && state.isReplaceable();
	}

	/** Free-place a wall block with a light placement thud + block-dust puff for feedback. */
	private void placeWallBlock(ServerWorld world, BlockPos pos, BlockState state) {
		if (!world.setBlockState(pos, state)) {
			return;
		}
		world.playSound(null, pos, state.getSoundGroup().getPlaceSound(),
			SoundCategory.BLOCKS, 0.7f, 1.0f);
		world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state),
			pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.0);
	}

	/** Resolve a build block id to a state, falling back to cobblestone on any invalid/unknown id. */
	private static BlockState resolveBuildBlock(String blockId) {
		try {
			return BlockArgumentParser.block(Registries.BLOCK, blockId, false).blockState();
		} catch (CommandSyntaxException e) {
			return Blocks.COBBLESTONE.getDefaultState();
		}
	}

	// ---- HAUL --------------------------------------------------------------
	private void stepHaul(ServerWorld world) {
		if (colonist.isInventoryEmpty()) {
			colonist.setWarnedNoChest(false);
			return; // nothing to haul — decision will pick a gather job
		}
		if (targetChest == null || !isUsableChest(world, targetChest)) {
			targetChest = findNearestChest(world);
		}
		if (targetChest == null) {
			warnOnce("No chest near the colony flag to store goods — place one within "
				+ (int) HOME_RADIUS + " blocks.");
			stepIdle(world);
			return;
		}
		double d2 = colonist.squaredDistanceTo(
			targetChest.getX() + 0.5, targetChest.getY() + 0.5, targetChest.getZ() + 0.5);
		if (d2 > REACH_SQ) {
			travelTo(targetChest.getX() + 0.5, targetChest.getY() + 0.5, targetChest.getZ() + 0.5);
			return;
		}
		colonist.getNavigation().stop();
		colonist.getLookControl().lookAt(
			targetChest.getX() + 0.5, targetChest.getY() + 0.5, targetChest.getZ() + 0.5);
		Inventory chest = HopperBlockEntity.getInventoryAt(world, targetChest);
		if (chest == null) {
			targetChest = null;
			return;
		}
		deposit(colonist.getInventory(), chest);
		if (colonist.isInventoryEmpty()) {
			colonist.setWarnedNoChest(false);
			targetChest = null; // done — resume gathering
		} else {
			// Chest wouldn't take everything — warn once and try a different chest.
			warnOnce("A colony chest is full — add more storage near the flag.");
			targetChest = null;
		}
	}

	// ---- FORAGE ------------------------------------------------------------
	private void stepForage(ServerWorld world) {
		if (targetItem == null || !targetItem.isAlive()) {
			targetItem = findNearestItem(world);
		}
		if (targetItem == null) {
			return;
		}
		if (colonist.squaredDistanceTo(targetItem) > PICKUP_REACH_SQ) {
			travelTo(targetItem.getX(), targetItem.getY(), targetItem.getZ());
			return;
		}
		pickUp(targetItem);
		targetItem = null;
	}

	// ---- HUNT --------------------------------------------------------------
	private void stepHunt(ServerWorld world) {
		if (targetAnimal == null || !targetAnimal.isAlive()) {
			targetAnimal = findNearestAnimal(world);
		}
		if (targetAnimal == null) {
			// No quarry — sweep up any drops from the last kill so the hunt still hauls.
			ItemEntity drop = findNearestItem(world);
			if (drop != null && colonist.squaredDistanceTo(drop) <= PICKUP_REACH_SQ) {
				pickUp(drop);
			} else if (drop != null) {
				travelTo(drop.getX(), drop.getY(), drop.getZ());
			}
			return;
		}
		double d2 = colonist.squaredDistanceTo(targetAnimal);
		if (d2 > ATTACK_REACH_SQ) {
			travelTo(targetAnimal.getX(), targetAnimal.getY(), targetAnimal.getZ());
			if (attackTimer > 0) {
				attackTimer--;
			}
			return;
		}
		colonist.getNavigation().stop();
		colonist.getLookControl().lookAt(targetAnimal);
		colonist.swingHand(Hand.MAIN_HAND);
		if (attackTimer > 0) {
			attackTimer--;
			return;
		}
		attackTimer = ATTACK_COOLDOWN;
		float dmg = (float) colonist.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);
		targetAnimal.damage(world, colonist.getDamageSources().mobAttack(colonist), Math.max(2.0f, dmg));
		if (!targetAnimal.isAlive()) {
			targetAnimal = null;
		}
	}

	// ---- IDLE --------------------------------------------------------------
	private void stepIdle(ServerWorld world) {
		BlockPos home = colonist.getHome();
		if (home == null) {
			return;
		}
		double d2 = colonist.squaredDistanceTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
		if (d2 > 6.0 * 6.0) {
			travelTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
		} else {
			colonist.getNavigation().stop();
		}
	}

	// ---- shared helpers ----------------------------------------------------
	/** Re-path toward a point, rate-limited so we don't restart the navigator every tick. */
	private void travelTo(double x, double y, double z) {
		EntityNavigation nav = colonist.getNavigation();
		if (nav.isIdle() || ++repathTimer >= REPATH_INTERVAL) {
			repathTimer = 0;
			nav.startMovingTo(x, y, z, SPEED);
		}
	}

	/** Fold an item entity into the backpack, discarding it once fully absorbed. */
	private void pickUp(ItemEntity item) {
		ItemStack leftover = colonist.getInventory().addStack(item.getStack());
		if (leftover.isEmpty()) {
			item.discard();
		} else {
			item.setStack(leftover);
		}
	}

	/** Send a one-shot advisory to the owner (latched on the colonist so it fires once). */
	private void warnOnce(String message) {
		if (colonist.hasWarnedNoChest()) {
			return;
		}
		colonist.setWarnedNoChest(true);
		if (colonist.resolveOwner() != null) {
			colonist.resolveOwner().sendMessage(Text.literal("[" + colonist.getColonistName()
				+ "] " + message).formatted(Formatting.YELLOW), false);
		}
	}

	private boolean withinHome(BlockPos pos) {
		BlockPos home = colonist.getHome();
		BlockPos center = home != null ? home : colonist.getBlockPos();
		return center.getSquaredDistance(pos) <= HOME_RADIUS * HOME_RADIUS;
	}

	// ---- scans -------------------------------------------------------------
	/** Nearest ore ({@code ore=true}) or log ({@code ore=false}) near home, or null. */
	@Nullable
	private BlockPos findNearestBlock(ServerWorld world, boolean ore) {
		BlockPos origin = colonist.getBlockPos();
		int down = ore ? ORE_DOWN : LOG_DOWN;
		int up = ore ? ORE_UP : LOG_UP;
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		BlockPos best = null;
		double bestSq = Double.MAX_VALUE;
		for (int dx = -SCAN_XZ; dx <= SCAN_XZ; dx++) {
			for (int dz = -SCAN_XZ; dz <= SCAN_XZ; dz++) {
				for (int dy = -down; dy <= up; dy++) {
					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (ignored.contains(cursor.asLong()) || !withinHome(cursor)) {
						continue;
					}
					BlockState state = world.getBlockState(cursor);
					if (ore ? !isOre(state) : !isLog(state)) {
						continue;
					}
					double sq = colonist.squaredDistanceTo(
						cursor.getX() + 0.5, cursor.getY() + 0.5, cursor.getZ() + 0.5);
					if (sq < bestSq) {
						bestSq = sq;
						best = cursor.toImmutable();
					}
				}
			}
		}
		return best;
	}

	/** Nearest usable (present, non-full) chest/barrel near home, or null. */
	@Nullable
	private BlockPos findNearestChest(ServerWorld world) {
		// Honour a deposit hint (the chest the commanding player was aiming at) if usable.
		BlockPos hint = colonist.getPreferredChest();
		if (hint != null && isUsableChest(world, hint) && withinHome(hint)) {
			return hint;
		}
		BlockPos origin = colonist.getBlockPos();
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		BlockPos best = null;
		double bestSq = Double.MAX_VALUE;
		for (int dx = -SCAN_XZ; dx <= SCAN_XZ; dx++) {
			for (int dz = -SCAN_XZ; dz <= SCAN_XZ; dz++) {
				for (int dy = -CHEST_Y; dy <= CHEST_Y; dy++) {
					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (!withinHome(cursor) || !isChestBlock(world.getBlockState(cursor))) {
						continue;
					}
					if (!isUsableChest(world, cursor)) {
						continue;
					}
					double sq = colonist.squaredDistanceTo(
						cursor.getX() + 0.5, cursor.getY() + 0.5, cursor.getZ() + 0.5);
					if (sq < bestSq) {
						bestSq = sq;
						best = cursor.toImmutable();
					}
				}
			}
		}
		return best;
	}

	@Nullable
	private ItemEntity findNearestItem(ServerWorld world) {
		Box box = new Box(colonist.getBlockPos()).expand(HOME_RADIUS);
		ItemEntity best = null;
		double bestSq = Double.MAX_VALUE;
		for (ItemEntity item : world.getEntitiesByClass(ItemEntity.class, box,
				e -> e.isAlive() && withinHome(e.getBlockPos()))) {
			double sq = colonist.squaredDistanceTo(item);
			if (sq < bestSq) {
				bestSq = sq;
				best = item;
			}
		}
		return best;
	}

	@Nullable
	private AnimalEntity findNearestAnimal(ServerWorld world) {
		Box box = new Box(colonist.getBlockPos()).expand(HOME_RADIUS);
		AnimalEntity best = null;
		double bestSq = Double.MAX_VALUE;
		for (AnimalEntity animal : world.getEntitiesByClass(AnimalEntity.class, box,
				e -> e.isAlive() && withinHome(e.getBlockPos()))) {
			double sq = colonist.squaredDistanceTo(animal);
			if (sq < bestSq) {
				bestSq = sq;
				best = animal;
			}
		}
		return best;
	}

	// ---- block/inventory predicates ----------------------------------------
	/** Match ores: the colonist's {@code oreFilter} keyword, else any {@code *_ore}. */
	private boolean isOre(BlockState state) {
		String path = Registries.BLOCK.getId(state.getBlock()).getPath();
		if (!path.contains("ore")) {
			return false;
		}
		String filter = colonist.getOreFilter();
		if (filter == null || filter.isBlank()) {
			return true; // default valuable set = any ore
		}
		return path.contains(filter.toLowerCase(java.util.Locale.ROOT));
	}

	private static boolean isLog(BlockState state) {
		String path = Registries.BLOCK.getId(state.getBlock()).getPath();
		return path.endsWith("_log") || path.endsWith("_stem")
			|| path.endsWith("_wood") || path.endsWith("_hyphae");
	}

	private static boolean isChestBlock(BlockState state) {
		String path = Registries.BLOCK.getId(state.getBlock()).getPath();
		return path.equals("chest") || path.equals("trapped_chest") || path.equals("barrel");
	}

	/** True if a chest exists at {@code pos} and has at least one slot with room. */
	private static boolean isUsableChest(ServerWorld world, BlockPos pos) {
		if (!isChestBlock(world.getBlockState(pos))) {
			return false;
		}
		Inventory inv = HopperBlockEntity.getInventoryAt(world, pos);
		if (inv == null) {
			return false;
		}
		for (int i = 0; i < inv.size(); i++) {
			ItemStack stack = inv.getStack(i);
			if (stack.isEmpty() || stack.getCount() < stack.getMaxCount()) {
				return true;
			}
		}
		return false;
	}

	// ---- chest insertion ---------------------------------------------------
	/** Move as much of the backpack as fits into {@code dest}, leaving the rest behind. */
	private static void deposit(SimpleInventory from, Inventory dest) {
		boolean changed = false;
		for (int i = 0; i < from.size(); i++) {
			ItemStack stack = from.getStack(i);
			if (stack.isEmpty()) {
				continue;
			}
			ItemStack leftover = insert(dest, stack);
			if (leftover.getCount() != stack.getCount()) {
				changed = true;
			}
			from.setStack(i, leftover.isEmpty() ? ItemStack.EMPTY : leftover);
		}
		if (changed) {
			dest.markDirty();
		}
	}

	/** Insert {@code stack} into an inventory (merge then fill empties); return the leftover. */
	private static ItemStack insert(Inventory dest, ItemStack stack) {
		// Merge into matching stacks first.
		for (int i = 0; i < dest.size() && !stack.isEmpty(); i++) {
			if (!dest.isValid(i, stack)) {
				continue;
			}
			ItemStack slot = dest.getStack(i);
			if (slot.isEmpty() || !ItemStack.areItemsAndComponentsEqual(slot, stack)) {
				continue;
			}
			int cap = Math.min(dest.getMaxCount(slot), slot.getMaxCount());
			int move = Math.min(stack.getCount(), cap - slot.getCount());
			if (move > 0) {
				slot.increment(move);
				stack.decrement(move);
			}
		}
		// Then drop the remainder into empty slots.
		for (int i = 0; i < dest.size() && !stack.isEmpty(); i++) {
			if (!dest.isValid(i, stack) || !dest.getStack(i).isEmpty()) {
				continue;
			}
			int cap = Math.min(dest.getMaxCount(stack), stack.getMaxCount());
			int move = Math.min(stack.getCount(), cap);
			ItemStack placed = stack.copy();
			placed.setCount(move);
			dest.setStack(i, placed);
			stack.decrement(move);
		}
		return stack;
	}
}
