package net.bubblesky.towerdefense.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCollisionHandler;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import net.minecraft.world.tick.ScheduledTickView;

/**
 * The {@code towerdefense:acid} block: a thin, translucent, corrosive green
 * pseudo-liquid. It behaves like a scheduled-tick "creeping" block rather than a
 * full fluid — simpler and self-contained, but it still flows and eats through
 * (almost) everything.
 *
 * <p>Design summary:
 * <ul>
 *   <li><b>Corrode</b> — on each scheduled tick it tries to dissolve its 6
 *       neighbors into air, always attempting the block <em>below</em> first
 *       (gravity-ish) and the other 5 with {@link #CORRODE_CHANCE}.</li>
 *   <li><b>Spread</b> — it flows into newly opened air: straight down first,
 *       otherwise horizontally up to {@link #SPREAD_DISTANCE} blocks from its
 *       source.</li>
 *   <li><b>Eat-budget</b> — the {@link #CHARGE} blockstate property (0..15) is
 *       the anti-runaway governor: it decrements whenever the acid does work,
 *       children inherit a reduced charge, and at 0 the acid evaporates to air.
 *       This hard-caps how much world a single pool can ever consume.</li>
 *   <li><b>Immunity</b> — blocks in the {@code towerdefense:acid_immune} tag are
 *       never dissolved, plus a code backstop that spares any block with
 *       negative hardness (unbreakable, e.g. bedrock/barrier).</li>
 *   <li><b>Damage</b> — entities standing in acid take {@link #DAMAGE} every
 *       {@link #DAMAGE_INTERVAL} ticks.</li>
 * </ul>
 */
public class AcidBlock extends Block {
	public static final MapCodec<AcidBlock> CODEC = createCodec(AcidBlock::new);

	// ---------------------------------------------------------------------
	// Tuning constants (all in one place)
	// ---------------------------------------------------------------------
	/** Per-neighbor probability (per tick) that a side/up neighbor is dissolved. */
	public static final float CORRODE_CHANCE = 0.6f;
	/** Max horizontal steps acid may creep away from a source before it must sink. */
	public static final int SPREAD_DISTANCE = 4;
	/** Charge a freshly placed source starts with; also the property maximum. */
	public static final int START_CHARGE = 15;
	/** Damage dealt to an entity touching acid, per damage interval. */
	public static final float DAMAGE = 2.0f;
	/** Ticks between damage applications to an entity in acid. */
	public static final int DAMAGE_INTERVAL = 15;
	/** Ticks between corrosion/spread ticks. */
	public static final int TICK_DELAY = 5;

	/** Eat-budget property (0..15). At 0 the acid turns to air. */
	public static final IntProperty CHARGE = IntProperty.of("charge", 0, START_CHARGE);

	/** Blocks the acid must never dissolve. */
	public static final TagKey<Block> ACID_IMMUNE = TagKey.of(RegistryKeys.BLOCK,
		Identifier.of("towerdefense", "acid_immune"));

	private static final Direction[] SIDE_AND_UP = {
		Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
	};
	private static final Direction[] HORIZONTAL = {
		Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
	};

	public AcidBlock(Settings settings) {
		super(settings);
		setDefaultState(getStateManager().getDefaultState().with(CHARGE, START_CHARGE));
	}

	@Override
	protected MapCodec<? extends Block> getCodec() {
		return CODEC;
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(CHARGE);
	}

	// Walk/sink straight through, like water.
	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return VoxelShapes.empty();
	}

	/** Schedule the first corrosion tick as soon as a source is placed. */
	@Override
	protected void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
		world.scheduleBlockTick(pos, this, TICK_DELAY);
	}

	/** Wake the acid whenever a neighbor changes (e.g. a wall is mined away). */
	@Override
	protected BlockState getStateForNeighborUpdate(BlockState state, WorldView world, ScheduledTickView tickView,
			BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState, Random random) {
		tickView.scheduleBlockTick(pos, this, TICK_DELAY);
		return state;
	}

	@Override
	protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		int charge = state.get(CHARGE);
		if (charge <= 0) {
			world.setBlockState(pos, defaultAirLike(world, pos));
			return;
		}

		// 1. Corrode downward first — acid prefers to eat straight down.
		BlockPos below = pos.down();
		if (canDissolve(world, below)) {
			world.setBlockState(below, defaultAirLike(world, below));
		}

		// 2. Corrode the other neighbors by chance.
		for (Direction dir : SIDE_AND_UP) {
			BlockPos np = pos.offset(dir);
			if (canDissolve(world, np) && random.nextFloat() < CORRODE_CHANCE) {
				world.setBlockState(np, defaultAirLike(world, np));
			}
		}

		// 3. Spread into open air: straight down first, else horizontally.
		BlockState belowState = world.getBlockState(below);
		if (belowState.isAir() && charge - 1 > 0) {
			placeChild(world, below, charge - 1);
		} else if (charge > START_CHARGE - SPREAD_DISTANCE) {
			// Only creep sideways while still within SPREAD_DISTANCE of a source.
			for (Direction dir : HORIZONTAL) {
				BlockPos np = pos.offset(dir);
				if (world.getBlockState(np).isAir() && charge - 1 > 0) {
					placeChild(world, np, charge - 1);
				}
			}
		}

		// 4. Decay one charge every tick (the anti-runaway governor). This runs
		// whether or not the acid found anything to eat, so every acid block is
		// guaranteed to evaporate to air within START_CHARGE * TICK_DELAY ticks —
		// no permanent, world-eating pools.
		int newCharge = charge - 1;
		if (newCharge <= 0) {
			world.setBlockState(pos, defaultAirLike(world, pos));
		} else {
			world.setBlockState(pos, state.with(CHARGE, newCharge));
			world.scheduleBlockTick(pos, this, TICK_DELAY);
		}
	}

	/** Damage entities that stand in the acid on a fixed cadence. */
	@Override
	protected void onEntityCollision(BlockState state, World world, BlockPos pos, Entity entity,
			EntityCollisionHandler handler) {
		if (!(world instanceof ServerWorld serverWorld)) {
			return;
		}
		if (serverWorld.getTime() % DAMAGE_INTERVAL == 0) {
			entity.damage(serverWorld, serverWorld.getDamageSources().magic(), DAMAGE);
		}
	}

	/**
	 * Whether the block at {@code pos} may be dissolved. Immune-tagged blocks,
	 * air, acid itself and any unbreakable (negative-hardness) block are spared.
	 */
	private boolean canDissolve(ServerWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (state.isAir() || state.isOf(this)) {
			return false;
		}
		if (state.isIn(ACID_IMMUNE)) {
			return false;
		}
		// Code backstop: never eat unbreakable blocks even if the tag misses one.
		return state.getHardness(world, pos) >= 0;
	}

	/** Place a spread child that inherits a reduced charge and starts ticking. */
	private void placeChild(ServerWorld world, BlockPos pos, int childCharge) {
		world.setBlockState(pos, getDefaultState().with(CHARGE, childCharge));
		world.scheduleBlockTick(pos, this, TICK_DELAY);
	}

	/** Result of a dissolve: air, or the block's fluid (so we don't strand water). */
	private static BlockState defaultAirLike(ServerWorld world, BlockPos pos) {
		return world.getFluidState(pos).getBlockState();
	}
}
