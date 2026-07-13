package net.bubblesky.towerdefense.fluid;

import net.bubblesky.towerdefense.registry.ModBlocks;
import net.bubblesky.towerdefense.registry.ModFluids;
import net.bubblesky.towerdefense.registry.ModItems;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCollisionHandler;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.Item;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/**
 * The {@code towerdefense:acid} fluid — a real, flowing corrosive liquid built on the
 * vanilla {@link FlowableFluid} engine (mirroring {@link net.minecraft.fluid.WaterFluid}).
 *
 * <p>Because it extends {@link FlowableFluid}, all the hard parts — spreading, pooling,
 * flowing downhill, level bookkeeping, the still/flowing state machine — are inherited
 * from vanilla and behave exactly like water. This class only supplies the identity
 * knobs (which fluids form the still/flowing pair, the bucket, the block it maps to,
 * flow tuning) and the one piece of acid flavor that lives on the fluid: periodic
 * <b>entity damage</b> for anything standing in it. The other half of acid's identity —
 * slowly eating through terrain — lives on {@link net.bubblesky.towerdefense.block.AcidFluidBlock}
 * (via random-tick corrosion), so the fluid can still pool inside its own container
 * without instantly dissolving it.
 *
 * <p>Split into the standard nested {@link Still} (the level-8 source) and {@link Flowing}
 * (the level 1..7 leading edge) subclasses, just like {@code WaterFluid.Still} /
 * {@code WaterFluid.Flowing}.
 */
public abstract class AcidFluid extends FlowableFluid {

	// ---------------------------------------------------------------------
	// Acid flavor tuning (entity damage). Corrosion tuning lives on the block.
	// ---------------------------------------------------------------------
	/** Damage dealt to an entity standing in acid, per damage interval. Mirrors the old AcidBlock. */
	public static final float DAMAGE = 2.0f;
	/** Ticks between damage applications to an entity in acid. Mirrors the old AcidBlock. */
	public static final int DAMAGE_INTERVAL = 15;

	@Override
	public Fluid getStill() {
		return ModFluids.STILL_ACID;
	}

	@Override
	public Fluid getFlowing() {
		return ModFluids.FLOWING_ACID;
	}

	@Override
	public Item getBucketItem() {
		return ModItems.ACID_BUCKET;
	}

	/**
	 * Acid can be overrun the same way water can: a fluid flowing straight down into
	 * it replaces it, but a matching acid fluid (its own still/flowing pair) never
	 * replaces itself. Copied from {@code WaterFluid#canBeReplacedWith}.
	 */
	@Override
	protected boolean canBeReplacedWith(FluidState state, BlockView world, BlockPos pos, Fluid fluid, Direction direction) {
		return direction == Direction.DOWN && !fluid.matchesType(this);
	}

	/**
	 * When the fluid overtakes a block as it spreads, drop <em>nothing</em> (acid
	 * annihilates what it flows over). Vanilla water instead drops the block's loot;
	 * an empty body here is the "drop nothing" behavior the design calls for.
	 */
	@Override
	protected void beforeBreakingBlock(WorldAccess world, BlockPos pos, BlockState state) {
		// Intentionally empty: acid destroys what it replaces, dropping no items.
	}

	/** No infinite-source pooling: two acid sources never form a third. */
	@Override
	protected boolean isInfinite(ServerWorld world) {
		return false;
	}

	/**
	 * Flow reach before a level step is lost. {@code 1} (same as water) so acid spreads
	 * far and thin across flats rather than the {@code 2} lava uses.
	 */
	@Override
	protected int getLevelDecreasePerBlock(WorldView world) {
		return 1;
	}

	/** How many blocks acid may search when looking for a downhill path — water-like reach. */
	@Override
	protected int getMaxFlowDistance(WorldView world) {
		return 4;
	}

	/** Tick cadence of the flow updates. {@code 5} matches vanilla water (fast, watery flow). */
	@Override
	public int getTickRate(WorldView world) {
		return 5;
	}

	/** Explosion resistance of the fluid, mirroring water's near-indestructible pool. */
	@Override
	protected float getBlastResistance() {
		return 100.0f;
	}

	/** The still and flowing acid fluids are considered the same "type" (like water's pair). */
	@Override
	public boolean matchesType(Fluid fluid) {
		return fluid == ModFluids.STILL_ACID || fluid == ModFluids.FLOWING_ACID;
	}

	/**
	 * Map a fluid state onto the {@code towerdefense:acid} block state, carrying the
	 * fluid level across via {@link FluidBlock#LEVEL}. Copied from {@code WaterFluid#toBlockState}.
	 */
	@Override
	protected BlockState toBlockState(FluidState state) {
		return ModBlocks.ACID.getDefaultState().with(FluidBlock.LEVEL, getBlockStateLevel(state));
	}

	/**
	 * Acid flavor: entities standing in the fluid take {@link #DAMAGE} on a fixed
	 * {@link #DAMAGE_INTERVAL} cadence (server-side only). Mirrors the old
	 * {@code AcidBlock#onEntityCollision}, using world time modulo the interval so the
	 * damage is throttled regardless of how many acid cells the entity touches.
	 */
	@Override
	protected void onEntityCollision(World world, BlockPos pos, Entity entity, EntityCollisionHandler handler) {
		if (world instanceof ServerWorld serverWorld && serverWorld.getTime() % DAMAGE_INTERVAL == 0) {
			entity.damage(serverWorld, serverWorld.getDamageSources().magic(), DAMAGE);
		}
	}

	/**
	 * The flowing (edge) variant: carries the {@link FlowableFluid#LEVEL} property (1..7)
	 * and is never "still".
	 */
	public static class Flowing extends AcidFluid {
		@Override
		protected void appendProperties(StateManager.Builder<Fluid, FluidState> builder) {
			super.appendProperties(builder);
			builder.add(LEVEL);
		}

		@Override
		public int getLevel(FluidState state) {
			return state.get(LEVEL);
		}

		@Override
		public boolean isStill(FluidState state) {
			return false;
		}
	}

	/**
	 * The still (source) variant: always full (level 8) and always "still".
	 */
	public static class Still extends AcidFluid {
		@Override
		public int getLevel(FluidState state) {
			return 8;
		}

		@Override
		public boolean isStill(FluidState state) {
			return true;
		}
	}
}
