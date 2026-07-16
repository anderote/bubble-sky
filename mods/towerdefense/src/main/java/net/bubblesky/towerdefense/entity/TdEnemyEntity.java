package net.bubblesky.towerdefense.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
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
	/** Ticks until a wall-blocked enemy re-checks for a newly-opened path (throttles
	 *  pathfinding so a horde bashing a wall doesn't hammer the pathfinder every tick).
	 *  Managed by the wave manager; transient (wave enemies are ephemeral). */
	public int repathCooldown = 0;
	/** True while this enemy has NO path to the Idol and is smashing through a wall. */
	public boolean blockedFromBase = false;
	/** Closest distance^2 to the Idol this enemy has ever reached (for stuck detection). */
	public double bestDistSq = Double.MAX_VALUE;
	/** Ticks with no progress toward the Idol; the wave manager culls an enemy that stays
	 *  stuck too long (lost underground / wedged in terrain) so the wave can finish. */
	public int stuckTicks = 0;
	/** The entity {@link #age} at which a TOWER last dealt damage to this enemy. The
	 *  {@code WarlordDirector} death hook reads this to attribute the killing blow: because a
	 *  tower stamps this the instant before applying its (possibly lethal) hit and
	 *  {@code AFTER_DEATH} fires synchronously within that same damage call, a stamp equal to
	 *  the current age means a tower landed the kill (vs a player / ally / environment). */
	public int lastTowerHitAge = Integer.MIN_VALUE;

	/** Squared distance (blocks^2) within which an enemy engages an ally/player that gets
	 *  "in the way". Kept short even though FOLLOW_RANGE is set large (so the enemy can
	 *  PATHFIND to the Idol from far) — the two must be decoupled or enemies would hunt. */
	private static final double ENGAGE_SQ = 8.0 * 8.0;

	protected TdEnemyEntity(EntityType<? extends TdEnemyEntity> type, World world) {
		super(type, world);
	}

	/**
	 * Whether this enemy is a <em>siege breaker</em>. A siege breaker does not path
	 * around walls: the wave manager bores it STRAIGHT toward the Idol, tunnelling
	 * through whatever solid block sits directly ahead on the line to the base (and
	 * digging faster than a normal enemy). Normal enemies return {@code false} and
	 * only smash a wall when the pathfinder finds no route to the Idol at all;
	 * {@link TdBarbarianSapper} overrides this to {@code true}.
	 */
	public boolean isSiegeBreaker() {
		return false;
	}

	/**
	 * Whether this enemy is a <em>flyer</em> — an airborne unit that IGNORES walls. Unlike a
	 * ground enemy (which paths around obstacles and only smashes a wall when boxed in) or a
	 * siege breaker (which tunnels straight through), a flyer routes <em>over</em> whatever the
	 * defender builds: the wave manager never makes it dig, instead letting its flight
	 * navigation ({@link net.minecraft.entity.ai.pathing.BirdNavigation}) carry it up-and-over
	 * the wall straight at the Idol — the anti-turtle unit. Normal enemies return {@code false};
	 * {@link TdGargoyle} overrides this to {@code true}.
	 *
	 * <p>A flyer is still a fully ordinary {@code td_enemy}: tagged, glow-highlighted,
	 * telemetry-tracked, escalation-scaled, and damageable by towers and players exactly like
	 * any other roster member — it simply cannot be stopped by a wall.
	 */
	public boolean isFlyer() {
		return false;
	}

	@Override
	protected void initGoals() {
		this.goalSelector.add(6, new WanderAroundGoal(this, 1.0));
		this.goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		this.goalSelector.add(8, new LookAroundGoal(this));

		this.targetSelector.add(1, new RevengeGoal(this));
		this.targetSelector.add(2, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
	}

	/**
	 * Re-arm a wave enemy to fight hired allied soldiers it meets on the march. The
	 * wave manager strips every default goal then steers the body straight at the
	 * Idol; this adds back a melee swing plus a target goal for {@link TdAllyEntity}
	 * soldiers, so an enemy that passes a soldier stops to cut it down and — once the
	 * fight ends and its navigation goes idle — the manager resumes steering it to the
	 * Idol. Exposed here because the goal selectors are {@code protected}. (The archer
	 * additionally re-arms its ranged goal via {@code addWaveCombatGoals()}, which will
	 * loose arrows at whichever target — soldier or player — it acquires.)
	 */
	public void addAllyCombatGoals() {
		this.goalSelector.add(3, new MeleeAttackGoal(this, 1.2, false));
		// Prefer hired soldiers, but also cut down a PLAYER who gets in the way. A short
		// distance predicate (ENGAGE_SQ) caps engagement to ~8 blocks even though
		// FOLLOW_RANGE is large — the big follow range is what lets the enemy PATHFIND to
		// the Idol from far away (fixing the "can't path / tunnels toward the Idol" bug),
		// so the two are decoupled here to keep the Idol the primary objective.
		this.targetSelector.add(2, new ActiveTargetGoal<>(this, TdAllyEntity.class, 10, true, false,
			(entity, world) -> entity.squaredDistanceTo(this) <= ENGAGE_SQ));
		this.targetSelector.add(3, new ActiveTargetGoal<>(this, PlayerEntity.class, 10, true, false,
			(entity, world) -> entity.squaredDistanceTo(this) <= ENGAGE_SQ));
	}
}
