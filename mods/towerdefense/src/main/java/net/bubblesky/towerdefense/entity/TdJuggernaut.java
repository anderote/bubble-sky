package net.bubblesky.towerdefense.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.world.World;

/**
 * The Juggernaut — an armoured TANK and the roster's premier bullet-sponge. Enormous HP
 * (~120 base), heavy armour (~18), and a slow, inexorable melee march: it exists to SOAK
 * tower fire while the rest of the horde slips through, forcing the defender to commit
 * real focused damage rather than tickling the swarm down. Arrives mid/late game
 * (~wave 8+) once the player's battery is strong enough to make a damage-sink meaningful.
 *
 * <p>Behaviourally it is an ordinary {@link TdMeleeEnemy} (walk up and swing on a loose
 * target; paths around walls like any normal enemy); its role comes entirely from the
 * sturdy default attributes registered in {@code ModEntities}. Its imposing SIZE is applied
 * client-side by a scaled biped renderer rather than the {@code generic.scale} attribute,
 * because the wave manager reserves that attribute for its adaptive-escalation growth.
 */
public class TdJuggernaut extends TdMeleeEnemy {
	public TdJuggernaut(EntityType<? extends TdJuggernaut> type, World world) {
		super(type, world);
	}
}
