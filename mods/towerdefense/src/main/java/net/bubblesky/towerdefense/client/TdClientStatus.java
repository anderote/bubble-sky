package net.bubblesky.towerdefense.client;

import net.bubblesky.towerdefense.entity.TdEnemyEntity;
import net.bubblesky.towerdefense.registry.ModItems;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.ScoreboardObjective;

/**
 * Client-side, read-only view of the tower-defense match, derived entirely from
 * state the vanilla protocol already syncs to the client — no custom packets:
 *
 * <ul>
 *   <li><b>coins</b>: counted from the client player's own (synced) inventory,
 *       exactly as the server {@code /td shop} counts them;</li>
 *   <li><b>wave</b>: read from the synced {@code td_sidebar} scoreboard objective
 *       that {@link net.bubblesky.towerdefense.game.TdHud} publishes;</li>
 *   <li><b>enemies</b>: counted from the client world's loaded {@link TdEnemyEntity}
 *       instances (the custom TD mobs are synced as normal entities);</li>
 *   <li><b>active</b>: true when the {@code td_sidebar} objective exists, i.e. a
 *       match is running (the server HUD is up).</li>
 * </ul>
 *
 * <p>These feed both the {@link net.bubblesky.towerdefense.client.screen.TowerDefenseScreen}
 * status line and the always-on discoverability HUD hint.
 */
public final class TdClientStatus {
	private TdClientStatus() {
	}

	/** Must match {@code TdHud.SIDEBAR_OBJECTIVE}. */
	private static final String SIDEBAR_OBJECTIVE = "td_sidebar";
	/** Must match {@code TdHud.WAVE_ROW}. */
	private static final String WAVE_ROW = "Wave";

	/** The client player's coin balance (synced inventory), or 0. */
	public static int coins() {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player == null) {
			return 0;
		}
		int total = 0;
		for (ItemStack stack : player.getInventory().getMainStacks()) {
			if (stack.isOf(ModItems.COIN)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** Current wave from the synced TD sidebar, or -1 when no match is running. */
	public static int wave() {
		ScoreboardObjective obj = sidebar();
		if (obj == null) {
			return -1;
		}
		Scoreboard sb = MinecraftClient.getInstance().world.getScoreboard();
		ReadableScoreboardScore score = sb.getScore(ScoreHolder.fromName(WAVE_ROW), obj);
		return score == null ? -1 : score.getScore();
	}

	/** Count of TD enemy mobs currently loaded/visible on the client. */
	public static int enemiesVisible() {
		ClientWorld world = MinecraftClient.getInstance().world;
		if (world == null) {
			return 0;
		}
		int count = 0;
		for (Entity e : world.getEntities()) {
			if (e instanceof TdEnemyEntity && e.isAlive()) {
				count++;
			}
		}
		return count;
	}

	/** True when a TD match is running (the server sidebar HUD is present). */
	public static boolean active() {
		return sidebar() != null;
	}

	private static ScoreboardObjective sidebar() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null) {
			return null;
		}
		return mc.world.getScoreboard().getNullableObjective(SIDEBAR_OBJECTIVE);
	}
}
