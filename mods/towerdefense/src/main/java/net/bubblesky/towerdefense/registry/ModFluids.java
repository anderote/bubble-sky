package net.bubblesky.towerdefense.registry;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.bubblesky.towerdefense.fluid.AcidFluid;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * Registers the mod's custom fluids.
 *
 * <p>{@code towerdefense:acid} is a <em>real</em> flowing fluid built on the vanilla
 * {@link net.minecraft.fluid.FlowableFluid} engine (the same engine that drives water),
 * so it spreads, pools and flows downhill exactly like water. Like every vanilla
 * flowing fluid it comes as a matched <b>still</b>/<b>flowing</b> pair:
 * <ul>
 *   <li>{@link #STILL_ACID} — the full source block (level 8), placed by the
 *       {@code acid_bucket}.</li>
 *   <li>{@link #FLOWING_ACID} — the leading edge that creeps outward (levels 1..7),
 *       spawned automatically by the engine as the source spreads.</li>
 * </ul>
 *
 * <p>Registration order matters: {@link net.bubblesky.towerdefense.block.AcidFluidBlock}
 * binds to {@link #STILL_ACID} in its constructor, so {@link #initialize()} must run
 * <em>before</em> {@link ModBlocks#initialize()} in the mod entrypoint. The acid
 * fluid's own back-references ({@code getBucketItem}/{@code toBlockState}/{@code
 * getStill}/{@code getFlowing}) are all resolved lazily at flow time, so no other
 * ordering constraints apply.
 */
public final class ModFluids {
	private ModFluids() {
	}

	/** The still acid source block (level 8). Placed by the acid bucket. */
	public static final FlowableFluid STILL_ACID = Registry.register(Registries.FLUID,
		Identifier.of(TowerDefenseMod.MOD_ID, "acid"), new AcidFluid.Still());

	/** The flowing acid edge (levels 1..7). Spawned by the fluid engine as acid spreads. */
	public static final FlowableFluid FLOWING_ACID = Registry.register(Registries.FLUID,
		Identifier.of(TowerDefenseMod.MOD_ID, "flowing_acid"), new AcidFluid.Flowing());

	/** Forces class load so the static registrations above run. */
	public static void initialize() {
	}
}
