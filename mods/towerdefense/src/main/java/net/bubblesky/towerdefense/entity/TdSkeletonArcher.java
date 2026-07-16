package net.bubblesky.towerdefense.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.world.World;

/**
 * The Warlord's SUMMONED SKELETON ARCHER — a raised undead bowman that fights as one
 * of our friendly {@link TdAllyEntity} allies rather than as a vanilla
 * {@link net.minecraft.entity.mob.SkeletonEntity}.
 *
 * <h2>Why a custom skeleton instead of the vanilla mob</h2>
 * Our towers acquire targets by scanning for any {@code HostileEntity}
 * (see {@code AbstractTowerBlockEntity.findNearestHostile}). A real
 * {@code SkeletonEntity} is a {@code HostileEntity}, so summoning one would make the
 * caster's OWN towers open fire on their summons, and the wave enemies would have no
 * idea it was meant to be an ally. By instead subclassing {@link TdAllyArcher} — a
 * non-hostile {@link net.minecraft.entity.mob.PathAwareEntity} — this summon slots
 * straight into the existing ally plumbing: towers ignore it, the enemy roster already
 * knows how to fight a {@link TdAllyEntity}, and it obeys the caster's orders + owner
 * stamp. All it adds on top of {@link TdAllyArcher} is its own {@link EntityType}
 * (so it can carry the skeleton skin/model client-side and its own beefier default
 * attributes registered in {@code ModEntities}) and a bare bow loadout in place of the
 * base archer's ranger-green leather kit.
 *
 * <p>Everything that makes it a competent ranged fighter — the {@code ProjectileAttackGoal},
 * the arrow shot whose damage tracks this entity's {@code ATTACK_DAMAGE} attribute, and
 * the shared ally targeting/ordering goals — is inherited unchanged from
 * {@link TdAllyArcher} / {@link TdAllyEntity}.
 */
public class TdSkeletonArcher extends TdAllyArcher {

	/**
	 * How far (blocks) this skeleton will loose arrows — well beyond the base
	 * {@link TdAllyArcher}'s 14-block range so the summoned bowmen snipe from the back line.
	 */
	private static final float SKELETON_SHOOT_RANGE = 24.0f;

	/**
	 * FOLLOW_RANGE (blocks) forced onto every skeleton archer so it can ACQUIRE targets from as
	 * far as it can shoot them — the base ally follow range (32) is already generous, but we push
	 * it further so the longer {@link #SKELETON_SHOOT_RANGE} shooting range is actually usable.
	 */
	private static final double SKELETON_FOLLOW_RANGE = 48.0;

	public TdSkeletonArcher(EntityType<? extends TdSkeletonArcher> type, World world) {
		super(type, world);
		// Widen sight to match the longer shooting range (attributes exist by now, set post-super).
		EntityAttributeInstance follow = this.getAttributeInstance(EntityAttributes.FOLLOW_RANGE);
		if (follow != null) {
			follow.setBaseValue(SKELETON_FOLLOW_RANGE);
		}
	}

	/** Shoot from noticeably farther than the base ranger archer (see {@link #SKELETON_SHOOT_RANGE}). */
	@Override
	protected float shootRange() {
		return SKELETON_SHOOT_RANGE;
	}

	/**
	 * A bare skeletal loadout: just a bow in hand. Unlike the living
	 * {@link TdAllyArcher} we skip the leather uniform entirely — the entity renders
	 * with the vanilla skeleton model/texture client-side, so cloth armour would only
	 * clutter the silhouette. Its durability comes from the higher default attributes
	 * (see {@code ModEntities#registerAttributes}) rather than worn plate.
	 */
	@Override
	protected void equipLoadout() {
		this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
	}
}
