package net.bubblesky.towerdefense.progression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The static, DATA-ONLY definition of every class's Diablo-2-style SKILL TREE — the single
 * source of truth shared by BOTH sides (it has no server-only dependency, so the client's
 * {@code CharacterScreen} reads exactly the same tree the server validates against). It maps
 * each {@link PlayerClass} to an ordered list of {@link Skill}s that a player ranks up by
 * spending their PER-CLASS points (see {@link ClassProgress#allocations()}).
 *
 * <h2>Actives vs. passives</h2>
 * Every tree holds three {@link Type#ACTIVE ACTIVE} skills and three {@link Type#PASSIVE
 * PASSIVE} skills. An ACTIVE skill's {@link Skill#id() id} is DELIBERATELY EQUAL to the
 * matching {@link net.bubblesky.towerdefense.spell.SpellType#id() spell id}, so the spell's
 * {@code rankOf(caster)} — which reads {@code ClassProgress.points(spellId)} — transparently
 * picks up whatever the player has ranked here (a stronger spell). A PASSIVE is an always-on
 * buff while its class is active, read at the relevant call-site via
 * {@link StatModifiers#passiveRank(PlayerProgress, String)}.
 *
 * <h2>Tiers &amp; the class-level gate</h2>
 * Each skill sits in a {@link Skill#tier() tier} (1/2/3) that gates it behind a class level:
 * tier 1 needs class level {@value #TIER1_LEVEL}, tier 2 level {@value #TIER2_LEVEL}, tier 3
 * level {@value #TIER3_LEVEL} (see {@link #levelGate(int)}). Every skill caps at
 * {@value #MAX_RANK} ranks. The list order is tier-ascending with the ACTIVE listed before the
 * PASSIVE within a tier, so a UI can render it top-to-bottom and group by tier with no sorting.
 *
 * <p>Ids are STABLE — they are the keys of the persisted allocation map — so never rename one
 * once shipped; append new skills instead.
 */
public final class ClassSkillTree {

	private ClassSkillTree() {
	}

	/** The two kinds of tree entry: a castable spell (ACTIVE) or an always-on buff (PASSIVE). */
	public enum Type { ACTIVE, PASSIVE }

	/** Every skill caps at this many ranks. */
	public static final int MAX_RANK = 5;

	/** Class level required to spend into a tier-1 skill. */
	public static final int TIER1_LEVEL = 1;
	/** Class level required to spend into a tier-2 skill. */
	public static final int TIER2_LEVEL = 3;
	/** Class level required to spend into a tier-3 skill. */
	public static final int TIER3_LEVEL = 5;

	/**
	 * One node of a class tree: a stable {@code id}, a human {@code displayName}, whether it is
	 * an {@link Type#ACTIVE}/{@link Type#PASSIVE} skill, its {@code maxRank}, the {@code tier}
	 * that level-gates it, and a one-line {@code description} of its per-rank effect (rendered
	 * under the row). For an ACTIVE, {@code id} equals the corresponding spell id.
	 */
	public record Skill(String id, String displayName, Type type, int maxRank, int tier, String description) {

		/** Convenience: {@code true} when this is an always-on passive rather than a cast. */
		public boolean isPassive() {
			return type == Type.PASSIVE;
		}
	}

	// ---- the trees ---------------------------------------------------------
	private static final Map<PlayerClass, List<Skill>> TREES = new EnumMap<>(PlayerClass.class);

	static {
		// MAGE — fire/frost/lightning spellpower plus mana + elemental passives.
		TREES.put(PlayerClass.MAGE, List.of(
			active("fireball", "Fireball", 1, "+15% damage per rank"),
			passive("pyromancy", "Pyromancy", 1, "+10% Fireball damage per rank"),
			active("frost_nova", "Frost Nova", 2, "+12% damage & +0.5s slow per rank"),
			passive("arcane_mind", "Arcane Mind", 2, "+3 max mana & +1 mana/sec per rank"),
			active("chain_lightning", "Chain Lightning", 3, "+1 jump & +10% damage per rank"),
			passive("frostbite", "Frostbite", 3, "+12% Frost Nova damage & +0.5s slow per rank")));

		// RANGER — multishot/trap/wolves plus archery + mobility passives.
		TREES.put(PlayerClass.RANGER, List.of(
			active("multishot", "Multishot", 1, "+1 arrow per rank"),
			passive("precision", "Precision", 1, "+8% arrow damage per rank"),
			active("trap", "Snare Trap", 2, "+15% damage & +0.5s root per rank"),
			passive("fleet_foot", "Fleet Foot", 2, "+3% movement speed per rank"),
			active("summon_wolf", "Summon Wolves", 3, "+1 wolf per 2 ranks & +10% wolf HP per rank"),
			passive("eagle_eye", "Eagle Eye", 3, "+1 block trap/spell reach per rank")));

		// ENGINEER — turret/repair/acid plus economy + construction passives.
		TREES.put(PlayerClass.ENGINEER, List.of(
			active("deploy_turret", "Deploy Turret", 1, "TEMPORARY turret; higher ranks last longer and hit harder"),
			passive("salvage", "Salvage", 1, "+10% gold & essence per rank"),
			active("repair_pulse", "Repair Pulse", 2, "+1 heart heal per rank & +1 block per 2 ranks"),
			passive("overclock", "Overclock", 2, "+1 deployed-turret tier per 2 ranks"),
			active("wall_of_acid", "Wall of Acid", 3, "+1 block length per rank"),
			passive("tinkerer", "Tinkerer", 3, "+1 heart Repair & +1 Wall length per rank")));

		// NECROMANCER — raise/summon/bone plus minion + damage passives.
		TREES.put(PlayerClass.NECROMANCER, List.of(
			active("raise_dead", "Raise Dead", 1, "+1 skeleton per rank"),
			passive("minion_mastery", "Minion Mastery", 1, "+10% minion HP & damage per rank"),
			active("summon_squad", "Summon Skeletons", 2, "+1 archer per 2 ranks & +10% archer damage per rank"),
			passive("unholy_vigor", "Unholy Vigor", 2, "+30s minion lifetime per rank"),
			active("bone_spear", "Bone Spear", 3, "+2 damage per rank"),
			passive("amplify", "Amplify", 3, "+10% Bone Spear & spell damage per rank")));
	}

	private static Skill active(String id, String name, int tier, String desc) {
		return new Skill(id, name, Type.ACTIVE, MAX_RANK, tier, desc);
	}

	private static Skill passive(String id, String name, int tier, String desc) {
		return new Skill(id, name, Type.PASSIVE, MAX_RANK, tier, desc);
	}

	// ---- lookups -----------------------------------------------------------
	/**
	 * The ordered skill list for {@code cls} (tier-ascending, ACTIVE before PASSIVE within a
	 * tier), or an empty list for an unknown/null class. The returned list is immutable.
	 */
	public static List<Skill> skills(PlayerClass cls) {
		if (cls == null) {
			return Collections.emptyList();
		}
		return TREES.getOrDefault(cls, Collections.emptyList());
	}

	/** The skill with {@code id} in {@code cls}'s tree, or {@code null} if there is none. */
	public static Skill skill(PlayerClass cls, String id) {
		if (cls == null || id == null) {
			return null;
		}
		for (Skill s : skills(cls)) {
			if (s.id().equals(id)) {
				return s;
			}
		}
		return null;
	}

	/** The max rank of the skill {@code id} in {@code cls} (0 if there is no such skill). */
	public static int maxRank(PlayerClass cls, String id) {
		Skill s = skill(cls, id);
		return s == null ? 0 : s.maxRank();
	}

	/**
	 * The class level required to spend into a skill in {@code tier}:
	 * {@value #TIER1_LEVEL}/{@value #TIER2_LEVEL}/{@value #TIER3_LEVEL} for tiers 1/2/3. Any
	 * out-of-range tier falls back to {@value #TIER1_LEVEL}.
	 */
	public static int levelGate(int tier) {
		return switch (tier) {
			case 2 -> TIER2_LEVEL;
			case 3 -> TIER3_LEVEL;
			default -> TIER1_LEVEL;
		};
	}

	/** The distinct tiers present in any tree, ascending — a UI grouping convenience. */
	public static List<Integer> tiers() {
		return new ArrayList<>(List.of(1, 2, 3));
	}
}
