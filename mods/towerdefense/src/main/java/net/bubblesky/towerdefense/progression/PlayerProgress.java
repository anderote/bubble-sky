package net.bubblesky.towerdefense.progression;

import java.util.EnumMap;
import java.util.Map;
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
	 * The seven RPG stats a skill point can be spent on. {@link #VITALITY},
	 * {@link #STRENGTH}, {@link #AGILITY}, and {@link #RESILIENCE} drive vanilla
	 * attribute modifiers ({@link StatModifiers}); {@link #MARKSMANSHIP},
	 * {@link #FORTUNE}, and {@link #INTELLIGENCE} are read at use-time as multipliers
	 * (bow damage / coin payout / XP gain) rather than attributes.
	 * The ordinal order is the wire order used by the sync payload, so DO NOT reorder —
	 * {@link #INTELLIGENCE} and {@link #RESILIENCE} were appended after the original
	 * five so existing saves/allocations stay valid.
	 */
	public enum Stat {
		VITALITY,
		STRENGTH,
		AGILITY,
		MARKSMANSHIP,
		FORTUNE,
		INTELLIGENCE,
		RESILIENCE
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
	/**
	 * The player's GOLD BANK: a plain int balance that is the single source of truth for
	 * how much gold this player has to spend. Gold is NO LONGER carried as {@code COIN}
	 * items in the inventory — coins still drop in the world, but are vacuumed up and
	 * credited here (see the auto-collect sweep in
	 * {@link net.bubblesky.towerdefense.progression.ProgressEvents}). Persisted to NBT
	 * and synced to the client HUD exactly like the rest of this record.
	 */
	private int gold;

	// ---- class / spellcasting layer (per-life + per-class) -----------------
	// Additive on top of the permanent global track above. See PlayerClass / ClassProgress.
	/**
	 * The class the player is currently playing this life, or {@code null} if they have
	 * not picked one yet. Set on selection ({@code /td class} or the SelectClass payload);
	 * drives the loadout, the {@link StatModifiers} per-life bias, and which
	 * {@link ClassProgress} track earns class XP.
	 */
	private PlayerClass activeClass;
	/**
	 * Independent progression track per class, created lazily via
	 * {@link #classProgress(PlayerClass)}. Switching classes preserves each track.
	 */
	private final EnumMap<PlayerClass, ClassProgress> classProgress = new EnumMap<>(PlayerClass.class);
	/** Current mana pool (spell fuel). Clamped to {@code [0, maxMana]}. Phase 2 spends it. */
	private int mana;
	/**
	 * Maximum mana. DERIVED from Intelligence (+ active-class bias) via
	 * {@link StatModifiers#maxMana(PlayerProgress)} and refreshed by
	 * {@link #refreshMaxMana()} whenever stats/class change; stored so it can be synced.
	 */
	private int maxMana;
	/**
	 * Per-player ESSENCE currency (a per-life loot resource, distinct from the gold bank).
	 * Phase 3 wires the drops + spend sinks; Phase 1 just carries the balance.
	 */
	private int essence;

	public PlayerProgress() {
		this.xp = 0;
		this.level = 1;
		this.unspentPoints = 0;
		this.gold = 0;
		this.activeClass = null;
		this.mana = 0;
		this.maxMana = 0;
		this.essence = 0;
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

	// ---- gold bank ---------------------------------------------------------
	/** Current gold-bank balance (never negative). */
	public int getGold() {
		return gold;
	}

	/** Overwrite the gold balance outright (clamped at 0). Used to seed starter gold. */
	public void setGold(int amount) {
		gold = Math.max(0, amount);
	}

	/** Credit {@code amount} gold to the bank (non-positive amounts are ignored). */
	public void addGold(int amount) {
		if (amount > 0) {
			gold += amount;
		}
	}

	/**
	 * Spend {@code amount} gold from the bank. Deducts only what is actually present
	 * (never drives the balance below 0) and reports whether the full amount cleared.
	 *
	 * @return {@code true} if the whole {@code amount} was covered, {@code false} otherwise
	 */
	public boolean spendGold(int amount) {
		if (amount <= 0) {
			return true;
		}
		if (gold < amount) {
			gold = 0;
			return false;
		}
		gold -= amount;
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

	// ---- class layer -------------------------------------------------------
	/** The class the player is playing this life, or {@code null} if unpicked. */
	public PlayerClass getActiveClass() {
		return activeClass;
	}

	/**
	 * Set (or clear, with {@code null}) the active class. This is a plain field write —
	 * granting the loadout, applying the per-life bias, and resyncing are the caller's job
	 * (see {@code ClassLoadout.select} / the {@code /td class} command path), so this POJO
	 * stays free of any {@code ServerPlayerEntity} dependency.
	 */
	public void setActiveClass(PlayerClass cls) {
		this.activeClass = cls;
	}

	/**
	 * Get-or-create this player's independent progression track for {@code cls}. A brand
	 * new track starts at level 1 with zero XP; the returned instance is live (mutating it
	 * mutates the stored track), so callers must {@code markDirty()} their store after
	 * changing it.
	 */
	public ClassProgress classProgress(PlayerClass cls) {
		return classProgress.computeIfAbsent(cls, k -> new ClassProgress());
	}

	// ---- mana pool ---------------------------------------------------------
	/** Current mana (spell fuel), always within {@code [0, maxMana]}. */
	public int getMana() {
		return mana;
	}

	/** Overwrite the current mana, clamped to {@code [0, maxMana]}. */
	public void setMana(int amount) {
		this.mana = Math.max(0, Math.min(amount, maxMana));
	}

	/** Add {@code amount} mana (may be negative to spend), clamped to {@code [0, maxMana]}. */
	public void addMana(int amount) {
		setMana(this.mana + amount);
	}

	/** Maximum mana (derived from Intelligence; see {@link #refreshMaxMana()}). */
	public int getMaxMana() {
		return maxMana;
	}

	/** Overwrite the max-mana cap directly (clamped at 0); also re-clamps current mana. */
	public void setMaxMana(int amount) {
		this.maxMana = Math.max(0, amount);
		if (mana > maxMana) {
			mana = maxMana;
		}
	}

	/**
	 * Recompute {@link #maxMana} from Intelligence (+ active-class bias) via
	 * {@link StatModifiers#maxMana(PlayerProgress)}, re-clamping current mana to the new
	 * cap. Called from {@link StatModifiers#apply} so the pool tracks stat/class changes.
	 * For Phase 1 (no regen yet) an empty pool is topped up to full so the HUD shows a
	 * meaningful bar; Phase 2's server-side regen supersedes this behavior.
	 */
	public void refreshMaxMana() {
		this.maxMana = StatModifiers.maxMana(this);
		if (mana > maxMana) {
			mana = maxMana;
		}
		if (mana <= 0) {
			mana = maxMana;
		}
	}

	// ---- essence currency --------------------------------------------------
	/** Current essence balance (never negative). */
	public int getEssence() {
		return essence;
	}

	/** Credit {@code amount} essence (non-positive amounts ignored). */
	public void addEssence(int amount) {
		if (amount > 0) {
			essence += amount;
		}
	}

	/**
	 * Spend {@code amount} essence, deducting only what is present (never below 0).
	 *
	 * @return {@code true} if the whole {@code amount} cleared, {@code false} otherwise
	 */
	public boolean spendEssence(int amount) {
		if (amount <= 0) {
			return true;
		}
		if (essence < amount) {
			essence = 0;
			return false;
		}
		essence -= amount;
		return true;
	}

	// ---- serialization -----------------------------------------------------
	/** Serialize this record to a fresh compound (xp/level/points + a per-stat sub-tag). */
	public NbtCompound writeNbt() {
		NbtCompound nbt = new NbtCompound();
		nbt.putInt("xp", xp);
		nbt.putInt("level", level);
		nbt.putInt("points", unspentPoints);
		nbt.putInt("gold", gold);
		NbtCompound alloc = new NbtCompound();
		for (Stat stat : Stat.values()) {
			alloc.putInt(stat.name(), points(stat));
		}
		nbt.put("alloc", alloc);

		// ---- class / spellcasting layer (additive; older saves simply omit these) ----
		// Active class stored by stable id string ("" = unpicked) so enum reorders are safe.
		nbt.putString("activeClass", activeClass == null ? "" : activeClass.id());
		nbt.putInt("mana", mana);
		nbt.putInt("maxMana", maxMana);
		nbt.putInt("essence", essence);
		// One sub-tag per class that has ANY progress, keyed by the class id.
		NbtCompound classes = new NbtCompound();
		for (Map.Entry<PlayerClass, ClassProgress> e : classProgress.entrySet()) {
			classes.put(e.getKey().id(), e.getValue().writeNbt());
		}
		nbt.put("classProgress", classes);
		return nbt;
	}

	/** Rebuild a record from NBT written by {@link #writeNbt()} (defaults for missing keys). */
	public static PlayerProgress readNbt(NbtCompound nbt) {
		PlayerProgress p = new PlayerProgress();
		p.xp = Math.max(0, nbt.getInt("xp", 0));
		p.level = Math.max(1, nbt.getInt("level", 1));
		p.unspentPoints = Math.max(0, nbt.getInt("points", 0));
		p.gold = Math.max(0, nbt.getInt("gold", 0));
		NbtCompound alloc = nbt.getCompoundOrEmpty("alloc");
		for (Stat stat : Stat.values()) {
			int v = Math.max(0, alloc.getInt(stat.name(), 0));
			if (v > 0) {
				p.allocations.put(stat, v);
			}
		}

		// ---- class / spellcasting layer (robust to missing keys on older saves) ----
		p.activeClass = PlayerClass.fromId(nbt.getString("activeClass", "")); // null if "" / unknown
		p.mana = Math.max(0, nbt.getInt("mana", 0));
		p.maxMana = Math.max(0, nbt.getInt("maxMana", 0));
		p.essence = Math.max(0, nbt.getInt("essence", 0));
		NbtCompound classes = nbt.getCompoundOrEmpty("classProgress");
		for (String key : classes.getKeys()) {
			PlayerClass cls = PlayerClass.fromId(key);
			if (cls != null) {
				p.classProgress.put(cls, ClassProgress.readNbt(classes.getCompoundOrEmpty(key)));
			}
		}
		return p;
	}
}
