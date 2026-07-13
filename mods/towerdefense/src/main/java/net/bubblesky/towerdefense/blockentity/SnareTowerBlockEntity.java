package net.bubblesky.towerdefense.blockentity;

import java.util.List;
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
import net.minecraft.util.math.Box;

/** Area-control tower: pins a pack in place so damage towers can work. */
public class SnareTowerBlockEntity extends AbstractTowerBlockEntity {
	private static final double BASE_RANGE = 26.0;
	private static final int BASE_COOLDOWN = 55;
	private static final double SNARE_RADIUS = 4.0;
	private static final int SNARE_TICKS = 80;

	public SnareTowerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.SNARE_TOWER, pos, state);
	}

	@Override
	public net.bubblesky.towerdefense.tower.TowerKind kind() {
		return net.bubblesky.towerdefense.tower.TowerKind.SNARE;
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
		int amplifier = Math.min(4, getTier());
		int duration = SNARE_TICKS + (getTier() - 1) * 20;
		Box zone = new Box(target.getBlockPos()).expand(SNARE_RADIUS);
		List<HostileEntity> mobs = world.getNonSpectatingEntities(HostileEntity.class, zone);
		for (HostileEntity mob : mobs) {
			if (mob.isAlive() && mob.squaredDistanceTo(target) <= SNARE_RADIUS * SNARE_RADIUS) {
				mob.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, duration, amplifier));
			}
		}
		world.spawnParticles(new DustParticleEffect(0x2DD45C, 1.5f),
			target.getX(), target.getBodyY(0.4), target.getZ(), 45 + 12 * getTier(),
			SNARE_RADIUS * 0.45, 0.25, SNARE_RADIUS * 0.45, 0.02);
		world.playSound(null, cx, cy, cz, SoundEvents.BLOCK_ROOTS_BREAK, SoundCategory.BLOCKS, 0.7f, 0.7f);
	}
}
