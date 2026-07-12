package net.bubblesky.towerdefense.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.World;

/**
 * A melee roster enemy: goblin skirmisher, footman, man-at-arms, undead soldier,
 * or heavy knight. One class backs all of them; their differing hp / attack /
 * speed come from the per-{@link EntityType} default attributes registered in
 * {@code ModEntities}. The only behavioural difference from the base is a
 * {@link MeleeAttackGoal} so a loose spawn will walk up and hit its target.
 */
public class TdMeleeEnemy extends TdEnemyEntity {
	public TdMeleeEnemy(EntityType<? extends TdMeleeEnemy> type, World world) {
		super(type, world);
	}

	@Override
	protected void initGoals() {
		super.initGoals();
		this.goalSelector.add(2, new MeleeAttackGoal(this, 1.0, false));
	}
}
