package net.bubblesky.towerdefense.blockentity;

import java.util.List;
import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.entity.damage.DamageSource;
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
 * The cannon tower: slow-firing, high-damage siege piece. It lobs a visible
 * projectile at the nearest hostile and deals splash damage to every enemy
 * within {@link #SPLASH_RADIUS} of the impact — the "throw a rock into the
 * crowd" tower. Great against clustered swarms, poor at single fast targets
 * because of its long cooldown.
 *
 * <p>Splash is applied directly (no vanilla explosion) via a player-owned
 * damage source so kills credit coins to the placer.
 */
public class CannonTowerBlockEntity extends AbstractTowerBlockEntity {
	private static final double BASE_RANGE = 28.0;
	private static final int BASE_COOLDOWN = 60;
	private static final double CANNON_DAMAGE = 8.0;
	private static final double SPLASH_RADIUS = 3.0;
	private static final float LOB_SPEED = 1.1f;

	public CannonTowerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.CANNON_TOWER, pos, state);
	}

	@Override
	public net.bubblesky.towerdefense.tower.TowerKind kind() {
		return net.bubblesky.towerdefense.tower.TowerKind.CANNON;
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
		// Cosmetic lobbed "cannonball" (snowball) for the throw-a-rock feel.
		ServerPlayerEntity owner = placerPlayer(world);
		SnowballEntity ball = new SnowballEntity(world, cx, cy, cz, new ItemStack(Items.SNOWBALL));
		if (owner != null) {
			ball.setOwner(owner);
		}
		double dx = target.getX() - cx;
		double dy = target.getBodyY(0.5) - cy;
		double dz = target.getZ() - cz;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		ball.setVelocity(dx, dy + horizontal * 0.2, dz, LOB_SPEED, 1.0f);
		world.spawnEntity(ball);

		// Splash damage at the target: every enemy in the blast radius takes the hit.
		DamageSource source = owner != null
			? world.getDamageSources().playerAttack(owner)
			: world.getDamageSources().magic();
		float damage = (float) (CANNON_DAMAGE * damageMultiplier());
		Box blast = new Box(target.getBlockPos()).expand(SPLASH_RADIUS);
		List<HostileEntity> hit = world.getNonSpectatingEntities(HostileEntity.class, blast);
		for (HostileEntity mob : hit) {
			if (mob.isAlive() && mob.squaredDistanceTo(target) <= SPLASH_RADIUS * SPLASH_RADIUS) {
				damageAndCredit(world, mob, source, damage);
			}
		}

		// Visible AoE: an explosion-style burst filling the splash radius so players SEE
		// the blast, not just feel it. Scaled up a notch per tier.
		spawnBlastBurst(world, target.getX(), target.getBodyY(0.5), target.getZ());

		world.playSound(null, cx, cy, cz, SoundEvents.ENTITY_GENERIC_EXPLODE,
			SoundCategory.BLOCKS, 0.6f, 1.4f);
	}

	/**
	 * A one-shot explosion burst centred on the impact point, sized to the splash
	 * radius: a couple of explosion puffs, a cloud of large smoke filling the blast,
	 * a bright ring of crit/sweep sparks marking the edge, and lingering rising smoke.
	 * Particle counts grow with tier so upgraded cannons read as bigger hits.
	 */
	private void spawnBlastBurst(ServerWorld world, double x, double y, double z) {
		int t = getTier();
		double r = SPLASH_RADIUS;
		// Core detonation: a few explosion puffs at the centre.
		world.spawnParticles(ParticleTypes.EXPLOSION, x, y, z, 1 + t, r * 0.3, r * 0.3, r * 0.3, 0.0);
		// Body of the blast: large smoke filling roughly the splash sphere.
		world.spawnParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 22 + 10 * t, r * 0.5, r * 0.4, r * 0.5, 0.02);
		// Edge flash: a bright ring of crit sparks + a couple of sweep arcs.
		world.spawnParticles(ParticleTypes.CRIT, x, y, z, 30 + 15 * t, r * 0.6, r * 0.3, r * 0.6, 0.12);
		world.spawnParticles(ParticleTypes.SWEEP_ATTACK, x, y, z, 2 + t, r * 0.4, 0.2, r * 0.4, 0.0);
		// Lingering rising smoke after the flash.
		world.spawnParticles(ParticleTypes.SMOKE, x, y + 0.2, z, 12 + 4 * t, r * 0.4, 0.3, r * 0.4, 0.01);
	}
}
