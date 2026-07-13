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
 * The arrow tower: every cooldown it fires an arrow at the nearest hostile mob,
 * arcing slightly upward to compensate for gravity. The fast, cheap, reliable
 * single-target tower. Tier/placer economy lives in {@link AbstractTowerBlockEntity}.
 */
public class ArrowTowerBlockEntity extends AbstractTowerBlockEntity {
	private static final double BASE_RANGE = 32.0;
	private static final int BASE_COOLDOWN = 30;
	private static final double ARROW_DAMAGE = 4.0;
	private static final float ARROW_SPEED = 1.6f;
	private static final float ARROW_DIVERGENCE = 1.0f;

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
		// Final arg is the WEAPON stack; a non-null EMPTY weapon throws "Invalid weapon
		// firing an arrow" and crashes the server on 1.21.6, so pass a valid bow.
		ArrowEntity arrow = new ArrowEntity(world, cx, cy, cz, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));

		double dx = target.getX() - cx;
		double dy = target.getBodyY(0.5) - cy;
		double dz = target.getZ() - cz;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		arrow.setVelocity(dx, dy + horizontal * 0.2, dz, ARROW_SPEED, ARROW_DIVERGENCE);
		arrow.setDamage(ARROW_DAMAGE * damageMultiplier());
		// Non-collectible so the arena doesn't flood with pickup arrows.
		arrow.pickupType = net.minecraft.entity.projectile.PersistentProjectileEntity.PickupPermission.DISALLOWED;

		// Credit the placer so tower kills pay coins (arrow's attacker == owner).
		ServerPlayerEntity owner = placerPlayer(world);
		if (owner != null) {
			arrow.setOwner(owner);
		}

		world.spawnEntity(arrow);
		world.playSound(null, cx, cy, cz, SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.BLOCKS, 1.0f, 1.0f);
	}
}
