package net.bubblesky.towerdefense.block;

import net.bubblesky.towerdefense.registry.ModFluids;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

/**
 * The block form of the {@code towerdefense:acid} fluid.
 *
 * <p>{@link FluidBlock}'s constructor is {@code protected}, so this thin subclass exists
 * mainly to expose it (binding the block to {@link ModFluids#STILL_ACID}) — the standard
 * pattern for a custom fluid. Everything about spreading/pooling/flowing is inherited from
 * {@link FluidBlock} + the vanilla fluid engine, so acid behaves exactly like water.
 *
 * <p>On top of that it adds the second half of acid's identity: <b>slow terrain corrosion</b>.
 * Rather than the old creeping-block approach (which dissolved everything around it every
 * tick), corrosion here is driven by the vanilla <em>random tick</em> — an infrequent,
 * per-block event. On a random tick, with probability {@link #CORRODE_CHANCE}, the acid
 * dissolves <em>one</em> adjacent non-immune block into air (the fluid then naturally flows
 * into the freshly opened space via the engine). Because the rate is low and random, acid
 * flows and pools like water and only gradually eats through terrain, instead of nuking its
 * surroundings — and critically, it does not instantly dissolve the very blocks containing
 * its pool.
 *
 * <p>Immunity mirrors the old {@code AcidBlock}: blocks in the {@code towerdefense:acid_immune}
 * tag are spared, plus a code backstop that never dissolves unbreakable (negative-hardness)
 * blocks such as bedrock/barrier. Fluids (including acid itself) are also skipped so acid
 * never wastefully churns its own pool or adjacent water.
 */
public class AcidFluidBlock extends FluidBlock {

	/** Per-random-tick probability that acid dissolves one adjacent block. Kept low so corrosion is gradual. */
	public static final float CORRODE_CHANCE = 0.5f;

	/** Blocks the acid must never dissolve (shared with the retired AcidBlock's data tag). */
	public static final TagKey<Block> ACID_IMMUNE = TagKey.of(RegistryKeys.BLOCK,
		Identifier.of("towerdefense", "acid_immune"));

	/**
	 * @param settings the block settings (replaceable, no collision, liquid, strength 100,
	 *                 drops nothing) — the acid fluid is bound in via {@link ModFluids#STILL_ACID}.
	 */
	public AcidFluidBlock(Settings settings) {
		super(ModFluids.STILL_ACID, settings);
	}

	/** Acid always random-ticks so it can slowly corrode neighbors as it sits/flows. */
	@Override
	protected boolean hasRandomTicks(BlockState state) {
		return true;
	}

	/**
	 * Slow corrosion: on each (already-infrequent) random tick, with probability
	 * {@link #CORRODE_CHANCE}, dissolve one randomly-chosen adjacent non-immune block into
	 * air. The fluid engine then flows acid into the opening on its own. We still delegate
	 * to {@code super.randomTick} to preserve any vanilla fluid random-tick behavior.
	 */
	@Override
	protected void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		super.randomTick(state, world, pos, random);
		if (random.nextFloat() >= CORRODE_CHANCE) {
			return;
		}
		// Prefer eating straight down (gravity flavor); otherwise a random side/up neighbor.
		BlockPos below = pos.down();
		if (canDissolve(world, below)) {
			dissolve(world, below);
			return;
		}
		Direction dir = Direction.values()[random.nextInt(Direction.values().length)];
		BlockPos target = pos.offset(dir);
		if (canDissolve(world, target)) {
			dissolve(world, target);
		}
	}

	/**
	 * Whether the block at {@code pos} may be dissolved. Immune-tagged blocks, air, any
	 * fluid (including acid itself, to avoid churning its own pool) and any unbreakable
	 * (negative-hardness) block are spared. Mirrors {@code AcidBlock#canDissolve}.
	 */
	private boolean canDissolve(ServerWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (state.isAir() || !state.getFluidState().isEmpty()) {
			return false; // nothing / a liquid (acid, water, ...) — skip
		}
		if (state.isIn(ACID_IMMUNE)) {
			return false;
		}
		// Code backstop: never eat unbreakable blocks even if the tag misses one.
		return state.getHardness(world, pos) >= 0;
	}

	/** Dissolve a block into air (or its underlying fluid, so we don't strand water). */
	private void dissolve(ServerWorld world, BlockPos pos) {
		world.setBlockState(pos, world.getFluidState(pos).getBlockState());
	}
}
