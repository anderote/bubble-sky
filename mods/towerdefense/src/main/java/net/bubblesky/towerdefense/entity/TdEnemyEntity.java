package net.bubblesky.towerdefense.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.WanderAroundGoal;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;

/**
 * Shared base for the custom Tower Defense "army" enemies — original biped mobs
 * that replace the vanilla-hostile stand-ins the wave manager used to spawn.
 *
 * <p>All roster members extend {@link HostileEntity} (so the existing
 * {@code arrow_tower}, which targets {@code HostileEntity}, hits them for free)
 * and share a common default AI: wander, look at players, and target players via
 * an {@link ActiveTargetGoal}. Concrete subclasses add their attack goal
 * ({@link TdMeleeEnemy} a melee swing, {@link TdArcherEnemy} a ranged shot).
 *
 * <p>These default goals only matter for "loose" spawns (e.g. a spawn egg or a
 * creative test). During a managed wave the {@code WaveManager} calls
 * {@code clearGoalsAndTasks()} and steers each enemy straight at the base via its
 * navigation, exactly as it did for the vanilla stand-ins.
 */
public abstract class TdEnemyEntity extends HostileEntity {
	protected TdEnemyEntity(EntityType<? extends TdEnemyEntity> type, World world) {
		super(type, world);
	}

	@Override
	protected void initGoals() {
		this.goalSelector.add(6, new WanderAroundGoal(this, 1.0));
		this.goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		this.goalSelector.add(8, new LookAroundGoal(this));

		this.targetSelector.add(1, new RevengeGoal(this));
		this.targetSelector.add(2, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
	}
}
