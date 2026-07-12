package net.bubblesky.towerdefense.blockentity;

import java.util.List;
import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
	private static final double BASE_RANGE = 14.0;
	private static final int BASE_COOLDOWN = 60;
	private static final double CANNON_DAMAGE = 8.0;
	private static final double SPLASH_RADIUS = 3.0;
	private static final float LOB_SPEED = 1.1f;

	public CannonTowerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.CANNON_TOWER, pos, state);
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
				mob.damage(world, source, damage);
			}
		}

		world.playSound(null, cx, cy, cz, SoundEvents.ENTITY_GENERIC_EXPLODE,
			SoundCategory.BLOCKS, 0.6f, 1.4f);
	}
}
