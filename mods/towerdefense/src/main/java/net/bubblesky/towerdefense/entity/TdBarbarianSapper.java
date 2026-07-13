package net.bubblesky.towerdefense.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.world.World;

/**
 * The Barbarian Sapper (a.k.a. the Ram) — the straight-line wall-breaker. A tanky,
 * slow melee brute that arrives deep into a run (~wave 10+) once the defender has
 * had time to build walls worth threatening.
 *
 * <p>Its defining trait is {@link #isSiegeBreaker()} returning {@code true}: rather
 * than pathing <em>around</em> obstacles like a normal enemy, the wave manager makes
 * a sapper bore <em>straight</em> toward the Idol, tunnelling through whatever solid
 * block sits directly ahead on the line to the base — and digging faster than a
 * normal enemy — so a squad of sappers punches a straight corridor through even a
 * thick wall. Everything else (hp / attack / speed) comes from its default
 * attributes in {@code ModEntities}.
 */
public class TdBarbarianSapper extends TdMeleeEnemy {
	public TdBarbarianSapper(EntityType<? extends TdBarbarianSapper> type, World world) {
		super(type, world);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The sapper is the roster's dedicated siege breaker: it bores a straight line
	 * to the Idol instead of pathing around walls.
	 */
	@Override
	public boolean isSiegeBreaker() {
		return true;
	}
}
