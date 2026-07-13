package net.bubblesky.towerdefense.game;

import java.util.List;
import net.bubblesky.towerdefense.state.TdArenaState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.scoreboard.number.StyledNumberFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Server-driven, vanilla-UI HUD for the tower-defense match. No client mixin is
 * required — everything here is standard server-authoritative UI that any client
 * (modded or vanilla) renders:
 *
 * <ul>
 *   <li>a {@link ServerBossBar} at the top of the screen showing the current wave
 *       and the base's HP (the bar itself is base HP %, coloured green/yellow/red
 *       by remaining health);</li>
 *   <li>a second, purple {@link ServerBossBar} that appears only while a boss is
 *       alive, tracking the boss's health;</li>
 *   <li>a scoreboard SIDEBAR listing each online arena player's coin balance plus
 *       the current wave number, refreshed periodically.</li>
 * </ul>
 *
 * <p>Driven from its own {@link ServerTickEvents#END_SERVER_TICK} hook so it can
 * show/hide independently of the {@link WaveManager} (which early-returns when
 * there is no active game). The bar viewer set is reconciled each second, and the
 * sidebar is only rebuilt once a second to stay lightweight.
 */
public final class TdHud {
	private TdHud() {
	}

	/** How often (ticks) the viewer set and sidebar are rebuilt. */
	private static final int REFRESH_INTERVAL = 20;
	/** Sidebar objective name (internal id). */
	private static final String SIDEBAR_OBJECTIVE = "td_sidebar";
	/** Score-holder name used for the wave row on the sidebar. */
	private static final String WAVE_ROW = "Wave";

	private static ServerBossBar baseBar;
	private static ServerBossBar bossBar;
	private static int tickCounter;

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(TdHud::onEndTick);
	}

	private static void onEndTick(MinecraftServer server) {
		if (baseBar == null) {
			baseBar = new ServerBossBar(Text.literal("Tower Defense"),
				BossBar.Color.GREEN, BossBar.Style.NOTCHED_10);
			baseBar.setVisible(false);
			bossBar = new ServerBossBar(Text.literal("Boss"),
				BossBar.Color.PURPLE, BossBar.Style.NOTCHED_20);
			bossBar.setVisible(false);
		}

		TdArenaState st = TdArenaState.get(server);
		ServerWorld world = st.getArenaWorld(server);

		// No live match (no base, lost, or unknown world): hide all HUD.
		if (!st.isActiveArena() || world == null) {
			if (baseBar.isVisible()) {
				baseBar.setVisible(false);
				baseBar.clearPlayers();
			}
			if (bossBar.isVisible()) {
				bossBar.setVisible(false);
				bossBar.clearPlayers();
			}
			clearSidebar(server);
			return;
		}

		updateBaseBar(world, st);
		updateBossBar(world, st);

		if (tickCounter++ % REFRESH_INTERVAL == 0) {
			reconcileViewers(world, baseBar);
			updateSidebar(server, world, st);
		}
	}

	// ---- base bossbar ------------------------------------------------------
	private static void updateBaseBar(ServerWorld world, TdArenaState st) {
		float pct = st.baseMaxHp <= 0 ? 0f
			: Math.max(0f, Math.min(1f, (float) st.baseHp / st.baseMaxHp));
		baseBar.setPercent(pct);
		baseBar.setColor(pct > 0.5f ? BossBar.Color.GREEN
			: pct > 0.25f ? BossBar.Color.YELLOW : BossBar.Color.RED);
		String phase = switch (st.phase) {
			case SPAWNING, ACTIVE -> "Wave " + st.currentWave;
			case INTERMISSION -> "Wave " + st.currentWave + " cleared — next incoming";
			case IDLE -> st.currentWave == 0 ? "Ready — /td wave" : "Wave " + st.currentWave + " done";
		};
		baseBar.setName(Text.literal(phase + "  —  Idol " + st.baseHp + "/" + st.baseMaxHp)
			.formatted(Formatting.GOLD));
		if (!baseBar.isVisible()) {
			baseBar.setVisible(true);
		}
	}

	// ---- boss bossbar ------------------------------------------------------
	/**
	 * A boss wave now fields a whole SQUAD of Warlords, so the single purple bar shows
	 * an AGGREGATE: its fill is the squad's total remaining health over total max
	 * health, and its label names the squad (with a live count). This tolerates any
	 * number of live bosses — one, a dozen, or none — without crashing.
	 */
	private static void updateBossBar(ServerWorld world, TdArenaState st) {
		List<LivingEntity> bosses = findBosses(world, st);
		if (bosses.isEmpty()) {
			if (bossBar.isVisible()) {
				bossBar.setVisible(false);
				bossBar.clearPlayers();
			}
			return;
		}
		float totalMax = 0f;
		float totalCur = 0f;
		for (LivingEntity boss : bosses) {
			totalMax += boss.getMaxHealth();
			totalCur += boss.getHealth();
		}
		float pct = totalMax <= 0f ? 0f : Math.max(0f, Math.min(1f, totalCur / totalMax));
		bossBar.setPercent(pct);
		if (bosses.size() == 1) {
			Text name = bosses.get(0).getCustomName();
			bossBar.setName(name != null ? name
				: Text.literal("Warlord — Wave " + st.currentWave).formatted(Formatting.DARK_PURPLE));
		} else {
			bossBar.setName(Text.literal("Warlords (" + bosses.size() + ") — Wave " + st.currentWave)
				.formatted(Formatting.DARK_PURPLE));
		}
		if (!bossBar.isVisible()) {
			bossBar.setVisible(true);
		}
		reconcileViewers(world, bossBar);
	}

	/** Every live tagged boss near the base (empty if none). */
	private static List<LivingEntity> findBosses(ServerWorld world, TdArenaState st) {
		if (st.base == null) {
			return List.of();
		}
		List<Entity> found = world.getOtherEntities(null,
			new net.minecraft.util.math.Box(st.base).expand(128.0),
			e -> e.isAlive() && e.getCommandTags().contains(WaveManager.BOSS_TAG));
		List<LivingEntity> bosses = new java.util.ArrayList<>();
		for (Entity e : found) {
			if (e instanceof LivingEntity le) {
				bosses.add(le);
			}
		}
		return bosses;
	}

	// ---- viewer reconciliation --------------------------------------------
	/** Add every arena-world player as a viewer; drop any who left the world. */
	private static void reconcileViewers(ServerWorld world, ServerBossBar bar) {
		for (ServerPlayerEntity player : world.getPlayers()) {
			bar.addPlayer(player);
		}
		// Remove viewers no longer in this world (e.g. changed dimension / left).
		for (ServerPlayerEntity viewer : List.copyOf(bar.getPlayers())) {
			if (viewer.isRemoved() || viewer.getWorld() != world) {
				bar.removePlayer(viewer);
			}
		}
	}

	// ---- sidebar -----------------------------------------------------------
	private static void updateSidebar(MinecraftServer server, ServerWorld world, TdArenaState st) {
		ServerScoreboard sb = server.getScoreboard();
		ScoreboardObjective obj = sb.getNullableObjective(SIDEBAR_OBJECTIVE);
		if (obj == null) {
			obj = sb.addObjective(SIDEBAR_OBJECTIVE, ScoreboardCriterion.DUMMY,
				Text.literal("Tower Defense").formatted(Formatting.GOLD, Formatting.BOLD),
				ScoreboardCriterion.RenderType.INTEGER, true, StyledNumberFormat.EMPTY);
		}
		sb.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, obj);

		// Wave row (sorts to the top by giving it the highest score).
		ScoreAccess waveScore = sb.getOrCreateScore(ScoreHolder.fromName(WAVE_ROW), obj);
		waveScore.setScore(st.currentWave);

		// One row per online arena player: their coin balance.
		for (ServerPlayerEntity player : world.getPlayers()) {
			ScoreAccess score = sb.getOrCreateScore(player, obj);
			score.setScore(countCoins(player));
		}
	}

	private static void clearSidebar(MinecraftServer server) {
		ServerScoreboard sb = server.getScoreboard();
		ScoreboardObjective obj = sb.getNullableObjective(SIDEBAR_OBJECTIVE);
		if (obj != null) {
			sb.removeObjective(obj);
		}
	}

	/** The player's sidebar gold figure — now their BANK balance, not inventory coins. */
	private static int countCoins(ServerPlayerEntity player) {
		return net.bubblesky.towerdefense.command.TdCommand.countCoinsPublic(player);
	}
}
