package net.bubblesky.towerdefense.spell;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
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
 * The lightweight server-tick engine behind the two SPELL effects that outlive their cast:
 * temporary SUMMONS (Ranger wolves, Warlord squads) and Ranger SNARE traps. Both are
 * transient by design — a life-of-the-fight resource, not a persisted structure — so this
 * holds them in plain in-memory lists and sweeps them once per server tick. A restart
 * simply forgets them (summons revert to ordinary despawnable mobs; traps vanish), which is
 * the intended, cost-free behavior for ephemeral spell state.
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
 */
public final class SpellManager {

	private SpellManager() {
	}

	// ---- tuning ------------------------------------------------------------
	/** How long a summoned wolf/ally lives before it is dismissed (ticks; 20/sec). */
	public static final int SUMMON_LIFETIME_TICKS = 400;
	/** How long an unsprung trap persists before it expires (ticks). */
	public static final int TRAP_LIFETIME_TICKS = 600;
	/** How close an enemy must come to a trap to trigger it (blocks). */
	public static final double TRAP_TRIGGER_RADIUS = 2.5;
	/** Damage a sprung trap deals to the enemy that triggers it (caster-credited). */
	public static final float TRAP_DAMAGE = 6.0f;
	/** How long (ticks) a sprung trap roots its victim with strong Slowness. */
	public static final int TRAP_ROOT_TICKS = 80;

	// ---- state -------------------------------------------------------------
	/** Monotonic tick counter, advanced once per server tick; deadlines are measured against it. */
	private static long tick;
	private static final List<TimedSummon> SUMMONS = new ArrayList<>();
	private static final List<Trap> TRAPS = new ArrayList<>();

	/** A summoned entity plus the tick at which it should be dismissed. */
	private record TimedSummon(Entity entity, long deadline) {
	}

	/** A placed snare: where it is, who owns it (for damage credit), and when it expires. */
	private record Trap(ServerWorld world, BlockPos pos, UUID caster, long deadline) {
	}

	/** Register the once-per-tick sweep. Call once from mod init. */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tick++;
			sweepSummons();
			sweepTraps();
		});
	}

	// ---- summons -----------------------------------------------------------
	/** Register {@code entity} to be dismissed after {@link #SUMMON_LIFETIME_TICKS} ticks. */
	public static void addSummon(Entity entity) {
		SUMMONS.add(new TimedSummon(entity, tick + SUMMON_LIFETIME_TICKS));
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
	/** Place a single-use snare at {@code pos}, owned by {@code caster} for damage credit. */
	public static void addTrap(ServerWorld world, BlockPos pos, UUID caster) {
		TRAPS.add(new Trap(world, pos, caster, tick + TRAP_LIFETIME_TICKS));
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
			// Spring: root + caster-credited damage, then consume the trap.
			victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, TRAP_ROOT_TICKS, 4));
			victim.damage(world, damageSource(world, trap.caster()), TRAP_DAMAGE);
			world.spawnParticles(ParticleTypes.CRIT, center.x, center.y + 0.3, center.z, 20, 0.4, 0.2, 0.4, 0.1);
			world.playSound(null, trap.pos(), SoundEvents.BLOCK_TRIPWIRE_CLICK_OFF, SoundCategory.PLAYERS, 0.8f, 0.8f);
			it.remove();
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
