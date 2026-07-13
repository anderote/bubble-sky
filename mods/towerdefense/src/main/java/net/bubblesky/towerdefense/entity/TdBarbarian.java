package net.bubblesky.towerdefense.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.world.World;

/**
 * A rugged heavy-melee roster enemy — the Barbarian. Beefier and hardier than a
 * footman, axe-flavoured, arriving mid-game (~wave 6+) to put real weight behind
 * the horde. Behaviourally identical to a {@link TdMeleeEnemy} (walk up and swing
 * when it has a loose target); its distinct role comes from the sturdier default
 * attributes registered in {@code ModEntities}. It is <em>not</em> a siege breaker
 * — it steers around walls like any normal enemy and only smashes through when the
 * pathfinder finds no way to the Idol at all.
 */
public class TdBarbarian extends TdMeleeEnemy {
	public TdBarbarian(EntityType<? extends TdBarbarian> type, World world) {
		super(type, world);
	}
}
