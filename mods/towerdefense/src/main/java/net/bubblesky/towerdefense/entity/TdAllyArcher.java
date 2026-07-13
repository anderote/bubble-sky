package net.bubblesky.towerdefense.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.ai.goal.ProjectileAttackGoal;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;

/**
 * The ranged ally: keeps its distance and looses arrows at the enemy target handed
 * to it by {@link AllyTargetGoal}. Mirrors {@link TdArcherEnemy} but shoots the
 * wave mobs instead of the player. Its arrow damage tracks its own
 * {@code ATTACK_DAMAGE} attribute so the roster's relative strength stays coherent.
 */
public class TdAllyArcher extends TdAllyEntity implements RangedAttackMob {
	private static final float ARROW_SPEED = 1.6f;
	private static final float ARROW_DIVERGENCE = 10.0f;
	private static final int SHOOT_INTERVAL = 25;
	private static final float SHOOT_RANGE = 14.0f;

	public TdAllyArcher(EntityType<? extends TdAllyArcher> type, World world) {
		super(type, world);
	}

	@Override
	protected void initGoals() {
		super.initGoals();
		this.goalSelector.add(2, new ProjectileAttackGoal(this, 1.0, SHOOT_INTERVAL, SHOOT_RANGE));
	}

	@Override
	public void shootAt(LivingEntity target, float pullProgress) {
		if (!(this.getWorld() instanceof ServerWorld world)) {
			return;
		}
		// Final arg is the WEAPON stack; a non-null EMPTY weapon crashes on 1.21.6
		// ("Invalid weapon firing an arrow"), so pass a valid bow.
		ArrowEntity arrow = new ArrowEntity(world, this, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
		double dx = target.getX() - this.getX();
		double dy = target.getBodyY(0.3333) - arrow.getY();
		double dz = target.getZ() - this.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		arrow.setVelocity(dx, dy + horizontal * 0.2, dz, ARROW_SPEED, ARROW_DIVERGENCE);
		arrow.setDamage(this.getAttributeValue(EntityAttributes.ATTACK_DAMAGE));
		world.spawnEntity(arrow);
		world.playSound(null, this.getX(), this.getY(), this.getZ(),
			SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.NEUTRAL, 1.0f, 1.0f);
	}
}
