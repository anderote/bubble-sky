package net.bubblesky.towerdefense.progression;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.NbtCompound;

/**
 * The PER-CLASS progression record: one of these exists per {@code (player, class)} pair
 * (held in {@link PlayerProgress#classProgress(PlayerClass)}). It mirrors the shape of the
 * global {@link PlayerProgress} track — {@code xp}, {@code level}, unspent class points,
 * and a per-skill allocation map — but is INDEPENDENT: you level a class only by playing
 * it, and switching classes preserves each class's own track.
 *
 * <p>Class points buy into that class's SKILL TREE (spell unlocks / ranks / passives). The
 * tree itself is a LATER phase; this record already stores the {@code allocations} map so
 * the data model is stable when the tree arrives — {@link #allocate(String)} is wired but
 * nothing spends into it yet in Phase 1.
 *
 * <h2>XP / level curve</h2>
 * Deliberately the SAME gentle super-linear curve as the global track:
 * {@link #xpForLevel(int)} delegates to {@link PlayerProgress#xpForLevel(int)} so a class
 * level costs exactly what a global level does. {@link #addXp(int)} banks XP and rolls as
 * many level-ups as the deposit covers, granting one class point per level.
 */
public class ClassProgress {

	/** XP banked toward the NEXT class level (progress within the current level). */
	private int xp;
	/** Current class level; a freshly-adopted class starts at level 1. */
	private int level;
	/** Class skill points earned (one per class level) but not yet spent. */
	private int unspentPoints;
	/**
	 * Points spent per class-skill id. The skill TREE is a later phase; this map is the
	 * durable home for its allocations so the storage shape never has to change. Keyed by
	 * a stable skill-id string. Insertion-ordered for stable NBT/UI iteration.
	 */
	private final Map<String, Integer> allocations = new LinkedHashMap<>();

	public ClassProgress() {
		this.xp = 0;
		this.level = 1;
		this.unspentPoints = 0;
	}

	// ---- curve -------------------------------------------------------------
	/**
	 * XP required to advance FROM {@code level} to {@code level + 1}. Delegates to the
	 * global curve ({@link PlayerProgress#xpForLevel(int)}) so class and character levels
	 * share one identical, pure ramp — no drift if the global curve is ever retuned.
	 */
	public static int xpForLevel(int level) {
		return PlayerProgress.xpForLevel(level);
	}

	// ---- mutation ----------------------------------------------------------
	/**
	 * Bank {@code amount} class XP, rolling as many level-ups as it covers. Each level-up
	 * carries the overflow forward and grants exactly one unspent class point.
	 * Negative/zero deposits are ignored.
	 *
	 * @return the number of class levels gained (0 if none) so callers can fire feedback
	 */
	public int addXp(int amount) {
		if (amount <= 0) {
			return 0;
		}
		xp += amount;
		int gained = 0;
		while (xp >= xpForLevel(level)) {
			xp -= xpForLevel(level);
			level++;
			unspentPoints++;
			gained++;
		}
		return gained;
	}

	/**
	 * Spend one unspent class point on {@code skill}. No-op (returns {@code false}) when
	 * the player has no class points banked — the server validates before applying. The
	 * class skill TREE that gives {@code skill} meaning is a later phase; the plumbing is
	 * here now so it lands without a data migration.
	 */
	public boolean allocate(String skill) {
		if (unspentPoints <= 0 || skill == null || skill.isEmpty()) {
			return false;
		}
		allocations.merge(skill, 1, Integer::sum);
		unspentPoints--;
		return true;
	}

	// ---- accessors ---------------------------------------------------------
	public int getXp() {
		return xp;
	}

	public int getLevel() {
		return level;
	}

	public int getUnspentPoints() {
		return unspentPoints;
	}

	/** Points spent into a class skill (0 if none). */
	public int points(String skill) {
		return allocations.getOrDefault(skill, 0);
	}

	/** A read-only view of the class-skill allocation map (for UI / debugging). */
	public Map<String, Integer> allocations() {
		return java.util.Collections.unmodifiableMap(allocations);
	}

	// ---- serialization -----------------------------------------------------
	/** Serialize this class track to a fresh compound (xp/level/points + a per-skill sub-tag). */
	public NbtCompound writeNbt() {
		NbtCompound nbt = new NbtCompound();
		nbt.putInt("xp", xp);
		nbt.putInt("level", level);
		nbt.putInt("points", unspentPoints);
		NbtCompound alloc = new NbtCompound();
		for (Map.Entry<String, Integer> e : allocations.entrySet()) {
			alloc.putInt(e.getKey(), e.getValue());
		}
		nbt.put("alloc", alloc);
		return nbt;
	}

	/** Rebuild a class track from NBT written by {@link #writeNbt()} (defaults for missing keys). */
	public static ClassProgress readNbt(NbtCompound nbt) {
		ClassProgress c = new ClassProgress();
		c.xp = Math.max(0, nbt.getInt("xp", 0));
		c.level = Math.max(1, nbt.getInt("level", 1));
		c.unspentPoints = Math.max(0, nbt.getInt("points", 0));
		NbtCompound alloc = nbt.getCompoundOrEmpty("alloc");
		for (String key : alloc.getKeys()) {
			int v = Math.max(0, alloc.getInt(key, 0));
			if (v > 0) {
				c.allocations.put(key, v);
			}
		}
		return c;
	}
}
