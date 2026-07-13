package net.bubblesky.towerdefense.entity;

import net.bubblesky.towerdefense.registry.ModEntities;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.world.World;

/**
 * A melee ally: the footman (and the sturdier knight, which reuses this class —
 * their differing hp / attack / speed come from the per-{@link EntityType} default
 * attributes in {@code ModEntities}). Adds a {@link MeleeAttackGoal} so that, once
 * {@link AllyTargetGoal} hands it an enemy target, it walks up and swings.
 */
public class TdFootman extends TdAllyEntity {
	/** House colour for the footman's dyed-leather uniform (a soldierly royal blue). */
	private static final int FOOTMAN_BLUE = 0x3355CC;

	public TdFootman(EntityType<? extends TdFootman> type, World world) {
		super(type, world);
	}

	@Override
	protected void initGoals() {
		super.initGoals();
		this.goalSelector.add(2, new MeleeAttackGoal(this, 1.1, true));
	}

	@Override
	protected void equipLoadout() {
		if (this.getType() == ModEntities.ALLY_KNIGHT) {
			// Knight — the elite: full diamond plate + a diamond blade.
			this.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
			this.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
			this.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
			this.equipStack(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
			this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
		} else {
			// Footman — line infantry: an iron helm over a blue leather uniform, iron sword.
			this.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
			this.equipStack(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, FOOTMAN_BLUE));
			this.equipStack(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, FOOTMAN_BLUE));
			this.equipStack(EquipmentSlot.FEET, dyed(Items.LEATHER_BOOTS, FOOTMAN_BLUE));
			this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
		}
	}
}
