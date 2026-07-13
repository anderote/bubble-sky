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
 * The tower ball: a single, sticky one-block mini turret. Unlike the ARROW/CANNON/FROST
 * towers it is placed directly onto any block face (including a vertical wall). It behaves
 * like a compact arrow tower: short range, fast cadence, low damage.
 *
 * <p>Fire behaviour mirrors {@link ArrowTowerBlockEntity} — a real arrow
 * ({@link TowerBoltEntity}) that deals owner-credited damage + knockback on hit and then
 * vanishes almost immediately for zero clutter. Tier/placer economy is inherited from
 * {@link AbstractTowerBlockEntity}.
 */
public class BallTowerBlockEntity extends AbstractTowerBlockEntity {
	private static final double BASE_RANGE = 20.0;
	private static final int BASE_COOLDOWN = 20;
	private static final double SHOT_DAMAGE = 2.0;
	private static final float SHOT_SPEED = 2.0f;
	private static final float SHOT_DIVERGENCE = 2.0f;

	public BallTowerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.BALL_TOWER, pos, state);
	}

	@Override
	public net.bubblesky.towerdefense.tower.TowerKind kind() {
		return net.bubblesky.towerdefense.tower.TowerKind.BALL;
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
		// A real arrow (TowerBoltEntity): owner-credited damage + knockback on hit, then
		// it vanishes right away — no projectiles ever accumulate on the ground.
		TowerBoltEntity bolt = new TowerBoltEntity(ModEntities.TOWER_BOLT, world, cx, cy, cz);
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

		world.playSound(null, cx, cy, cz, SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.BLOCKS, 0.6f, 1.6f);
	}
}
