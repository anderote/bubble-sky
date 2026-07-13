package net.bubblesky.towerdefense.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.World;

/**
 * A melee ally: the footman (and the sturdier knight, which reuses this class —
 * their differing hp / attack / speed come from the per-{@link EntityType} default
 * attributes in {@code ModEntities}). Adds a {@link MeleeAttackGoal} so that, once
 * {@link AllyTargetGoal} hands it an enemy target, it walks up and swings.
 */
public class TdFootman extends TdAllyEntity {
	public TdFootman(EntityType<? extends TdFootman> type, World world) {
		super(type, world);
	}

	@Override
	protected void initGoals() {
		super.initGoals();
		this.goalSelector.add(2, new MeleeAttackGoal(this, 1.1, true));
	}
}
