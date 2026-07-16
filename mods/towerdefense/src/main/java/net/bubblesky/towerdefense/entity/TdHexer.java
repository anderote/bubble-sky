package net.bubblesky.towerdefense.entity;

import java.util.List;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

/**
 * The Hexer — a SUPPORT caster and the roster's force-multiplier. Moderately tough (~28
 * base HP) but no rusher: it marches with the horde and, every few seconds, pulses a
 * BUFF over its nearby comrades — Regeneration + Strength plus a small direct heal — making
 * a mob of otherwise-ordinary enemies suddenly hit harder and shrug off chip damage.
 * Arrives deep into a run (~wave 10+). Because its buff radiates through the whole knot of
 * enemies around it, the Hexer is a high-value PRIORITY TARGET: kill it and the pack
 * deflates; leave it and the wave snowballs.
 *
 * <p><b>How the buff works (and why it survives wave steering).</b> The wave manager strips
 * every enemy's AI goals on spawn and drives the body itself, so a goal-based aura would be
 * stripped away. Instead the Hexer runs its aura from {@link #mobTick(ServerWorld)} — the
 * server-side per-tick hook that fires regardless of the goal selectors — so the pulse keeps
 * beating no matter how the manager is steering it. Every {@link #BUFF_INTERVAL_TICKS} ticks
 * it finds all {@code td_enemy} mobs within {@link #BUFF_RADIUS} blocks and refreshes their
 * Regeneration + Strength (and tops their health up a little), emitting witch-magic
 * particles so the support pulse reads on-screen.
 *
 * <p>It remains a fully ordinary {@code td_enemy}: tagged, red-glowing, telemetry-counted,
 * escalation-scaled, and killable by towers/players like any other roster member.
 */
public class TdHexer extends TdEnemyEntity {
	/** Ticks between buff pulses (~4s at 20 tps) — frequent enough to matter, sparse enough
	 *  that focusing the Hexer down before the next pulse is worthwhile. */
	private static final int BUFF_INTERVAL_TICKS = 80;
	/** Radius (blocks) within which the pulse reaches fellow {@code td_enemy} mobs. */
	private static final double BUFF_RADIUS = 8.0;
	/** Duration (ticks) of each applied buff — a touch longer than the interval so the buff
	 *  never fully lapses between pulses on a mob that stays in range. */
	private static final int BUFF_DURATION_TICKS = 120;
	/** Flat health the pulse restores to each buffed ally (on top of Regeneration's tick-heal). */
	private static final float PULSE_HEAL = 2.0f;

	public TdHexer(EntityType<? extends TdHexer> type, World world) {
		super(type, world);
	}

	/**
	 * Drive the support aura. Runs every server tick (independent of the goal selectors the
	 * wave manager clears); on each {@link #BUFF_INTERVAL_TICKS}-th tick it refreshes
	 * Regeneration + Strength on — and tops the health of — every nearby {@code td_enemy}
	 * (the Hexer's own kind), including itself, and puffs witch-magic particles so the buff
	 * is visible.
	 */
	@Override
	protected void mobTick(ServerWorld world) {
		super.mobTick(world);
		if (this.age % BUFF_INTERVAL_TICKS != 0) {
			return;
		}
		Box area = this.getBoundingBox().expand(BUFF_RADIUS);
		List<TdEnemyEntity> allies = world.getEntitiesByClass(TdEnemyEntity.class, area,
			e -> e.isAlive() && e.getCommandTags().contains(
				net.bubblesky.towerdefense.game.WaveManager.ENEMY_TAG));
		if (allies.isEmpty()) {
			return;
		}
		for (TdEnemyEntity ally : allies) {
			// AMBIENT-style effects (showParticles=true, hidden icon) so the buffed mobs shimmer
			// without cluttering a HUD. Amplifier 0 = tier I of each.
			ally.addStatusEffect(new StatusEffectInstance(
				StatusEffects.REGENERATION, BUFF_DURATION_TICKS, 0, true, true, true));
			ally.addStatusEffect(new StatusEffectInstance(
				StatusEffects.STRENGTH, BUFF_DURATION_TICKS, 0, true, true, true));
			if (ally.getHealth() < ally.getMaxHealth()) {
				ally.heal(PULSE_HEAL);
			}
			world.spawnParticles(ParticleTypes.WITCH,
				ally.getX(), ally.getBodyY(0.75), ally.getZ(),
				6, 0.35, 0.5, 0.35, 0.0);
		}
	}
}
