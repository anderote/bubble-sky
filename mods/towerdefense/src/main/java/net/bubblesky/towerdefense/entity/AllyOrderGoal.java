package net.bubblesky.towerdefense.entity;

import java.util.EnumSet;
import net.bubblesky.towerdefense.state.TdArenaState;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * The order-driven movement fallback for a {@link TdAllyEntity}. It runs only when
 * no combat goal (melee/projectile, higher priority) is active — i.e. when the ally
 * has no live target — and steers the body according to its {@link TdAllyEntity.Order}:
 *
 * <ul>
 *   <li>{@code HOLD}  — return to the anchor if it has drifted past a small radius.</li>
 *   <li>{@code MOVE}  — path to the anchor, then settle into {@code HOLD} there.</li>
 *   <li>{@code FOLLOW} — trail the owning player, keeping a short standoff.</li>
 *   <li>{@code ATTACK} — march at the nearest enemy, or (with none in range) at the
 *       nearest spawn gate, so the squad presses the offensive.</li>
 * </ul>
 */
public class AllyOrderGoal extends Goal {
	/** Movement speed multiplier for order repositioning. */
	private static final double SPEED = 1.0;
	/** HOLD: only re-path home when this far from the anchor (blocks). */
	private static final double HOLD_SLACK = 3.0;
	/** MOVE: considered "arrived" within this distance of the anchor (blocks). */
	private static final double ARRIVE = 2.0;
	/** FOLLOW: keep at least this far from the owner; re-path beyond the outer band. */
	private static final double FOLLOW_MIN = 3.0;
	private static final double FOLLOW_MAX = 8.0;
	/** Ticks between repath attempts, so we don't spam the navigator every tick. */
	private static final int REPATH_INTERVAL = 10;

	private final TdAllyEntity ally;
	private int cooldown;

	public AllyOrderGoal(TdAllyEntity ally) {
		this.ally = ally;
		this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		return true;
	}

	@Override
	public boolean shouldContinue() {
		return true;
	}

	@Override
	public boolean shouldRunEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		cooldown = REPATH_INTERVAL;

		EntityNavigation nav = ally.getNavigation();
		switch (ally.getOrder()) {
			case HOLD -> tickHold(nav);
			case MOVE -> tickMove(nav);
			case FOLLOW -> tickFollow(nav);
			case ATTACK -> tickAttack(nav);
			default -> { }
		}
	}

	private void tickHold(EntityNavigation nav) {
		Vec3d a = ally.getAnchor();
		if (a == null) {
			return; // no post yet — just stand
		}
		if (ally.squaredDistanceTo(a.x, a.y, a.z) > HOLD_SLACK * HOLD_SLACK && nav.isIdle()) {
			nav.startMovingTo(a.x, a.y, a.z, SPEED);
		}
	}

	private void tickMove(EntityNavigation nav) {
		Vec3d a = ally.getAnchor();
		if (a == null) {
			ally.setOrder(TdAllyEntity.Order.HOLD, null, null);
			return;
		}
		if (ally.squaredDistanceTo(a.x, a.y, a.z) <= ARRIVE * ARRIVE) {
			// Arrived — dig in and defend this spot.
			ally.setOrder(TdAllyEntity.Order.HOLD, a, null);
		} else if (nav.isIdle()) {
			nav.startMovingTo(a.x, a.y, a.z, SPEED);
		}
	}

	private void tickFollow(EntityNavigation nav) {
		PlayerEntity owner = ally.resolveOwner();
		if (owner == null) {
			return;
		}
		double d2 = ally.squaredDistanceTo(owner);
		if (d2 > FOLLOW_MAX * FOLLOW_MAX && nav.isIdle()) {
			nav.startMovingTo(owner.getX(), owner.getY(), owner.getZ(), SPEED);
		} else if (d2 < FOLLOW_MIN * FOLLOW_MIN) {
			nav.stop(); // close enough; don't crowd
		}
		ally.getLookControl().lookAt(owner, 10.0f, 10.0f);
	}

	private void tickAttack(EntityNavigation nav) {
		TdEnemyEntity enemy = ally.findEnemyTarget();
		if (enemy != null) {
			if (nav.isIdle()) {
				nav.startMovingTo(enemy.getX(), enemy.getY(), enemy.getZ(), SPEED);
			}
			return;
		}
		// No enemy in sight — march toward the nearest spawn gate to meet the wave.
		BlockPos gate = nearestGate();
		if (gate != null && nav.isIdle()) {
			nav.startMovingTo(gate.getX() + 0.5, gate.getY(), gate.getZ() + 0.5, SPEED);
		}
	}

	private BlockPos nearestGate() {
		if (!(ally.getWorld() instanceof ServerWorld world)) {
			return null;
		}
		TdArenaState st = TdArenaState.get(world.getServer());
		BlockPos best = null;
		double bestSq = Double.MAX_VALUE;
		for (BlockPos sp : st.spawnPoints) {
			double d2 = ally.squaredDistanceTo(sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5);
			if (d2 < bestSq) {
				bestSq = d2;
				best = sp;
			}
		}
		return best;
	}
}
