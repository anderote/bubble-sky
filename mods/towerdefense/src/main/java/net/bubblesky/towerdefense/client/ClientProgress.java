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
}
