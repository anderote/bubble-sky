package net.bubblesky.towerdefense.blockentity;

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

/**
 * The tower ball: a single, sticky one-block mini turret. Unlike the ARROW/CANNON/FROST
 * towers it is placed directly onto any block face (including a vertical wall). It behaves
 * like a compact arrow tower: short range, fast cadence, low damage.
 *
 * <p>Fire behaviour mirrors {@link ArrowTowerBlockEntity} — a fast cosmetic "musket ball"
 * (snowball) that despawns on impact for zero clutter, with damage applied directly to the
 * target (owner-credited). Tier/placer economy is inherited from {@link AbstractTowerBlockEntity}.
 */
public class BallTowerBlockEntity extends AbstractTowerBlockEntity {
	private static final double BASE_RANGE = 20.0;
	private static final int BASE_COOLDOWN = 20;
	private static final double SHOT_DAMAGE = 2.0;
	private static final float BALL_SPEED = 2.0f;
	private static final float BALL_DIVERGENCE = 2.0f;

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
		// Cosmetic musket ball (despawns on impact — no clutter); damage dealt directly.
		SnowballEntity ball = new SnowballEntity(world, cx, cy, cz, new ItemStack(Items.SNOWBALL));
		if (owner != null) {
			ball.setOwner(owner);
		}
		double dx = target.getX() - cx;
		double dy = target.getBodyY(0.5) - cy;
		double dz = target.getZ() - cz;
		ball.setVelocity(dx, dy, dz, BALL_SPEED, BALL_DIVERGENCE);
		world.spawnEntity(ball);

		DamageSource source = owner != null
			? world.getDamageSources().playerAttack(owner)
			: world.getDamageSources().magic();
		target.damage(world, source, (float) (SHOT_DAMAGE * damageMultiplier()));

		world.playSound(null, cx, cy, cz, SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.BLOCKS, 0.6f, 1.6f);
	}
}
