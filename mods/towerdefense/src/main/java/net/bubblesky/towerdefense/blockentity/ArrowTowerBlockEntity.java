package net.bubblesky.towerdefense.blockentity;

import net.bubblesky.towerdefense.entity.TowerBoltEntity;
import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.bubblesky.towerdefense.registry.ModEntities;
import net.minecraft.block.BlockState;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;

/**
 * The arrow tower: every cooldown it fires a real ARROW at the nearest hostile.
 * The projectile is a {@link TowerBoltEntity} — it looks like a normal fired arrow
 * (unlike the frost/cannon towers' snowballs), deals owner-credited damage + a
 * knockback shove on impact (so kills pay coins), and then vanishes almost
 * immediately so the arena never fills with lingering, pickup-able arrows. The fast,
 * cheap, reliable single-target tower. Tier/placer economy lives in {@link AbstractTowerBlockEntity}.
 */
public class ArrowTowerBlockEntity extends AbstractTowerBlockEntity {
	private static final double BASE_RANGE = 32.0;
	private static final int BASE_COOLDOWN = 30;
	private static final double SHOT_DAMAGE = 4.0;
	/** Fast + flat (only a small gravity-compensating lift) so it reads as a quick shot. */
	private static final float SHOT_SPEED = 2.0f;
	private static final float SHOT_DIVERGENCE = 1.0f;

	public ArrowTowerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.ARROW_TOWER, pos, state);
	}

	@Override
	public net.bubblesky.towerdefense.tower.TowerKind kind() {
		return net.bubblesky.towerdefense.tower.TowerKind.ARROW;
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
		// A real arrow (TowerBoltEntity): it deals owner-credited damage + knockback on
		// hit (so kills pay coins) and then vanishes right away — no clutter on the ground.
		TowerBoltEntity bolt = new TowerBoltEntity(ModEntities.TOWER_BOLT, world, cx, cy, cz);
		bolt.setTowerPos(getPos());
		if (owner != null) {
			bolt.setOwner(owner);
		}
		bolt.setDamage(SHOT_DAMAGE * damageMultiplier());
		double dx = target.getX() - cx;
		double dy = target.getBodyY(0.5) - cy;
		double dz = target.getZ() - cz;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		// Small upward lift compensates the arrow's gravity so it reaches the target.
		bolt.setVelocity(dx, dy + horizontal * 0.2, dz, SHOT_SPEED, SHOT_DIVERGENCE);
		world.spawnEntity(bolt);

		world.playSound(null, cx, cy, cz, SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.BLOCKS, 1.0f, 1.4f);
	}
}
