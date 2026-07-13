package net.bubblesky.towerdefense.blockentity;

import java.util.List;
import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.jetbrains.annotations.Nullable;

/**
 * The sharpshooter tower: very long range, slow cadence, and — unlike every other
 * tower — does NOT target the nearest enemy. It picks out the TOUGHEST hostile in
 * range (highest max health) and lines up a shot that hits hard regardless of armor
 * or HP pool: a flat base plus a percentage of the target's max health, with a
 * chance to crit. The answer to high-HP late-wave enemies and bosses that shrug off
 * the other towers' flat damage. Damage is routed through the shared
 * {@code damageAndCredit} hook so kills still pay coins and count for veterancy.
 * Tier/placer economy lives in {@link AbstractTowerBlockEntity}.
 */
public class SharpshooterTowerBlockEntity extends AbstractTowerBlockEntity {
	private static final double BASE_RANGE = 40.0;
	private static final int BASE_COOLDOWN = 70;
	private static final float FLAT_DAMAGE = 8.0f;
	private static final float MAX_HP_FRACTION = 0.20f;
	private static final double CRIT_CHANCE = 0.25;
	private static final float CRIT_MULT = 2.0f;

	public SharpshooterTowerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.SHARPSHOOTER_TOWER, pos, state);
	}

	@Override
	public net.bubblesky.towerdefense.tower.TowerKind kind() {
		return net.bubblesky.towerdefense.tower.TowerKind.SHARPSHOOTER;
	}

	@Override
	protected double baseRange() {
		return BASE_RANGE;
	}

	@Override
	protected int baseCooldown() {
		return BASE_COOLDOWN;
	}

	/** Target the toughest (highest max health) enemy in range, not the nearest. */
	@Override
	@Nullable
	protected HostileEntity findNearestHostile(ServerWorld world, BlockPos pos, double cx, double cy, double cz) {
		double range = range();
		Box box = new Box(pos).expand(range);
		List<HostileEntity> mobs = world.getNonSpectatingEntities(HostileEntity.class, box);
		HostileEntity toughest = null;
		float bestHealth = -1.0f;
		double rangeSq = range * range;
		for (HostileEntity mob : mobs) {
			if (!mob.isAlive() || mob.squaredDistanceTo(cx, cy, cz) > rangeSq) {
				continue;
			}
			if (mob.getMaxHealth() > bestHealth) {
				bestHealth = mob.getMaxHealth();
				toughest = mob;
			}
		}
		return toughest;
	}

	@Override
	protected void fire(ServerWorld world, double cx, double cy, double cz, HostileEntity target) {
		ServerPlayerEntity owner = placerPlayer(world);
		// Owner-credited damage source (player > magic) so tower kills pay coins.
		DamageSource source = owner != null
			? world.getDamageSources().playerAttack(owner)
			: world.getDamageSources().magic();

		boolean crit = world.random.nextDouble() < CRIT_CHANCE;
		float damage = (float) ((FLAT_DAMAGE + MAX_HP_FRACTION * target.getMaxHealth()) * damageMultiplier());
		if (crit) {
			damage *= CRIT_MULT;
		}
		damageAndCredit(world, target, source, damage);

		world.playSound(null, cx, cy, cz,
			crit ? SoundEvents.ITEM_CROSSBOW_HIT : SoundEvents.ITEM_CROSSBOW_SHOOT,
			SoundCategory.BLOCKS, 1.0f, crit ? 0.8f : 1.2f);
	}
}
