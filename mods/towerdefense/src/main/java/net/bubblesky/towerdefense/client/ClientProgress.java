package net.bubblesky.towerdefense.client;

import net.bubblesky.towerdefense.progression.PlayerProgress;
import net.bubblesky.towerdefense.progression.PlayerProgress.Stat;
import net.bubblesky.towerdefense.progression.net.ProgressSyncPayload;

/**
 * Client-side cache of the last {@link ProgressSyncPayload} the server pushed. Feeds
 * both the always-on HUD line and the {@link net.bubblesky.towerdefense.client.screen.CharacterScreen}.
 * All state is a plain snapshot; the client never mutates progression locally (the
 * server is authoritative and resyncs after every change).
 */
public final class ClientProgress {

	private ClientProgress() {
	}

	private static volatile int xp = 0;
	private static volatile int level = 1;
	private static volatile int unspentPoints = 0;
	private static volatile int gold = 0;
	private static volatile int[] allocations = new int[Stat.values().length];
	// ---- class layer (mirrors the appended ProgressSyncPayload fields) ----
	private static volatile int mana = 0;
	private static volatile int maxMana = 0;
	private static volatile String activeClass = "";
	private static volatile int classLevel = 0;
	private static volatile int classXp = 0;
	private static volatile int classPoints = 0;

	/** Replace the cached snapshot from a freshly-received sync payload. */
	public static void update(ProgressSyncPayload payload) {
		xp = payload.xp();
		level = payload.level();
		unspentPoints = payload.unspentPoints();
		gold = payload.gold();
		int[] incoming = payload.allocations();
		int[] copy = new int[Stat.values().length];
		System.arraycopy(incoming, 0, copy, 0, Math.min(incoming.length, copy.length));
		allocations = copy;
		mana = payload.mana();
		maxMana = payload.maxMana();
		activeClass = payload.activeClass() == null ? "" : payload.activeClass();
		classLevel = payload.classLevel();
		classXp = payload.classXp();
		classPoints = payload.classPoints();
	}

	public static int xp() {
		return xp;
	}

	/** The synced gold-bank balance shown on the HUD. */
	public static int gold() {
		return gold;
	}

	public static int level() {
		return level;
	}

	public static int unspentPoints() {
		return unspentPoints;
	}

	/** XP required to reach the next level from the current one. */
	public static int xpForNextLevel() {
		return PlayerProgress.xpForLevel(level);
	}

	/** Points spent into a stat (0 if out of range). */
	public static int points(Stat stat) {
		int[] a = allocations;
		int i = stat.ordinal();
		return i < a.length ? a[i] : 0;
	}

	/** Fraction [0,1] of the way through the current level, for the XP bar. */
	public static float xpFraction() {
		int need = xpForNextLevel();
		if (need <= 0) {
			return 0f;
		}
		return Math.max(0f, Math.min(1f, xp / (float) need));
	}

	// ---- class layer accessors (feed the Phase-2 mana bar / class readouts) ----
	/** Current synced mana. */
	public static int mana() {
		return mana;
	}

	/** Current synced max mana (derived server-side from Intelligence). */
	public static int maxMana() {
		return maxMana;
	}

	/** Fraction [0,1] of the mana pool that is filled, for the mana bar. */
	public static float manaFraction() {
		if (maxMana <= 0) {
			return 0f;
		}
		return Math.max(0f, Math.min(1f, mana / (float) maxMana));
	}

	/** The active class id ({@code ""} when the player has not picked a class). */
	public static String activeClass() {
		return activeClass;
	}

	/** Whether the player currently has an active class. */
	public static boolean hasClass() {
		return !activeClass.isEmpty();
	}

	/** The active class's own level (0 when unclassed). */
	public static int classLevel() {
		return classLevel;
	}

	/** The active class's within-level xp (0 when unclassed). */
	public static int classXp() {
		return classXp;
	}

	/** The active class's unspent class points (0 when unclassed). */
	public static int classPoints() {
		return classPoints;
	}
}
