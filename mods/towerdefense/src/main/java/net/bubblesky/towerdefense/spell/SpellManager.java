package net.bubblesky.towerdefense.spell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity;
import net.bubblesky.towerdefense.state.TdArenaState;
import net.bubblesky.towerdefense.tower.TowerStructure;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * The lightweight server-tick engine behind the SPELL effects that outlive their cast:
 * temporary SUMMONS (Ranger wolves, Warlord squads), Ranger SNARE traps, and the Engineer's
 * temporary DEPLOYED TURRETS. All are transient by design — a life-of-the-fight resource, not
 * a persisted structure — so this holds them in plain in-memory lists and sweeps them once per
 * server tick. A restart simply forgets them (summons revert to ordinary despawnable mobs;
 * traps vanish; a leftover turret block is simply orphaned and can be sold like any tower),
 * which is the intended, cost-free behavior for ephemeral spell state.
 *
 * <h2>Summons</h2>
 * A summoned entity is registered via {@link #addSummon} with a fixed lifetime
 * ({@link #SUMMON_LIFETIME_TICKS}); when its deadline passes (or it dies on its own) it is
 * discarded with a puff of particles. This is what makes "despawn after N sec" work without
 * baking a timer into every entity type.
 *
 * <h2>Traps</h2>
 * A trap is a {@code (world, pos, caster)} record placed by {@link #addTrap}. Each tick every
 * live trap scans for the nearest enemy within {@link #TRAP_TRIGGER_RADIUS}; the first one to
 * step in is ROOTED (heavy Slowness) and takes {@link #TRAP_DAMAGE} caster-credited damage,
 * and the trap springs (single use). Untriggered traps expire after {@link #TRAP_LIFETIME_TICKS}.
 *
 * <h2>Corpses (Necromancer fuel)</h2>
 * Every {@code td_enemy} death drops a transient CORPSE marker via {@link #recordCorpse}
 * (called from the enemy-death hook in {@code ProgressEvents}). A corpse is nothing but a
 * {@code (world, pos)} point with a short {@link #CORPSE_LIFETIME_TICKS} deadline; it is
 * swept away when it expires. The Necromancer's {@code RAISE_DEAD} spell reanimates the
 * nearest un-consumed corpses via {@link #consumeCorpses}, which returns their positions
 * and removes them so no corpse is ever raised twice.
 *
 * <h2>Deployed turrets (Engineer)</h2>
 * The Engineer's {@code DEPLOY_TURRET} spell places a real, caster-owned tower block, then
 * registers it via {@link #addTempTurret} with a rank-scaled lifetime. When the deadline
 * passes the turret is torn down exactly as if it had been sold — {@link TowerStructure#clear}
 * removes the block and {@link TdArenaState#removeTower} de-registers it — with a poof of
 * particles. This is what keeps the deployable a strong-but-fleeting resource instead of a
 * free permanent tower that would bypass the gold economy. Teardown is defensive: if the
 * turret was already sold or destroyed (no tower block-entity remains at the recorded cell)
 * the record is simply dropped, so expiry can never clear an unrelated block.
 */
public final class SpellManager {

	private SpellManager() {
	}

	// ---- tuning ------------------------------------------------------------
	/** How long a summoned wolf/ally lives before it is dismissed (ticks; 20/sec). */
	public static final int SUMMON_LIFETIME_TICKS = 400;
	/**
	 * Lifetime for the Warlord's summoned SKELETON squad: 5 minutes (300s × 20t/s). These
	 * raised bowmen are meant to hold a line for a whole wave, so they far outlast the
	 * fleeting 20-second wolf/footman summons.
	 */
	public static final int SKELETON_SUMMON_LIFETIME_TICKS = 6000;
	/** How long an unsprung trap persists before it expires (ticks). */
	public static final int TRAP_LIFETIME_TICKS = 600;
	/** How close an enemy must come to a trap to trigger it (blocks). */
	public static final double TRAP_TRIGGER_RADIUS = 2.5;
	/** Damage a sprung trap deals to the enemy that triggers it (caster-credited). */
	public static final float TRAP_DAMAGE = 6.0f;
	/** How long (ticks) a sprung trap roots its victim with strong Slowness. */
	public static final int TRAP_ROOT_TICKS = 80;
	/**
	 * How long (ticks; 20/sec) a slain enemy's CORPSE lingers as raisable fuel for the
	 * Necromancer's {@code RAISE_DEAD} before it rots away — roughly 10 seconds.
	 */
	public static final int CORPSE_LIFETIME_TICKS = 200;
	/**
	 * Baseline lifetime (ticks; 20/sec) of an Engineer's {@code DEPLOY_TURRET} deployable before
	 * it self-dismantles — 20 seconds at rank 0. {@code DEPLOY_TURRET} extends this per allocated
	 * rank, so the deployable is a strong but fleeting resource rather than a free permanent tower.
	 */
	public static final int TEMP_TURRET_BASE_TICKS = 400;

	// ---- state -------------------------------------------------------------
	/** Monotonic tick counter, advanced once per server tick; deadlines are measured against it. */
	private static long tick;
	private static final List<TimedSummon> SUMMONS = new ArrayList<>();
	private static final List<Trap> TRAPS = new ArrayList<>();
	private static final List<Corpse> CORPSES = new ArrayList<>();
	private static final List<TempTurret> TEMP_TURRETS = new ArrayList<>();

	/** A summoned entity plus the tick at which it should be dismissed. */
	private record TimedSummon(Entity entity, long deadline) {
	}

	/**
	 * A placed snare: where it is, who owns it (for damage credit), the caster's allocated
	 * {@code trap} rank (so its sprung damage/root scale with investment), and when it expires.
	 */
	private record Trap(ServerWorld world, BlockPos pos, UUID caster, int rank, long deadline) {
	}

	/** Snare Trap: +15% sprung damage per allocated rank. */
	private static final float TRAP_DAMAGE_PER_RANK = 0.15f;
	/** Snare Trap: +0.5s (10 ticks) of root per allocated rank. */
	private static final int TRAP_ROOT_PER_RANK = 10;

	/** A fresh enemy corpse: where it fell, in which world, and when it rots away. */
	private record Corpse(ServerWorld world, Vec3d pos, long deadline) {
	}

	/**
	 * A temporary Engineer turret: the tower block-entity's cell, its world, and the tick at
	 * which it should self-dismantle. Holds no owner/tier — the placed tower block already
	 * carries all of that; this record only schedules its teardown.
	 */
	private record TempTurret(ServerWorld world, BlockPos pos, long deadline) {
	}

	/** Register the once-per-tick sweep. Call once from mod init. */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tick++;
			sweepSummons();
			sweepTraps();
			sweepCorpses();
			sweepTempTurrets();
		});
	}

	// ---- summons -----------------------------------------------------------
	/** Register {@code entity} to be dismissed after {@link #SUMMON_LIFETIME_TICKS} ticks. */
	public static void addSummon(Entity entity) {
		addSummon(entity, SUMMON_LIFETIME_TICKS);
	}

	/**
	 * Register {@code entity} to be dismissed after a caller-chosen {@code lifetimeTicks}.
	 * Lets longer-lived summons (e.g. the Warlord's 5-minute skeleton squad via
	 * {@link #SKELETON_SUMMON_LIFETIME_TICKS}) coexist with the default short-lived ones
	 * without touching the shared {@link #SUMMON_LIFETIME_TICKS} deadline.
	 */
	public static void addSummon(Entity entity, int lifetimeTicks) {
		SUMMONS.add(new TimedSummon(entity, tick + lifetimeTicks));
	}

	private static void sweepSummons() {
		Iterator<TimedSummon> it = SUMMONS.iterator();
		while (it.hasNext()) {
			TimedSummon s = it.next();
			Entity e = s.entity();
			if (e == null || e.isRemoved() || !e.isAlive()) {
				it.remove();
				continue;
			}
			if (tick >= s.deadline()) {
				if (e.getWorld() instanceof ServerWorld sw) {
					sw.spawnParticles(ParticleTypes.CLOUD, e.getX(), e.getY() + 0.5, e.getZ(),
						12, 0.3, 0.5, 0.3, 0.02);
				}
				e.discard();
				it.remove();
			}
		}
	}

	// ---- traps -------------------------------------------------------------
	/** Place a single-use snare at {@code pos}, owned by {@code caster} for damage credit (rank 0). */
	public static void addTrap(ServerWorld world, BlockPos pos, UUID caster) {
		addTrap(world, pos, caster, 0);
	}

	/**
	 * Place a single-use snare at {@code pos}, owned by {@code caster}, whose sprung damage/root
	 * scale with the caster's allocated {@code trap} {@code rank}. Rank 0 reproduces the baseline.
	 */
	public static void addTrap(ServerWorld world, BlockPos pos, UUID caster, int rank) {
		TRAPS.add(new Trap(world, pos, caster, Math.max(0, rank), tick + TRAP_LIFETIME_TICKS));
	}

	private static void sweepTraps() {
		Iterator<Trap> it = TRAPS.iterator();
		while (it.hasNext()) {
			Trap trap = it.next();
			ServerWorld world = trap.world();
			if (world == null) {
				it.remove();
				continue;
			}
			if (tick >= trap.deadline()) {
				it.remove();
				continue;
			}
			Vec3d center = Vec3d.ofCenter(trap.pos());
			HostileEntity victim = SpellType.nearestEnemy(world, center, TRAP_TRIGGER_RADIUS, null);
			if (victim == null) {
				continue;
			}
			// Spring: root + caster-credited damage (both rank-scaled), then consume the trap.
			int rootTicks = TRAP_ROOT_TICKS + TRAP_ROOT_PER_RANK * trap.rank();
			float damage = TRAP_DAMAGE * (1.0f + TRAP_DAMAGE_PER_RANK * trap.rank());
			victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, rootTicks, 4));
			victim.damage(world, damageSource(world, trap.caster()), damage);
			world.spawnParticles(ParticleTypes.CRIT, center.x, center.y + 0.3, center.z, 20, 0.4, 0.2, 0.4, 0.1);
			world.playSound(null, trap.pos(), SoundEvents.BLOCK_TRIPWIRE_CLICK_OFF, SoundCategory.PLAYERS, 0.8f, 0.8f);
			it.remove();
		}
	}

	// ---- corpses -----------------------------------------------------------
	/**
	 * Record a fresh corpse at {@code pos} in {@code world} — called from the enemy-death
	 * hook for every slain {@code td_enemy}. The corpse becomes raisable fuel for
	 * {@code RAISE_DEAD} until it rots after {@link #CORPSE_LIFETIME_TICKS} ticks.
	 */
	public static void recordCorpse(ServerWorld world, Vec3d pos) {
		CORPSES.add(new Corpse(world, pos, tick + CORPSE_LIFETIME_TICKS));
	}

	private static void sweepCorpses() {
		Iterator<Corpse> it = CORPSES.iterator();
		while (it.hasNext()) {
			if (tick >= it.next().deadline()) {
				it.remove();
			}
		}
	}

	/**
	 * Consume (remove and return the positions of) up to {@code max} of the nearest un-consumed
	 * corpses in {@code world} within {@code radius} of {@code center}, closest first. Each
	 * returned corpse is removed from the pool so it can never be raised twice. Returns an empty
	 * list when no corpse is in range — the caller ({@code RAISE_DEAD}) then falls back to a
	 * single feet-of-the-caster reanimation.
	 */
	public static List<Vec3d> consumeCorpses(ServerWorld world, Vec3d center, double radius, int max) {
		List<Vec3d> raised = new ArrayList<>();
		if (max <= 0) {
			return raised;
		}
		double r2 = radius * radius;
		List<Corpse> inRange = new ArrayList<>();
		for (Corpse c : CORPSES) {
			if (c.world() == world && c.pos().squaredDistanceTo(center) <= r2) {
				inRange.add(c);
			}
		}
		inRange.sort(Comparator.comparingDouble(c -> c.pos().squaredDistanceTo(center)));
		for (int i = 0; i < inRange.size() && raised.size() < max; i++) {
			Corpse c = inRange.get(i);
			raised.add(c.pos());
			CORPSES.remove(c);
		}
		return raised;
	}

	// ---- deployed turrets --------------------------------------------------
	/**
	 * Schedule the already-placed tower block at {@code pos} in {@code world} to self-dismantle
	 * after {@code lifetimeTicks}. The caller ({@code DEPLOY_TURRET}) builds and configures the
	 * turret (owner, tier) via {@link TowerStructure#build}; this only registers its teardown so
	 * the deployable expires instead of standing forever. A non-positive lifetime is clamped to a
	 * single tick so a mis-tuned caller can never register a turret that never expires.
	 */
	public static void addTempTurret(ServerWorld world, BlockPos pos, int lifetimeTicks) {
		TEMP_TURRETS.add(new TempTurret(world, pos.toImmutable(), tick + Math.max(1, lifetimeTicks)));
	}

	/**
	 * Tear down every deployed turret whose deadline has passed (mirrors {@link #sweepSummons}).
	 * On expiry the turret is removed exactly as a manual sell would remove it — the block is
	 * cleared via {@link TowerStructure#clear} and de-registered via {@link TdArenaState#removeTower}
	 * — leaving no ghost block-entity or stale arena entry. The teardown is defensive: if the
	 * recorded cell no longer holds a tower block-entity (the turret was sold or destroyed first),
	 * the record is dropped without touching the world, so expiry can never clear an unrelated block.
	 */
	private static void sweepTempTurrets() {
		Iterator<TempTurret> it = TEMP_TURRETS.iterator();
		while (it.hasNext()) {
			TempTurret t = it.next();
			ServerWorld world = t.world();
			if (world == null) {
				it.remove();
				continue;
			}
			if (tick < t.deadline()) {
				continue;
			}
			it.remove();
			// Already sold/destroyed? No tower block-entity left — just forget the record.
			if (!(world.getBlockEntity(t.pos()) instanceof AbstractTowerBlockEntity)) {
				continue;
			}
			TowerStructure.clear(world, t.pos());
			TdArenaState.get(world.getServer()).removeTower(t.pos());
			world.spawnParticles(ParticleTypes.CLOUD, t.pos().getX() + 0.5, t.pos().getY() + 0.5, t.pos().getZ() + 0.5,
				20, 0.3, 0.4, 0.3, 0.02);
			world.playSound(null, t.pos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.7f, 1.2f);
		}
	}

	/**
	 * The damage source a sprung trap uses: the owning player's {@code playerAttack} (so the
	 * kill still credits their coins/XP) if they are online, else plain {@code magic}.
	 */
	private static DamageSource damageSource(ServerWorld world, UUID caster) {
		PlayerEntity player = world.getPlayerByUuid(caster);
		if (player instanceof ServerPlayerEntity sp) {
			return world.getDamageSources().playerAttack(sp);
		}
		return world.getDamageSources().magic();
	}
}
