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
 * The arrow tower: every cooldown it fires a fast "musket ball" at the nearest hostile.
 * The projectile is a COSMETIC snowball — it despawns on impact, so the arena never fills
 * with lingering, pickup-able arrows — and the damage is dealt directly to the target
 * (owner-credited so kills pay coins), exactly like the cannon/frost towers. The fast,
 * cheap, reliable single-target tower. Tier/placer economy lives in {@link AbstractTowerBlockEntity}.
 */
public class ArrowTowerBlockEntity extends AbstractTowerBlockEntity {
	private static final double BASE_RANGE = 32.0;
	private static final int BASE_COOLDOWN = 30;
	private static final double SHOT_DAMAGE = 4.0;
	/** Fast + flat (no lob) so it reads as a quick bullet, unlike the cannon's slow arc. */
	private static final float BALL_SPEED = 2.0f;
	private static final float BALL_DIVERGENCE = 1.0f;

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
		// Cosmetic "musket ball": a fast snowball flung at the target purely for the visual.
		// It despawns on impact, so no projectiles ever accumulate on the ground.
		SnowballEntity ball = new SnowballEntity(world, cx, cy, cz, new ItemStack(Items.SNOWBALL));
		if (owner != null) {
			ball.setOwner(owner);
		}
		double dx = target.getX() - cx;
		double dy = target.getBodyY(0.5) - cy;
		double dz = target.getZ() - cz;
		ball.setVelocity(dx, dy, dz, BALL_SPEED, BALL_DIVERGENCE);
		world.spawnEntity(ball);

		// Damage is dealt directly to the target (owner-credited so kills pay coins).
		DamageSource source = owner != null
			? world.getDamageSources().playerAttack(owner)
			: world.getDamageSources().magic();
		target.damage(world, source, (float) (SHOT_DAMAGE * damageMultiplier()));

		world.playSound(null, cx, cy, cz, SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.BLOCKS, 1.0f, 1.4f);
	}
}
