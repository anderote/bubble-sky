package net.bubblesky.towerdefense.progression;

import java.util.Locale;
import net.bubblesky.towerdefense.progression.PlayerProgress.Stat;

/**
 * The four playable CLASSES a hero may adopt for a life. A class is the PER-LIFE layer
 * layered on top of the permanent global 7-stat character (see {@link PlayerProgress}):
 * picking one grants a loadout (gear + spell placeholders) and applies a modest per-life
 * {@link Stat} bias, while each class also carries its OWN independent progression track
 * (see {@link ClassProgress}). The global character is unchanged by class switching.
 *
 * <h2>Stat bias</h2>
 * Each class FAVORS exactly one of the seven stats. While that class is active, the
 * favored stat behaves as if the player had spent {@link #BIAS_POINTS} extra points into
 * it — a small additive nudge on top of the player's real allocation, read by
 * {@link StatModifiers} when it (re)computes attributes and use-time multipliers. The
 * bias is applied through the same remove-then-add path as everything else, so it is
 * refreshed (never stacked) on every class change / respawn.
 *
 * <ul>
 *   <li>{@link #MAGE} → {@link Stat#INTELLIGENCE} (spellpower / mana)</li>
 *   <li>{@link #RANGER} → {@link Stat#MARKSMANSHIP} (bow damage)</li>
 *   <li>{@link #ENGINEER} → {@link Stat#FORTUNE} (payout / construction)</li>
 *   <li>{@link #NECROMANCER} → {@link Stat#INTELLIGENCE} (summoning / mana)</li>
 * </ul>
 *
 * <p>The ordinal order is the wire/registry order — DO NOT reorder existing entries, as
 * saves reference classes by {@link #id()} string (stable) but the per-class map keys on
 * the enum. New classes may be appended.
 */
public enum PlayerClass {
	MAGE("mage", "Mage", Stat.INTELLIGENCE),
	RANGER("ranger", "Ranger", Stat.MARKSMANSHIP),
	ENGINEER("engineer", "Engineer", Stat.FORTUNE),
	/**
	 * The undead summoner (formerly the melee WARLORD). Favors {@link Stat#INTELLIGENCE}
	 * because its kit — raising skeletons and hurling bone — is mana-hungry. Its stable
	 * {@link #id()} is {@code "necromancer"}; the legacy {@code "warlord"} id is still
	 * accepted by {@link #fromId(String)} so old saves resolve to this class.
	 */
	NECROMANCER("necromancer", "Necromancer", Stat.INTELLIGENCE);

	/**
	 * How many "virtual" points the active class adds to its favored stat. Kept modest
	 * (a flavor nudge, not a power spike) — tune here to rebalance every class at once.
	 */
	public static final int BIAS_POINTS = 2;

	/** Stable lowercase id used on the wire, in NBT, and by {@code /td class <id>}. */
	private final String id;
	/** Human-facing display name for chat/feedback/UI. */
	private final String displayName;
	/** The single stat this class biases while active. */
	private final Stat favored;

	PlayerClass(String id, String displayName, Stat favored) {
		this.id = id;
		this.displayName = displayName;
		this.favored = favored;
	}

	/** Stable lowercase id (e.g. {@code "mage"}). */
	public String id() {
		return id;
	}

	/** Human-facing display name (e.g. {@code "Mage"}). */
	public String displayName() {
		return displayName;
	}

	/** The stat this class favors while active. */
	public Stat favoredStat() {
		return favored;
	}

	/**
	 * The additive point bias this class contributes to {@code stat} while active:
	 * {@link #BIAS_POINTS} for its {@link #favoredStat()}, else {@code 0}. Read by
	 * {@link StatModifiers} so the bias flows into both attribute modifiers and the
	 * use-time multipliers with no special-casing at each site.
	 */
	public int statBias(Stat stat) {
		return stat == favored ? BIAS_POINTS : 0;
	}

	/**
	 * Resolve a class by its {@link #id()} (case-insensitive), or {@code null} if no
	 * class matches — callers treat {@code null} as "unknown class" and reject the pick.
	 */
	public static PlayerClass fromId(String id) {
		if (id == null) {
			return null;
		}
		String needle = id.toLowerCase(Locale.ROOT);
		// Legacy alias: the WARLORD class was reworked into the NECROMANCER, so any old
		// save / activeClass value written as "warlord" must resolve to the new class.
		if (needle.equals("warlord")) {
			return NECROMANCER;
		}
		for (PlayerClass c : values()) {
			if (c.id.equals(needle)) {
				return c;
			}
		}
		return null;
	}
}
