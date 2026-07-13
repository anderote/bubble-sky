package net.bubblesky.towerdefense.entity;

import java.util.EnumSet;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;

/**
 * Target-selection goal for a {@link TdAllyEntity}: acquires the nearest wave
 * {@link TdEnemyEntity} that the ally's current order permits it to engage (see
 * {@link TdAllyEntity#canEngage}). Keeping this custom (rather than a vanilla
 * {@code ActiveTargetGoal}) lets a HOLD ally break off and return to its post when
 * its target strays past the leash, and lets an ATTACK ally reach clear across the
 * arena. The actual approach + swing/shoot is handled by the melee/projectile goal
 * on the concrete subclass once a target is set here.
 */
public class AllyTargetGoal extends Goal {
	private final TdAllyEntity ally;

	public AllyTargetGoal(TdAllyEntity ally) {
		this.ally = ally;
		this.setControls(EnumSet.of(Control.TARGET));
	}

	@Override
	public boolean canStart() {
		LivingEntity current = ally.getTarget();
		if (current instanceof TdEnemyEntity && current.isAlive() && ally.canEngage(current)) {
			return false; // already validly engaged
		}
		return ally.findEnemyTarget() != null;
	}

	@Override
	public void start() {
		TdEnemyEntity enemy = ally.findEnemyTarget();
		if (enemy != null) {
			ally.setTarget(enemy);
		}
	}

	@Override
	public boolean shouldContinue() {
		LivingEntity target = ally.getTarget();
		if (!(target instanceof TdEnemyEntity) || !target.isAlive()) {
			return false;
		}
		return ally.canEngage(target);
	}

	@Override
	public void stop() {
		ally.setTarget(null);
	}

	@Override
	public boolean shouldRunEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		// Re-evaluate mid-fight: if the current target left our engagement envelope
		// (e.g. a HOLD ally's foe ran past the leash), swap to a closer valid one.
		LivingEntity target = ally.getTarget();
		if (target == null || !target.isAlive() || !ally.canEngage(target)) {
			TdEnemyEntity next = ally.findEnemyTarget();
			ally.setTarget(next); // may be null -> combat goal releases
		}
	}
}
