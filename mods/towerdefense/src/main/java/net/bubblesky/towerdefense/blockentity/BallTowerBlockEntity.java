package net.bubblesky.towerdefense.blockentity;

import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;

/**
 * The tower ball: a single, sticky one-block mini arrow turret. Unlike the tall
 * ARROW/CANNON/FROST towers it has NO stick-structure — it is placed directly onto
 * any block face (including a vertical wall) where its "tower ball arrow" embeds, and
 * from there it behaves like a compact arrow tower: short range, fast cadence, low
 * damage. Great as a cheap wall-mounted picket that chips away at the swarm.
 *
 * <p>Fire behaviour mirrors {@link ArrowTowerBlockEntity} (a gravity-compensated arrow
 * at the nearest hostile), just tuned smaller. Tier/placer economy is inherited from
 * {@link AbstractTowerBlockEntity}.
 */
public class BallTowerBlockEntity extends AbstractTowerBlockEntity {
	private static final double BASE_RANGE = 10.0;
	private static final int BASE_COOLDOWN = 20;
	private static final double ARROW_DAMAGE = 2.0;
	private static final float ARROW_SPEED = 1.6f;
	private static final float ARROW_DIVERGENCE = 2.0f;

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
		// Final arg is the WEAPON stack; a non-null EMPTY weapon throws on 1.21.6, so
		// pass a valid bow (same guard as the arrow tower).
		ArrowEntity arrow = new ArrowEntity(world, cx, cy, cz, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));

		double dx = target.getX() - cx;
		double dy = target.getBodyY(0.5) - cy;
		double dz = target.getZ() - cz;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		arrow.setVelocity(dx, dy + horizontal * 0.2, dz, ARROW_SPEED, ARROW_DIVERGENCE);
		arrow.setDamage(ARROW_DAMAGE * damageMultiplier());

		// Credit the placer so tower kills pay coins (arrow's attacker == owner).
		ServerPlayerEntity owner = placerPlayer(world);
		if (owner != null) {
			arrow.setOwner(owner);
		}

		world.spawnEntity(arrow);
		world.playSound(null, cx, cy, cz, SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.BLOCKS, 0.6f, 1.4f);
	}
}
