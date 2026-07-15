package net.bubblesky.towerdefense.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.world.World;

/**
 * The Necromancer's raised SKELETON WARRIOR — a MELEE undead ally reanimated from an
 * enemy corpse by the {@code RAISE_DEAD} spell. It is the close-combat counterpart to the
 * ranged {@link TdSkeletonArcher}: where the archer subclasses {@link TdAllyArcher} for a
 * bow, this one mirrors {@link TdFootman}'s melee wiring — a {@link MeleeAttackGoal} on top
 * of the shared {@link TdAllyEntity} targeting/order/ownership plumbing — so once
 * {@link AllyTargetGoal} hands it an enemy it walks up and swings.
 *
 * <h2>Why a custom skeleton instead of the vanilla mob</h2>
 * Exactly the reasoning of {@link TdSkeletonArcher}: our towers acquire targets by scanning
 * for any {@code HostileEntity}, so summoning a real {@code SkeletonEntity} would make the
 * caster's own towers open fire on their summon. By instead being a non-hostile
 * {@link TdAllyEntity}, this warrior slots straight into the ally plumbing (towers ignore
 * it, wave enemies fight it, it obeys the caster's orders + owner stamp). All it adds on top
 * of the base ally is its own {@link EntityType} (for the skeleton skin/model client-side and
 * its beefier default attributes registered in {@code ModEntities}) and a bare sword loadout
 * so it reads as a warrior.
 *
 * <p>Its "pretty good" stats — ~30 max health, ~7 attack, ~0.3 speed — live with the rest of
 * the roster in {@code ModEntities#registerAttributes} rather than here.
 */
public class TdSkeletonWarrior extends TdAllyEntity {

	public TdSkeletonWarrior(EntityType<? extends TdSkeletonWarrior> type, World world) {
		super(type, world);
	}

	@Override
	protected void initGoals() {
		super.initGoals();
		this.goalSelector.add(2, new MeleeAttackGoal(this, 1.1, true));
	}

	/**
	 * A bare skeletal loadout: just an iron sword in hand. Like {@link TdSkeletonArcher} we
	 * skip any cloth uniform — the entity renders with the vanilla skeleton model/texture
	 * client-side, and the base biped renderer draws this held sword so it reads as a warrior.
	 * Its durability comes from its higher default attributes rather than worn plate.
	 */
	@Override
	protected void equipLoadout() {
		this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
	}
}
