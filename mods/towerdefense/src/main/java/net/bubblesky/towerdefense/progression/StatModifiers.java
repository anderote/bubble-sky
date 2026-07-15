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
 * <p>Four stats map to vanilla attribute modifiers, applied in {@link #apply}:
 * <ul>
 *   <li>{@link Stat#VITALITY} → {@code generic.max_health} +4 HP (2 hearts) per point</li>
 *   <li>{@link Stat#STRENGTH} → {@code generic.attack_damage} +1.0 per point (melee)</li>
 *   <li>{@link Stat#AGILITY} → {@code generic.movement_speed} +3% (of base) per point</li>
 *   <li>{@link Stat#RESILIENCE} → {@code generic.armor} +1 per point</li>
 * </ul>
 * Each uses a STABLE per-stat modifier {@link Identifier}, and application is
 * remove-then-add: re-applying on every join/respawn/allocate refreshes the modifier
 * in place instead of stacking a fresh copy each relog.
 *
 * <p>The remaining three stats are read at use-time rather than baked into attributes:
 * {@link #bowMult} (+10% fired-arrow damage per {@link Stat#MARKSMANSHIP} point),
 * {@link #coinMult} (+12% coin payout per {@link Stat#FORTUNE} point), and
 * {@link Stat#INTELLIGENCE}, which now drives BOTH the coin-vacuum
 * {@link #collectionRadius} (its primary effect) AND retains its {@link #xpMult}
 * (+8% XP gained per point) so the older behavior never regresses.
 */
public final class StatModifiers {

	private StatModifiers() {
	}

	// ---- stable per-stat modifier ids --------------------------------------
	private static final Identifier VITALITY_ID = id("progression_vitality");
	private static final Identifier STRENGTH_ID = id("progression_strength");
	private static final Identifier AGILITY_ID = id("progression_agility");
	private static final Identifier RESILIENCE_ID = id("progression_resilience");
	// Flat "base character" buffs every player gets (on top of vanilla + allocated stats).
	private static final Identifier BASE_HEALTH_ID = id("base_health");
	private static final Identifier BASE_ATTACK_ID = id("base_attack");
	private static final Identifier BASE_ARMOR_ID = id("base_armor");

	// ---- per-point steps ---------------------------------------------------
	/** Vitality: +4 max health (two hearts) per point, flat. */
	private static final double HEALTH_PER_POINT = 4.0;
	/** Strength: +1.0 melee attack damage per point, flat. */
	private static final double ATTACK_PER_POINT = 1.0;
	/** Agility: +3% movement speed per point (fraction of the base value). */
	private static final double SPEED_PER_POINT = 0.03;
	/** Resilience: +1 armor per point, flat. */
	private static final double ARMOR_PER_POINT = 1.0;
	/** Marksmanship: +10% fired-arrow damage per point. */
	private static final double BOW_PER_POINT = 0.10;
	/** Fortune: +12% coin payout per point. */
	private static final double COIN_PER_POINT = 0.12;
	/** Intelligence: +8% XP gained per point. */
	private static final double XP_PER_POINT = 0.08;
	/** Base coin-vacuum collection radius (blocks) every player has at Intelligence 0. */
	private static final double COLLECTION_BASE_RADIUS = 2.5;
	/** Intelligence: +1.0 block of collection radius per point. */
	private static final double COLLECTION_PER_POINT = 1.0;
	/** Base max mana every player has at Intelligence 0. */
	private static final int MANA_BASE_MAX = 20;
	/** Intelligence: +5 max mana per point. */
	private static final int MANA_PER_POINT = 5;
	/** Base mana regenerated per SECOND at Intelligence 0. */
	private static final int MANA_REGEN_BASE = 2;
	/** Intelligence: +1 mana/second of regen per point. */
	private static final int MANA_REGEN_PER_POINT = 1;

	// ---- flat base-character buffs (always on, independent of skill points) --
	/** +20 max health for every player (20 -> 40 HP / 20 hearts). */
	private static final double BASE_HEALTH_BONUS = 20.0;
	/** +2 base melee attack damage for every player. */
	private static final double BASE_ATTACK_BONUS = 2.0;
	/** +4 innate armor for every player (stacks with worn armor). */
	private static final double BASE_ARMOR_BONUS = 4.0;

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
			effectivePoints(progress, Stat.VITALITY) * HEALTH_PER_POINT,
			EntityAttributeModifier.Operation.ADD_VALUE);
		applyFlat(player, EntityAttributes.ATTACK_DAMAGE, STRENGTH_ID,
			effectivePoints(progress, Stat.STRENGTH) * ATTACK_PER_POINT,
			EntityAttributeModifier.Operation.ADD_VALUE);
		applyFlat(player, EntityAttributes.MOVEMENT_SPEED, AGILITY_ID,
			effectivePoints(progress, Stat.AGILITY) * SPEED_PER_POINT,
			EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE);
		applyFlat(player, EntityAttributes.ARMOR, RESILIENCE_ID,
			effectivePoints(progress, Stat.RESILIENCE) * ARMOR_PER_POINT,
			EntityAttributeModifier.Operation.ADD_VALUE);

		// Flat base-character buffs — a sturdier, stronger hero by default, on top of
		// vanilla and any allocated points. Always applied (idempotent, stable ids).
		applyFlat(player, EntityAttributes.MAX_HEALTH, BASE_HEALTH_ID,
			BASE_HEALTH_BONUS, EntityAttributeModifier.Operation.ADD_VALUE);
		applyFlat(player, EntityAttributes.ATTACK_DAMAGE, BASE_ATTACK_ID,
			BASE_ATTACK_BONUS, EntityAttributeModifier.Operation.ADD_VALUE);
		applyFlat(player, EntityAttributes.ARMOR, BASE_ARMOR_ID,
			BASE_ARMOR_BONUS, EntityAttributeModifier.Operation.ADD_VALUE);

		if (player.getHealth() > player.getMaxHealth()) {
			player.setHealth(player.getMaxHealth());
		}

		// Keep the mana pool's cap in step with Intelligence (+ any active-class bias).
		// Done here so it refreshes on the exact same join/respawn/allocate/class-change
		// events that (re)apply attributes, never stacking (it's a recompute, not an add).
		progress.refreshMaxMana();
	}

	/**
	 * A stat's EFFECTIVE point count: the player's real allocation plus any per-life bias
	 * from the {@linkplain PlayerProgress#getActiveClass() active class}. Every effect —
	 * attributes and use-time multipliers alike — reads through here, so a class's favored
	 * stat is buffed everywhere with no per-site special-casing. With no active class this
	 * is exactly the raw allocation, so the global track is unchanged when unclassed.
	 */
	private static int effectivePoints(PlayerProgress progress, Stat stat) {
		int base = progress.points(stat);
		PlayerClass active = progress.getActiveClass();
		return active == null ? base : base + active.statBias(stat);
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
	// All read EFFECTIVE points (allocation + active-class bias) so a class's favored stat
	// buffs its multiplier too — e.g. Ranger's Marksmanship bias raises bow damage.
	/** Fired-arrow damage multiplier (1.0 at zero points; +10% per Marksmanship point). */
	public static double bowMult(PlayerProgress progress) {
		return 1.0 + effectivePoints(progress, Stat.MARKSMANSHIP) * BOW_PER_POINT;
	}

	/** Coin-payout multiplier (1.0 at zero points; +12% per Fortune point). */
	public static double coinMult(PlayerProgress progress) {
		return 1.0 + effectivePoints(progress, Stat.FORTUNE) * COIN_PER_POINT;
	}

	/** XP-gain multiplier (1.0 at zero points; +8% per Intelligence point). */
	public static double xpMult(PlayerProgress progress) {
		return 1.0 + effectivePoints(progress, Stat.INTELLIGENCE) * XP_PER_POINT;
	}

	/**
	 * Maximum mana for a record: {@code MANA_BASE_MAX + Intelligence * MANA_PER_POINT}
	 * ({@value #MANA_BASE_MAX} at 0 points, +{@value #MANA_PER_POINT} per Intelligence
	 * point), using EFFECTIVE points so a Mage's Intelligence bias also lifts the pool.
	 * This is the single home for the mana formula; {@link PlayerProgress#refreshMaxMana()}
	 * calls it. Spending / regen arrive in Phase 2.
	 */
	public static int maxMana(PlayerProgress progress) {
		return MANA_BASE_MAX + effectivePoints(progress, Stat.INTELLIGENCE) * MANA_PER_POINT;
	}

	/**
	 * Mana regenerated PER SECOND for a record:
	 * {@code MANA_REGEN_BASE + Intelligence * MANA_REGEN_PER_POINT} ({@value #MANA_REGEN_BASE}
	 * at 0 points, +{@value #MANA_REGEN_PER_POINT} per Intelligence point), using EFFECTIVE
	 * points so a Mage's Intelligence bias also speeds regen. Ticked once per second by
	 * {@code ProgressEvents} and clamped to {@link #maxMana}.
	 */
	public static int manaRegenPerSecond(PlayerProgress progress) {
		return MANA_REGEN_BASE + effectivePoints(progress, Stat.INTELLIGENCE) * MANA_REGEN_PER_POINT;
	}

	/**
	 * Coin-vacuum collection radius in blocks for a given record:
	 * {@code BASE + Intelligence * PER_POINT} — {@value #COLLECTION_BASE_RADIUS} at 0
	 * points, growing {@value #COLLECTION_PER_POINT} block per Intelligence point (so
	 * 2.5 / 7.5 / 12.5 blocks at Intelligence 0 / 5 / 10). This is Intelligence's primary
	 * effect: a smarter hero sweeps dropped coins into their bank from farther away.
	 */
	public static double collectionRadius(PlayerProgress progress) {
		return COLLECTION_BASE_RADIUS + effectivePoints(progress, Stat.INTELLIGENCE) * COLLECTION_PER_POINT;
	}

	/** The base collection radius (blocks) used when no progression record is available. */
	public static double baseCollectionRadius() {
		return COLLECTION_BASE_RADIUS;
	}
}
