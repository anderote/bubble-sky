package net.bubblesky.towerdefense.progression;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.bubblesky.towerdefense.progression.PlayerProgress.Stat;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Turns a {@link PlayerProgress}'s spent points into live gameplay effects.
 *
 * <p>Three stats map to vanilla attribute modifiers, applied in {@link #apply}:
 * <ul>
 *   <li>{@link Stat#VITALITY} → {@code generic.max_health} +2 HP (1 heart) per point</li>
 *   <li>{@link Stat#STRENGTH} → {@code generic.attack_damage} +0.5 per point (melee)</li>
 *   <li>{@link Stat#AGILITY} → {@code generic.movement_speed} +2% (of base) per point</li>
 * </ul>
 * Each uses a STABLE per-stat modifier {@link Identifier}, and application is
 * remove-then-add: re-applying on every join/respawn/allocate refreshes the modifier
 * in place instead of stacking a fresh copy each relog.
 *
 * <p>The remaining two stats are read at use-time as multipliers rather than baked
 * into attributes: {@link #bowMult} (+6% fired-arrow damage per {@link Stat#MARKSMANSHIP}
 * point) and {@link #coinMult} (+8% coin payout per {@link Stat#FORTUNE} point).
 */
public final class StatModifiers {

	private StatModifiers() {
	}

	// ---- stable per-stat modifier ids --------------------------------------
	private static final Identifier VITALITY_ID = id("progression_vitality");
	private static final Identifier STRENGTH_ID = id("progression_strength");
	private static final Identifier AGILITY_ID = id("progression_agility");

	// ---- per-point steps ---------------------------------------------------
	/** Vitality: +2 max health (one heart) per point, flat. */
	private static final double HEALTH_PER_POINT = 2.0;
	/** Strength: +0.5 melee attack damage per point, flat. */
	private static final double ATTACK_PER_POINT = 0.5;
	/** Agility: +2% movement speed per point (fraction of the base value). */
	private static final double SPEED_PER_POINT = 0.02;
	/** Marksmanship: +6% fired-arrow damage per point. */
	private static final double BOW_PER_POINT = 0.06;
	/** Fortune: +8% coin payout per point. */
	private static final double COIN_PER_POINT = 0.08;

	private static Identifier id(String path) {
		return Identifier.of(TowerDefenseMod.MOD_ID, path);
	}

	/**
	 * (Re)apply the attribute-backed stats to a player. Idempotent: each stat's
	 * modifier is removed by its stable id and re-added, so calling this on every
	 * join/respawn/world-change/allocation never stacks. Health is clamped down if a
	 * (future) reduction ever left the player above their new max.
	 */
	public static void apply(ServerPlayerEntity player, PlayerProgress progress) {
		applyFlat(player, EntityAttributes.MAX_HEALTH, VITALITY_ID,
			progress.points(Stat.VITALITY) * HEALTH_PER_POINT,
			EntityAttributeModifier.Operation.ADD_VALUE);
		applyFlat(player, EntityAttributes.ATTACK_DAMAGE, STRENGTH_ID,
			progress.points(Stat.STRENGTH) * ATTACK_PER_POINT,
			EntityAttributeModifier.Operation.ADD_VALUE);
		applyFlat(player, EntityAttributes.MOVEMENT_SPEED, AGILITY_ID,
			progress.points(Stat.AGILITY) * SPEED_PER_POINT,
			EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE);

		if (player.getHealth() > player.getMaxHealth()) {
			player.setHealth(player.getMaxHealth());
		}
	}

	/** Remove-then-add one modifier by its stable id (skips a zero-value modifier entirely). */
	private static void applyFlat(ServerPlayerEntity player, RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attr,
			Identifier modifierId, double amount, EntityAttributeModifier.Operation op) {
		EntityAttributeInstance inst = player.getAttributeInstance(attr);
		if (inst == null) {
			return;
		}
		inst.removeModifier(modifierId);
		if (amount != 0.0) {
			inst.addPersistentModifier(new EntityAttributeModifier(modifierId, amount, op));
		}
	}

	// ---- use-time multipliers ----------------------------------------------
	/** Fired-arrow damage multiplier (1.0 at zero points; +6% per Marksmanship point). */
	public static double bowMult(PlayerProgress progress) {
		return 1.0 + progress.points(Stat.MARKSMANSHIP) * BOW_PER_POINT;
	}

	/** Coin-payout multiplier (1.0 at zero points; +8% per Fortune point). */
	public static double coinMult(PlayerProgress progress) {
		return 1.0 + progress.points(Stat.FORTUNE) * COIN_PER_POINT;
	}
}
