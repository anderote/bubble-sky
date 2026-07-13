package net.bubblesky.towerdefense.blockentity;

import java.util.List;
import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * The frost tower: a utility tower. It fires a snowball at the nearest hostile
 * and chills every enemy within {@link #CHILL_RADIUS} of the impact, applying
 * Slowness so the swarm crawls toward the base. Deals only token damage — its
 * value is crowd-control, buying the other towers time. The slow amplifier and
 * duration grow with tier.
 */
public class FrostTowerBlockEntity extends AbstractTowerBlockEntity {
	private static final double BASE_RANGE = 28.0;
	private static final int BASE_COOLDOWN = 40;
	private static final double FROST_DAMAGE = 1.0;
	private static final double CHILL_RADIUS = 3.0;
	private static final int SLOW_DURATION_TICKS = 60;
	private static final float LOB_SPEED = 1.3f;

	public FrostTowerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.FROST_TOWER, pos, state);
	}

	@Override
	public net.bubblesky.towerdefense.tower.TowerKind kind() {
		return net.bubblesky.towerdefense.tower.TowerKind.FROST;
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
		SnowballEntity ball = new SnowballEntity(world, cx, cy, cz, new ItemStack(Items.SNOWBALL));
		if (owner != null) {
			ball.setOwner(owner);
		}
		double dx = target.getX() - cx;
		double dy = target.getBodyY(0.5) - cy;
		double dz = target.getZ() - cz;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		ball.setVelocity(dx, dy + horizontal * 0.15, dz, LOB_SPEED, 1.0f);
		world.spawnEntity(ball);

		// Slowness amplifier scales with tier (0 / 1 / 2); duration grows too.
		int amplifier = getTier() - 1;
		int duration = SLOW_DURATION_TICKS + (getTier() - 1) * 20;
		DamageSource source = owner != null
			? world.getDamageSources().playerAttack(owner)
			: world.getDamageSources().magic();
		float damage = (float) (FROST_DAMAGE * damageMultiplier());

		Box chill = new Box(target.getBlockPos()).expand(CHILL_RADIUS);
		List<HostileEntity> hit = world.getNonSpectatingEntities(HostileEntity.class, chill);
		for (HostileEntity mob : hit) {
			if (mob.isAlive() && mob.squaredDistanceTo(target) <= CHILL_RADIUS * CHILL_RADIUS) {
				mob.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, duration, amplifier));
				if (damage > 0.0f) {
					damageAndCredit(world, mob, source, damage);
				}
			}
		}

		// Visible AoE: a cold chill-cloud filling the slow radius so the crowd-control
		// zone is obvious at a glance. Denser on higher tiers (which also chill harder).
		spawnChillCloud(world, target.getX(), target.getBodyY(0.5), target.getZ());

		world.playSound(null, cx, cy, cz, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 0.5f, 1.6f);
	}

	/**
	 * A cold burst centred on the impact point, sized to the chill radius: drifting
	 * snowflakes fill the zone, shattered snowball crumbs mark the hit, and a few soft
	 * clouds give it a frosty haze. Counts grow with tier so a stronger slow reads bigger.
	 */
	private void spawnChillCloud(ServerWorld world, double x, double y, double z) {
		int t = getTier();
		double r = CHILL_RADIUS;
		world.spawnParticles(ParticleTypes.SNOWFLAKE, x, y, z, 30 + 12 * t, r * 0.6, r * 0.4, r * 0.6, 0.02);
		world.spawnParticles(ParticleTypes.ITEM_SNOWBALL, x, y, z, 12 + 5 * t, r * 0.4, r * 0.3, r * 0.4, 0.05);
		world.spawnParticles(ParticleTypes.CLOUD, x, y + 0.1, z, 8 + 3 * t, r * 0.5, r * 0.2, r * 0.5, 0.0);
	}
}
