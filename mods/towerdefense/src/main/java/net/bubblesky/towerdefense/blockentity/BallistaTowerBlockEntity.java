package net.bubblesky.towerdefense.blockentity;

import java.util.List;
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
import net.minecraft.util.math.Vec3d;

/** Long-range piercing tower: rewards placing it down a lane or funnel. */
public class BallistaTowerBlockEntity extends AbstractTowerBlockEntity {
	private static final double BASE_RANGE = 38.0;
	private static final int BASE_COOLDOWN = 70;
	private static final double DAMAGE = 12.0;
	private static final double LINE_WIDTH = 1.6;
	private static final int MAX_HITS = 5;

	public BallistaTowerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.BALLISTA_TOWER, pos, state);
	}

	@Override
	public net.bubblesky.towerdefense.tower.TowerKind kind() {
		return net.bubblesky.towerdefense.tower.TowerKind.BALLISTA;
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
		DamageSource source = owner != null
			? world.getDamageSources().playerAttack(owner)
			: world.getDamageSources().magic();
		Vec3d origin = new Vec3d(cx, cy, cz);
		Vec3d dir = target.getPos().add(0.0, target.getHeight() * 0.5, 0.0).subtract(origin).normalize();
		double range = range();
		Box scan = new Box(pos).expand(range);
		List<HostileEntity> mobs = world.getNonSpectatingEntities(HostileEntity.class, scan).stream()
			.filter(HostileEntity::isAlive)
			.sorted((a, b) -> Double.compare(a.squaredDistanceTo(cx, cy, cz), b.squaredDistanceTo(cx, cy, cz)))
			.toList();
		int hit = 0;
		for (HostileEntity mob : mobs) {
			Vec3d toMob = mob.getPos().add(0.0, mob.getHeight() * 0.5, 0.0).subtract(origin);
			double along = toMob.dotProduct(dir);
			if (along < 0.0 || along > range) {
				continue;
			}
			double offSq = toMob.subtract(dir.multiply(along)).lengthSquared();
			if (offSq <= LINE_WIDTH * LINE_WIDTH) {
				damageAndCredit(world, mob, source, (float) (DAMAGE * damageMultiplier(mob)));
				if (++hit >= MAX_HITS + getTier() / 2) {
					break;
				}
			}
		}
		double targetDistance = target.getPos().distanceTo(origin);
		Vec3d end = origin.add(dir.multiply(Math.min(range, targetDistance + 8.0)));
		world.spawnParticles(ParticleTypes.CRIT, end.x, end.y, end.z, 35 + 8 * getTier(),
			Math.abs(dir.x) * 2.0 + 0.2, 0.2, Math.abs(dir.z) * 2.0 + 0.2, 0.12);
		world.playSound(null, cx, cy, cz, SoundEvents.ITEM_CROSSBOW_SHOOT, SoundCategory.BLOCKS, 1.0f, 0.75f);
	}
}
