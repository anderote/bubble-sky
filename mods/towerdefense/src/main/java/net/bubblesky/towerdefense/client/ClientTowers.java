package net.bubblesky.towerdefense.client;

import java.util.List;
import net.bubblesky.towerdefense.towerui.net.TowerRosterPayload;

/** Client-side cache of the last tower-roster snapshot from the server. */
public final class ClientTowers {
	private static volatile int coins = 0;
	private static volatile List<TowerRosterPayload.Row> rows = List.of();

	private ClientTowers() {
	}

	public static void update(TowerRosterPayload payload) {
		coins = payload.coins();
		rows = List.copyOf(payload.rows());
	}

	public static int coins() {
		return coins;
	}

	public static List<TowerRosterPayload.Row> rows() {
		return rows;
	}
}
