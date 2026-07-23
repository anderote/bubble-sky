package net.bubblesky.towerdefense.blockentity;

import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;

/** Marks priority targets so every tower burns them down faster. */
public class BeaconTowerBlockEntity extends AbstractTowerBlockEntity {
	public static final double MARKED_DAMAGE_MULT = 1.35;
	private static final double BASE_RANGE = 34.0;
	private static final int BASE_COOLDOWN = 35;
	private static final int MARK_TICKS = 90;

	public BeaconTowerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.BEACON_TOWER, pos, state);
	}

	@Override
	public net.bubblesky.towerdefense.tower.TowerKind kind() {
		return net.bubblesky.towerdefense.tower.TowerKind.BEACON;
	}

	@Override
	protected double baseRange() {
		return BASE_RANGE;
	}

	@Override
	protected int baseCooldown() {
		return BASE_COOLDOWN;
	}

	@Override
	protected void fire(ServerWorld world, double cx, double cy, double cz, HostileEntity target) {
		target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, MARK_TICKS + 10 * getTier(), 0));
		target.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, MARK_TICKS, 0));
		world.spawnParticles(new DustParticleEffect(0xFFE34A, 1.2f),
			target.getX(), target.getBodyY(0.8), target.getZ(), 18 + 4 * getTier(), 0.25, 0.5, 0.25, 0.02);
		world.playSound(null, cx, cy, cz, SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.BLOCKS, 0.55f, 1.4f);
	}

	public static boolean isMarked(HostileEntity target) {
		return target.hasStatusEffect(StatusEffects.GLOWING)
			&& target.hasStatusEffect(StatusEffects.WEAKNESS);
	}
}
