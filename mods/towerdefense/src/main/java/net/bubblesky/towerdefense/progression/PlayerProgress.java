package net.bubblesky.towerdefense.progression;

import java.util.EnumMap;
import net.minecraft.nbt.NbtCompound;

/**
 * The per-player RPG record: experience, level, unspent skill points, and how many
 * points have been sunk into each {@link Stat}. This is a plain server-side POJO —
 * the single source of truth for one player's PERMANENT progression, held in the
 * world-saved {@link ProgressState} and serialized to/from NBT.
 *
 * <p>Progression is permanent: it persists across sessions AND deaths (death is
 * ordinary survival — drop items, respawn, keep level/stats). Nothing here resets on
 * death; the record only ever grows (XP in, points spent).
 *
 * <h2>XP / level curve</h2>
 * Levels start at 1. {@link #xpForLevel(int)} gives the XP needed to advance FROM a
 * given level to the next: {@code 50 + 25*(n-1) + 4*(n-1)^2} — a gentle super-linear
 * ramp (50 to reach L2, 79 for L3, 116 for L4, ...). {@link #addXp(int)} banks XP and
 * rolls as many level-ups as the deposit covers, granting one skill point per level.
 */
public class PlayerProgress {

	/**
	 * The five RPG stats a skill point can be spent on. Three drive vanilla attribute
	 * modifiers ({@link StatModifiers}); {@link #MARKSMANSHIP} and {@link #FORTUNE} are
	 * read at use-time as multipliers (bow damage / coin payout) rather than attributes.
	 * The ordinal order is the wire order used by the sync payload, so DO NOT reorder.
	 */
	public enum Stat {
		VITALITY,
		STRENGTH,
		AGILITY,
		MARKSMANSHIP,
		FORTUNE
	}

	// ---- state -------------------------------------------------------------
	/** XP banked toward the NEXT level (i.e. progress within the current level). */
	private int xp;
	/** Current level; players start at level 1. */
	private int level;
	/** Skill points earned (one per level) but not yet spent. */
	private int unspentPoints;
	/** Points spent per stat. Missing stats read as 0 via {@link #points(Stat)}. */
	private final EnumMap<Stat, Integer> allocations = new EnumMap<>(Stat.class);

	public PlayerProgress() {
		this.xp = 0;
		this.level = 1;
		this.unspentPoints = 0;
	}

	// ---- curve -------------------------------------------------------------
	/**
	 * XP required to advance FROM {@code level} to {@code level + 1}:
	 * {@code 50 + 25*(n-1) + 4*(n-1)^2}. A pure function (no state) so the client
	 * HUD/screen can compute the same threshold from a synced level.
	 */
	public static int xpForLevel(int level) {
		int m = Math.max(0, level - 1);
		return 50 + 25 * m + 4 * m * m;
	}

	// ---- mutation ----------------------------------------------------------
	/**
	 * Bank {@code amount} XP, rolling as many level-ups as it covers. Each level-up
	 * carries the overflow XP forward and grants exactly one unspent skill point.
	 * Negative/zero deposits are ignored.
	 *
	 * @return the number of levels gained (0 if none) so callers can fire feedback
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
	 * Spend one unspent point on {@code stat}. No-op (returns {@code false}) when the
	 * player has no points banked — the server validates this before applying anything.
	 */
	public boolean allocate(Stat stat) {
		if (unspentPoints <= 0) {
			return false;
		}
		allocations.merge(stat, 1, Integer::sum);
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

	/** Points spent into a stat (0 if none). */
	public int points(Stat stat) {
		return allocations.getOrDefault(stat, 0);
	}

	// ---- serialization -----------------------------------------------------
	/** Serialize this record to a fresh compound (xp/level/points + a per-stat sub-tag). */
	public NbtCompound writeNbt() {
		NbtCompound nbt = new NbtCompound();
		nbt.putInt("xp", xp);
		nbt.putInt("level", level);
		nbt.putInt("points", unspentPoints);
		NbtCompound alloc = new NbtCompound();
		for (Stat stat : Stat.values()) {
			alloc.putInt(stat.name(), points(stat));
		}
		nbt.put("alloc", alloc);
		return nbt;
	}

	/** Rebuild a record from NBT written by {@link #writeNbt()} (defaults for missing keys). */
	public static PlayerProgress readNbt(NbtCompound nbt) {
		PlayerProgress p = new PlayerProgress();
		p.xp = Math.max(0, nbt.getInt("xp", 0));
		p.level = Math.max(1, nbt.getInt("level", 1));
		p.unspentPoints = Math.max(0, nbt.getInt("points", 0));
		NbtCompound alloc = nbt.getCompoundOrEmpty("alloc");
		for (Stat stat : Stat.values()) {
			int v = Math.max(0, alloc.getInt(stat.name(), 0));
			if (v > 0) {
				p.allocations.put(stat, v);
			}
		}
		return p;
	}
}
