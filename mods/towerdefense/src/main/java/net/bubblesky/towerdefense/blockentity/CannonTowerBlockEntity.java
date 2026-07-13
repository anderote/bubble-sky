package net.bubblesky.towerdefense.blockentity;

import java.util.List;
import net.bubblesky.towerdefense.entity.ShellEntity;
import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
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
	/** Muzzle velocity of the artillery shell — faster and flatter than the old snowball lob. */
	private static final float SHELL_SPEED = 1.6f;

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
		// The ARTILLERY SHELL: a fast, dark, whistling round that bursts in a cosmetic
		// explosion when it lands (see ShellEntity). It carries NO damage itself — the tower
		// still owns all the splash below, exactly as the old snowball build did.
		ServerPlayerEntity owner = placerPlayer(world);
		ShellEntity shell = new ShellEntity(world, cx, cy, cz);
		if (owner != null) {
			shell.setOwner(owner);
		}
		double dx = target.getX() - cx;
		double dy = target.getBodyY(0.5) - cy;
		double dz = target.getZ() - cz;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		// A flat, fast shot with only a slight lofted arc so the round reads as artillery,
		// not a lobbed ball, yet still comes down around the target point.
		shell.setVelocity(dx, dy + horizontal * 0.1, dz, SHELL_SPEED, 1.0f);
		world.spawnEntity(shell);

		// Muzzle bang at the cannon the instant it fires: a flash + smoke puff at the barrel
		// and a firing report, distinct from the shell's impact boom downrange.
		spawnMuzzleFlash(world, cx, cy, cz);
		world.playSound(null, cx, cy, cz, SoundEvents.ENTITY_GENERIC_EXPLODE,
			SoundCategory.BLOCKS, 0.5f, 1.5f);

		// Splash damage at the target: every enemy in the blast radius takes the hit.
		// (Unchanged from the snowball build — same source, damage, radius, and timing.)
		DamageSource source = owner != null
			? world.getDamageSources().playerAttack(owner)
			: world.getDamageSources().magic();
		Box blast = new Box(target.getBlockPos()).expand(SPLASH_RADIUS);
		List<HostileEntity> hit = world.getNonSpectatingEntities(HostileEntity.class, blast);
		for (HostileEntity mob : hit) {
			if (mob.isAlive() && mob.squaredDistanceTo(target) <= SPLASH_RADIUS * SPLASH_RADIUS) {
				damageAndCredit(world, mob, source,
					(float) (CANNON_DAMAGE * damageMultiplier(mob)));
			}
		}
	}

	/**
	 * A brief muzzle-flash at the cannon's barrel the instant it fires: a bright flash, a
	 * puff of large smoke, and a spray of firing sparks. Purely cosmetic and scaled a notch
	 * per tier so upgraded cannons kick harder.
	 */
	private void spawnMuzzleFlash(ServerWorld world, double x, double y, double z) {
		int t = getTier();
		world.spawnParticles(ParticleTypes.FLASH, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
		world.spawnParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 8 + 3 * t, 0.25, 0.25, 0.25, 0.02);
		world.spawnParticles(ParticleTypes.CRIT, x, y, z, 12 + 4 * t, 0.3, 0.3, 0.3, 0.2);
	}
}
