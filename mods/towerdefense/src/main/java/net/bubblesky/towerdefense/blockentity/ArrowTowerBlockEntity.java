package net.bubblesky.towerdefense.blockentity;

import java.util.List;
import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

/**
 * The brains of the arrow tower. Every {@link #COOLDOWN_TICKS} ticks it scans a
 * {@link #RANGE}-block radius for the nearest living hostile mob and fires an
 * arrow at it, leading slightly upward so the shot arcs onto the target.
 */
public class ArrowTowerBlockEntity extends BlockEntity {
	/** Search / fire radius in blocks. */
	private static final double RANGE = 16.0;
	/** Ticks between shots (20 ticks = 1 second). */
	private static final int COOLDOWN_TICKS = 30;
	/** Arrow impact damage. */
	private static final double ARROW_DAMAGE = 4.0;
	/** Muzzle velocity passed to setVelocity. */
	private static final float ARROW_SPEED = 1.6f;
	/** Lower = more accurate. */
	private static final float ARROW_DIVERGENCE = 1.0f;

	private int cooldown = 0;

	public ArrowTowerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.ARROW_TOWER, pos, state);
	}

	/** Server-side ticker wired up in {@code ArrowTowerBlock#getTicker}. */
	public static void tick(World world, BlockPos pos, BlockState state, ArrowTowerBlockEntity be) {
		if (!(world instanceof ServerWorld serverWorld)) {
			return;
		}
		if (be.cooldown > 0) {
			be.cooldown--;
			return;
		}

		double cx = pos.getX() + 0.5;
		double cy = pos.getY() + 1.0;
		double cz = pos.getZ() + 0.5;

		HostileEntity target = findNearestHostile(serverWorld, pos, cx, cy, cz);
		if (target == null) {
			return;
		}

		shootArrow(serverWorld, cx, cy, cz, target);
		be.cooldown = COOLDOWN_TICKS;
	}

	private static HostileEntity findNearestHostile(ServerWorld world, BlockPos pos,
			double cx, double cy, double cz) {
		Box box = new Box(pos).expand(RANGE);
		List<HostileEntity> mobs = world.getNonSpectatingEntities(HostileEntity.class, box);
		HostileEntity nearest = null;
		double bestSq = RANGE * RANGE;
		for (HostileEntity mob : mobs) {
			if (!mob.isAlive()) {
				continue;
			}
			double distSq = mob.squaredDistanceTo(cx, cy, cz);
			if (distSq <= bestSq) {
				bestSq = distSq;
				nearest = mob;
			}
		}
		return nearest;
	}

	private static void shootArrow(ServerWorld world, double cx, double cy, double cz, HostileEntity target) {
		ArrowEntity arrow = new ArrowEntity(world, cx, cy, cz, new ItemStack(Items.ARROW), ItemStack.EMPTY);

		double dx = target.getX() - cx;
		double dy = target.getBodyY(0.5) - cy;
		double dz = target.getZ() - cz;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		// Compensate for gravity so the arrow arcs onto distant targets.
		arrow.setVelocity(dx, dy + horizontal * 0.2, dz, ARROW_SPEED, ARROW_DIVERGENCE);
		arrow.setDamage(ARROW_DAMAGE);

		world.spawnEntity(arrow);
		world.playSound(null, cx, cy, cz, SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.BLOCKS, 1.0f, 1.0f);
	}
}
