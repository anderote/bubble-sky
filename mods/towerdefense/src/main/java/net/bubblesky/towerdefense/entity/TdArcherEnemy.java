package net.bubblesky.towerdefense.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.ProjectileAttackGoal;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;

/**
 * The ranged roster enemy. Implements {@link RangedAttackMob} so a
 * {@link ProjectileAttackGoal} can drive it: it keeps its distance and looses
 * arrows at its target. Its arrow damage is fixed (the attack-damage attribute
 * is only used for the base-arrival hit); everything else scales per wave.
 */
public class TdArcherEnemy extends TdEnemyEntity implements RangedAttackMob {
	/** Damage each loosed arrow deals on impact. */
	private static final double ARROW_DAMAGE = 4.0;
	/** Muzzle velocity for the arrow. */
	private static final float ARROW_SPEED = 1.6f;
	/** Arrow spread (higher = less accurate); keeps the archer non-oppressive. */
	private static final float ARROW_DIVERGENCE = 8.0f;
	/** Attack interval (ticks) and effective range (blocks) for the shoot goal. */
	private static final int SHOOT_INTERVAL = 30;
	private static final float SHOOT_RANGE = 15.0f;

	public TdArcherEnemy(EntityType<? extends TdArcherEnemy> type, World world) {
		super(type, world);
	}

	@Override
	protected void initGoals() {
		super.initGoals();
		this.goalSelector.add(2, new ProjectileAttackGoal(this, 1.0, SHOOT_INTERVAL, SHOOT_RANGE));
	}

	/**
	 * Re-arm the archer's ranged AI after the wave manager has stripped its goals.
	 * The manager still steers the body toward the base; when a player wanders into
	 * range this goal takes over the navigation to hold distance and shoot, then
	 * releases it so the march resumes. Called from {@code WaveManager} because the
	 * goal selectors are {@code protected}.
	 */
	public void addWaveCombatGoals() {
		this.goalSelector.add(1, new ProjectileAttackGoal(this, 1.0, SHOOT_INTERVAL, SHOOT_RANGE));
		this.targetSelector.add(1, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
	}

	@Override
	public void shootAt(LivingEntity target, float pullProgress) {
		if (!(this.getWorld() instanceof ServerWorld world)) {
			return;
		}
		// The final arg is the WEAPON stack: on 1.21.6 an EMPTY (but non-null) weapon
		// throws "Invalid weapon firing an arrow" and crashes the server, so pass a
		// valid bow.
		ArrowEntity arrow = new ArrowEntity(world, this, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
		double dx = target.getX() - this.getX();
		double dy = target.getBodyY(0.3333) - arrow.getY();
		double dz = target.getZ() - this.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		// Arc slightly upward so distant shots drop onto the target.
		arrow.setVelocity(dx, dy + horizontal * 0.2, dz, ARROW_SPEED, ARROW_DIVERGENCE);
		arrow.setDamage(ARROW_DAMAGE);
		world.spawnEntity(arrow);
		world.playSound(null, this.getX(), this.getY(), this.getZ(),
			SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.HOSTILE, 1.0f, 1.0f);
	}
}
