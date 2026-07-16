package net.bubblesky.towerdefense.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.world.World;

/**
 * The Direwolf — a fast BEAST/pack unit. Frail (~10 base HP) but the quickest thing on the
 * field, arriving in numbers from ~wave 4 to swamp a slow defence with sheer count and
 * speed: a cheap, expendable rusher that punishes gaps and thin front lines. Cut one down
 * in a hit, but there are always more.
 *
 * <p>Behaviourally an ordinary {@link TdMeleeEnemy} (lunges and bites a loose target;
 * paths around walls like any normal enemy); its role comes from its frail-but-fast default
 * attributes in {@code ModEntities} and its low Warlord threat cost (so a plan can field a
 * whole pack cheaply). Rendered client-side with the vanilla wolf model.
 *
 * <p>Note: during a managed wave every enemy is steered at the fixed slow zombie-march pace
 * by the wave manager, so the Direwolf's high base movement speed mainly matters for loose
 * spawns and for its cheap threat pricing — it reads as the swarm-filler of the roster.
 */
public class TdDirewolf extends TdMeleeEnemy {
	public TdDirewolf(EntityType<? extends TdDirewolf> type, World world) {
		super(type, world);
	}
}
