package net.bubblesky.towerdefense.blockentity;

import java.util.List;
import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** Short-range attrition tower: poisons clustered enemies and chips them down. */
public class PoisonTowerBlockEntity extends AbstractTowerBlockEntity {
	private static final double BASE_RANGE = 22.0;
	private static final int BASE_COOLDOWN = 35;
	private static final double POISON_RADIUS = 3.5;
	private static final double DAMAGE = 2.0;
	private static final int POISON_TICKS = 100;

	public PoisonTowerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.POISON_TOWER, pos, state);
	}

	@Override
	public net.bubblesky.towerdefense.tower.TowerKind kind() {
		return net.bubblesky.towerdefense.tower.TowerKind.POISON;
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
		ServerPlayerEntity owner = placerPlayer(world);
		DamageSource source = owner != null
			? world.getDamageSources().playerAttack(owner)
			: world.getDamageSources().magic();
		int amplifier = Math.min(2, getTier() / 2);
		Box cloud = new Box(target.getBlockPos()).expand(POISON_RADIUS);
		List<HostileEntity> mobs = world.getNonSpectatingEntities(HostileEntity.class, cloud);
		for (HostileEntity mob : mobs) {
			if (mob.isAlive() && mob.squaredDistanceTo(target) <= POISON_RADIUS * POISON_RADIUS) {
				mob.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON,
					POISON_TICKS + 15 * getTier(), amplifier));
				damageAndCredit(world, mob, source, (float) (DAMAGE * damageMultiplier(mob)));
			}
		}
		world.spawnParticles(new DustParticleEffect(0x69D12F, 1.6f),
			target.getX(), target.getBodyY(0.45), target.getZ(), 55 + 10 * getTier(),
			POISON_RADIUS * 0.5, 0.35, POISON_RADIUS * 0.5, 0.03);
		world.playSound(null, cx, cy, cz, SoundEvents.BLOCK_BUBBLE_COLUMN_BUBBLE_POP,
			SoundCategory.BLOCKS, 0.75f, 0.8f);
	}
}
