package net.bubblesky.towerdefense.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.bubblesky.towerdefense.entity.TdEnemyEntity;
import net.bubblesky.towerdefense.registry.ModEntities;
import net.bubblesky.towerdefense.state.TdArenaState;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * The server-side "Enemy AI Warlord" contract (block #17a of the swarm design).
 *
 * <p>This is the <em>mod-side interface</em> an EXTERNAL agent (the Node "warlord.js",
 * block #17b) drives over the HTTP bridge. It holds three things and no game policy of
 * its own:
 * <ul>
 *   <li>the <b>pending wave plan</b> — a per-wave composition + spawn emphasis + tactic
 *       + taunt the agent submits (see {@link WavePlan});</li>
 *   <li>the <b>threat model</b> — a per-enemy-type {@link #threatCost} and a per-wave
 *       {@link #budgetFor(int) budget} derived from the wave manager's OWN default
 *       composition, plus {@link #validateAndClamp} which bounds any submitted plan to
 *       ~that budget so the agent can only <em>redistribute</em>, never trivialise or
 *       nuke a wave;</li>
 *   <li>the <b>last-wave telemetry</b> — spawned / leaked / killed-by-towers /
 *       killed-by-players / duration, accumulated live as a wave runs and finalised on
 *       wave end (see {@link WaveTelemetry}).</li>
 * </ul>
 *
 * <p><b>Additive + graceful fallback.</b> If no plan is submitted for a wave, the
 * {@link WaveManager} takes its unchanged default path and the wave is byte-for-byte
 * identical to today. Nothing here calls out to an LLM — that lives entirely in the
 * external agent.
 *
 * <p><b>Threading.</b> The bridge HTTP handlers run OFF the server thread; the wave
 * logic and death hook run ON it. Pending plans live in a {@link ConcurrentHashMap}
 * (submitted off-thread, consumed on-thread). The live telemetry counters are only ever
 * mutated on the server thread; the finished {@link #lastWave} snapshot is an immutable
 * record published through a {@code volatile} field so the bridge can read it off-thread
 * without locking. {@link #validateAndClamp} and {@link #budgetFor} are pure (they read
 * only static tables and the wave manager's pure scaling functions) so they are safe to
 * call from either thread.
 */
public final class WarlordDirector {

	// ---- singleton ---------------------------------------------------------
	private static final WarlordDirector INSTANCE = new WarlordDirector();

	/** The process-wide director instance (state is per-server-run but a single JVM hosts one). */
	public static WarlordDirector get() {
		return INSTANCE;
	}

	// ---- stable enemy-id contract ------------------------------------------
	/**
	 * The STABLE, documented enemy-id strings the Warlord composes waves from, mapped to
	 * the actual {@link EntityType} the {@link WaveManager} spawns. This is the single
	 * source of truth shared by 17a (here) and 17b (the agent) — keep the keys stable.
	 *
	 * <p>{@code "boss"} is intentionally NOT in this map: a boss is spawned through the
	 * wave manager's dedicated {@link WaveManager#spawnBoss} path (as a scaled
	 * {@code heavy_knight} carrying the boss tag), so it is handled specially by
	 * {@link #plannedBosses}. All other ids map straight to a roster type.
	 */
	private static final Map<String, EntityType<? extends TdEnemyEntity>> ID_TO_TYPE = new LinkedHashMap<>();
	/** Per-enemy-type THREAT COST — the "points" a type costs against the wave budget. */
	private static final Map<String, Double> THREAT = new LinkedHashMap<>();
	/** Reverse map (roster {@link EntityType} → id) used to price the default composition. */
	private static final Map<EntityType<?>, String> TYPE_TO_ID = new LinkedHashMap<>();

	/** Special enemy id for a boss unit (spawned via {@link WaveManager#spawnBoss}). */
	public static final String BOSS_ID = "boss";

	static {
		put("goblin", ModEntities.GOBLIN_SKIRMISHER, 1.0);
		put("footman", ModEntities.FOOTMAN, 1.0);
		put("archer", ModEntities.ARCHER, 1.5);
		put("man_at_arms", ModEntities.MAN_AT_ARMS, 2.0);
		put("undead", ModEntities.UNDEAD_SOLDIER, 2.0);
		put("knight", ModEntities.HEAVY_KNIGHT, 3.0);
		put("barbarian", ModEntities.BARBARIAN, 2.0);
		put("sapper", ModEntities.BARBARIAN_SAPPER, 3.0);
		// The boss has a cost but no direct roster mapping (spawned via spawnBoss).
		THREAT.put(BOSS_ID, 8.0);
	}

	private static void put(String id, EntityType<? extends TdEnemyEntity> type, double cost) {
		ID_TO_TYPE.put(id, type);
		THREAT.put(id, cost);
		TYPE_TO_ID.put(type, id);
	}

	// ---- clamp tuning ------------------------------------------------------
	/** A submitted plan's total threat may exceed the wave's default budget by at most this
	 *  factor, so the Warlord may lean a wave a little harder but never runaway-harder. */
	private static final double BUDGET_SLACK = 1.15;
	/** Sanity cap on how many of a single enemy type one plan may field. */
	private static final int PER_TYPE_CAP = 300;
	/** Sanity cap on the TOTAL non-boss entities one plan may field (protects the drip/TPS
	 *  system from an all-goblin budget dump). */
	private static final int MAX_PLAN_ENTITIES = 400;
	/** Sanity cap on how many bosses a plan may add on top of the default boss squad. */
	public static final int MAX_PLAN_BOSSES = 8;

	// ---- pending plans -----------------------------------------------------
	/** targetWave → the validated+clamped plan submitted for it. Consumed on wave start. */
	private final Map<Integer, WavePlan> pendingPlans = new ConcurrentHashMap<>();

	// ---- telemetry ---------------------------------------------------------
	/** Live counters for the wave currently running (server-thread-only). */
	private final Live live = new Live();
	/** Immutable snapshot of the most recently finished wave; {@code null} until one ends.
	 *  Published through {@code volatile} so the bridge can read it off-thread. */
	private volatile WaveTelemetry lastWave = null;

	private WarlordDirector() {
	}

	/**
	 * Register the director's own {@link ServerLivingEntityEvents#AFTER_DEATH} listener,
	 * which classifies each wave-enemy death as a tower kill or a player kill for the
	 * telemetry. Deliberately separate from the wave manager's boss-bounty hook and the
	 * progression XP hook (each owns its own listener). Call once from mod init.
	 */
	public static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity.getWorld() instanceof ServerWorld)) {
				return;
			}
			if (!entity.getCommandTags().contains(WaveManager.ENEMY_TAG)) {
				return;
			}
			WarlordDirector d = get();
			// A tower's damage stamps {@code lastTowerHitAge} on the enemy the instant before it
			// applies the (possibly lethal) hit; since AFTER_DEATH fires synchronously inside that
			// same damage call, a stamp equal to the enemy's current age means a tower struck the
			// killing blow. Otherwise a player attacker (melee / bow / spell — all resolve to a
			// player) is a player kill; anything else (ally soldier, drowning, cull) is neither.
			if (entity instanceof TdEnemyEntity te && te.lastTowerHitAge == te.age) {
				d.recordTowerKill();
			} else if (source.getAttacker() instanceof PlayerEntity) {
				d.recordPlayerKill();
			} else {
				d.recordOtherKill();
			}
		});
	}

	// ---- threat model ------------------------------------------------------
	/** The threat cost of one unit of the given enemy id (0 for an unknown id). */
	public static double threatCost(String id) {
		return THREAT.getOrDefault(id, 0.0);
	}

	/** The {@link EntityType} an enemy id spawns, or {@code null} for an unknown / special id. */
	@Nullable
	public static EntityType<? extends TdEnemyEntity> typeFor(String id) {
		return id == null ? null : ID_TO_TYPE.get(id.toLowerCase());
	}

	/**
	 * The total threat "budget" for a wave — the threat of the {@link WaveManager}'s OWN
	 * default composition for that wave. This is what the Warlord redistributes within.
	 *
	 * <p>Computed deterministically (not by rolling the random roster): the horde is
	 * {@code enemyCount(wave)} units each worth the roster pool's average threat (the pool
	 * lists duplicates as spawn weights, so a plain average IS the weighted average), plus
	 * — on a boss wave — {@code bossCount(wave)} bosses at the boss threat.
	 */
	public static double budgetFor(int wave) {
		int w = Math.max(1, wave);
		double avg = averageThreat(WaveManager.rosterFor(w));
		double horde = WaveManager.enemyCount(w) * avg;
		double bosses = WaveManager.isBossWave(w) ? WaveManager.bossCount(w) * THREAT.get(BOSS_ID) : 0.0;
		return horde + bosses;
	}

	/** Average threat of a roster pool (duplicates weight the average). */
	private static double averageThreat(List<EntityType<? extends TdEnemyEntity>> pool) {
		if (pool.isEmpty()) {
			return 1.0;
		}
		double sum = 0.0;
		for (EntityType<?> t : pool) {
			String id = TYPE_TO_ID.get(t);
			sum += id != null ? THREAT.getOrDefault(id, 1.0) : 1.0;
		}
		return sum / pool.size();
	}

	/** Total threat of a composition map (id → count). Unknown ids contribute nothing. */
	private static double totalThreat(Map<String, Integer> composition) {
		double total = 0.0;
		for (Map.Entry<String, Integer> e : composition.entrySet()) {
			total += threatCost(e.getKey()) * Math.max(0, e.getValue());
		}
		return total;
	}

	// ---- plan submission / validation --------------------------------------
	/**
	 * Validate + clamp a raw plan for a wave, WITHOUT storing it (pure — safe off-thread).
	 * The returned plan carries only known enemy ids, positive counts capped per-type, a
	 * total threat clamped to {@code budgetFor(wave) * }{@value #BUDGET_SLACK}, and a total
	 * entity count capped at {@value #MAX_PLAN_ENTITIES} (bosses capped separately at
	 * {@value #MAX_PLAN_BOSSES}). The spawn emphasis / tactic / taunt pass through as-is.
	 */
	public WavePlan validateAndClamp(int wave, Map<String, Integer> rawComposition,
			@Nullable SpawnEmphasis emphasis, String tactic, String taunt) {
		Map<String, Integer> clamped = new LinkedHashMap<>();
		int bosses = 0;
		if (rawComposition != null) {
			for (Map.Entry<String, Integer> e : rawComposition.entrySet()) {
				String id = e.getKey() == null ? "" : e.getKey().toLowerCase().trim();
				int count = e.getValue() == null ? 0 : e.getValue();
				if (count <= 0) {
					continue;
				}
				if (BOSS_ID.equals(id)) {
					bosses = Math.min(MAX_PLAN_BOSSES, count);
					continue;
				}
				if (!ID_TO_TYPE.containsKey(id)) {
					continue; // drop unknown enemy ids
				}
				clamped.merge(id, Math.min(PER_TYPE_CAP, count), Integer::sum);
			}
		}
		// Clamp per-type again after any merge, then clamp the whole to the threat budget.
		clamped.replaceAll((k, v) -> Math.min(PER_TYPE_CAP, v));
		double limit = budgetFor(wave) * BUDGET_SLACK + bosses * THREAT.get(BOSS_ID);
		scaleToThreat(clamped, limit - bosses * THREAT.get(BOSS_ID));
		capTotalCount(clamped, MAX_PLAN_ENTITIES);
		if (bosses > 0) {
			clamped.put(BOSS_ID, bosses);
		}
		return new WavePlan(wave, clamped, emphasis, tactic == null ? "" : tactic,
			taunt == null ? "" : taunt);
	}

	/** Proportionally scale a composition's counts down so its threat ≤ {@code limit}. */
	private static void scaleToThreat(Map<String, Integer> composition, double limit) {
		double total = totalThreat(composition);
		if (total <= limit || total <= 0) {
			return;
		}
		double f = limit / total;
		composition.entrySet().removeIf(e -> {
			int scaled = (int) Math.floor(e.getValue() * f);
			e.setValue(scaled);
			return scaled <= 0;
		});
	}

	/** Proportionally scale a composition's counts so their SUM ≤ {@code maxCount}. */
	private static void capTotalCount(Map<String, Integer> composition, int maxCount) {
		int total = 0;
		for (int v : composition.values()) {
			total += v;
		}
		if (total <= maxCount || total <= 0) {
			return;
		}
		double f = (double) maxCount / total;
		composition.entrySet().removeIf(e -> {
			int scaled = (int) Math.floor(e.getValue() * f);
			e.setValue(scaled);
			return scaled <= 0;
		});
	}

	/**
	 * Validate + clamp a raw plan and STORE it as the pending plan for its wave (replacing
	 * any prior plan for that wave). Returns the clamped plan so the caller sees exactly
	 * what was accepted. Called off the server thread by the bridge.
	 */
	public WavePlan submitPlan(int wave, Map<String, Integer> rawComposition,
			@Nullable SpawnEmphasis emphasis, String tactic, String taunt) {
		WavePlan plan = validateAndClamp(wave, rawComposition, emphasis, tactic, taunt);
		pendingPlans.put(wave, plan);
		return plan;
	}

	/**
	 * Consume the pending plan for a wave as it starts: returns it (or {@code null} if none)
	 * and removes it, plus any now-stale plans for earlier waves. Called on the server
	 * thread from {@link WaveManager}.
	 */
	@Nullable
	public WavePlan takePlan(int wave) {
		pendingPlans.keySet().removeIf(w -> w < wave);
		return pendingPlans.remove(wave);
	}

	/** Peek at a pending plan (does not consume). */
	@Nullable
	public WavePlan peekPlan(int wave) {
		return pendingPlans.get(wave);
	}

	/** Boss units a plan adds (the {@code "boss"} entry of its composition), clamped. */
	public static int plannedBosses(@Nullable WavePlan plan) {
		if (plan == null) {
			return 0;
		}
		return Math.min(MAX_PLAN_BOSSES, plan.composition().getOrDefault(BOSS_ID, 0));
	}

	// ---- telemetry recording (server thread) -------------------------------
	/** Begin accumulating telemetry for a freshly started wave. */
	public void onWaveStart(int wave, long worldTime) {
		live.reset(wave, worldTime);
	}

	/** One enemy actually spawned this wave. */
	public void recordSpawn() {
		live.spawned++;
	}

	/** One enemy reached the idol (leaked) without dying. */
	public void recordLeak() {
		live.leaked++;
	}

	/** One enemy was killed by a tower's damage. */
	public void recordTowerKill() {
		live.killedByTowers++;
	}

	/** One enemy was killed by a player (melee / bow / spell). */
	public void recordPlayerKill() {
		live.killedByPlayers++;
	}

	/** One enemy died to something that is neither a tower nor a player (ally, drowning, cull). */
	public void recordOtherKill() {
		live.killedByOther++;
	}

	/**
	 * Finalise the current wave's telemetry into an immutable {@link WaveTelemetry} snapshot,
	 * publish it as {@link #lastWave}, and return it. Called on wave clear.
	 */
	public WaveTelemetry finalizeWave(int wave, long worldTime) {
		WaveTelemetry snapshot = new WaveTelemetry(
			wave, live.spawned, live.leaked, live.killedByTowers, live.killedByPlayers,
			(int) Math.max(0L, worldTime - live.startTime));
		lastWave = snapshot;
		return snapshot;
	}

	/** The most recently finished wave's telemetry, or {@code null} if none has ended yet. */
	@Nullable
	public WaveTelemetry lastWave() {
		return lastWave;
	}

	// ---- battlefield snapshot helpers (thread-safe) ------------------------
	/**
	 * The enemy-type catalogue the Warlord may compose from: each {@code {id, threatCost}},
	 * in a stable order (roster light→heavy, then the boss). Consumed by the battlefield
	 * endpoint so the agent always sees the live contract.
	 */
	public static List<Map<String, Object>> enemyTypeInfos() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map.Entry<String, EntityType<? extends TdEnemyEntity>> e : ID_TO_TYPE.entrySet()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", e.getKey());
			m.put("threatCost", THREAT.get(e.getKey()));
			out.add(m);
		}
		Map<String, Object> boss = new LinkedHashMap<>();
		boss.put("id", BOSS_ID);
		boss.put("threatCost", THREAT.get(BOSS_ID));
		out.add(boss);
		return out;
	}

	// ---- spawn-emphasis weighting ------------------------------------------
	/**
	 * Pick a spawn gate from the arena, weighted by a plan's {@link SpawnEmphasis}: a
	 * <em>focus point</em> weights each gate by inverse distance-squared to that point (so
	 * the horde emphasises the flank nearest the focus); <em>direction weights</em> weight
	 * each gate by its compass direction from the idol. If the emphasis yields no usable
	 * weight (or is null), falls back to a uniform random gate — identical to the default
	 * spawn path. Consumes exactly one {@code random} draw, like the default {@code nextInt}.
	 */
	public static BlockPos weightedGate(ServerWorld world, TdArenaState st, @Nullable SpawnEmphasis emphasis) {
		List<BlockPos> gates = st.spawnPoints;
		if (emphasis == null || gates.isEmpty() || st.base == null) {
			return gates.get(world.random.nextInt(gates.size()));
		}
		double[] weights = new double[gates.size()];
		double total = 0.0;
		for (int i = 0; i < gates.size(); i++) {
			double w = emphasis.weightFor(gates.get(i), st.base);
			weights[i] = w;
			total += w;
		}
		if (total <= 0.0) {
			return gates.get(world.random.nextInt(gates.size()));
		}
		double roll = world.random.nextDouble() * total;
		for (int i = 0; i < gates.size(); i++) {
			roll -= weights[i];
			if (roll <= 0.0) {
				return gates.get(i);
			}
		}
		return gates.get(gates.size() - 1);
	}

	// ==== value types =======================================================

	/**
	 * A submitted wave plan (already validated + clamped): the {@code targetWave} it applies
	 * to, the {@code composition} (enemy id → count, possibly incl. {@code "boss"}), an
	 * optional {@code emphasis}, and free-text {@code tactic} + {@code taunt} flavour.
	 */
	public record WavePlan(int targetWave, Map<String, Integer> composition,
			@Nullable SpawnEmphasis emphasis, String tactic, String taunt) {

		/** JSON-serialisable view for the bridge to echo back the accepted plan. */
		public Map<String, Object> toJson() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("wave", targetWave);
			m.put("composition", composition);
			m.put("spawnEmphasis", emphasis == null ? null : emphasis.toJson());
			m.put("tactic", tactic);
			m.put("taunt", taunt);
			m.put("totalThreat", totalThreat(composition));
			return m;
		}
	}

	/**
	 * Where a wave's spawns are emphasised: either a FOCUS point {@code (focusX, focusZ)}
	 * that biases spawning toward the nearest gate, or per-direction {@code weights} (keys
	 * like {@code n/s/e/w/ne/nw/se/sw}) that bias which compass flank the horde pours from.
	 * Both are optional; a null/empty emphasis weights every gate uniformly.
	 */
	public record SpawnEmphasis(@Nullable Integer focusX, @Nullable Integer focusZ,
			@Nullable Map<String, Double> weights) {

		public boolean hasFocus() {
			return focusX != null && focusZ != null;
		}

		public boolean hasWeights() {
			return weights != null && !weights.isEmpty();
		}

		/** The weight this emphasis assigns to a given gate relative to the base/idol. */
		double weightFor(BlockPos gate, BlockPos base) {
			if (hasFocus()) {
				double dx = gate.getX() - focusX;
				double dz = gate.getZ() - focusZ;
				return 1.0 / (1.0 + dx * dx + dz * dz);
			}
			if (hasWeights()) {
				Double w = weights.get(compassDir(gate, base));
				return w == null ? 0.0 : Math.max(0.0, w);
			}
			return 1.0;
		}

		/** JSON view mirroring the accepted emphasis. */
		public Map<String, Object> toJson() {
			Map<String, Object> m = new LinkedHashMap<>();
			if (hasFocus()) {
				m.put("x", focusX);
				m.put("z", focusZ);
			}
			if (hasWeights()) {
				m.put("weights", weights);
			}
			return m;
		}

		/** 8-way compass direction of {@code gate} relative to {@code base} (MC: -z=north, +x=east). */
		private static String compassDir(BlockPos gate, BlockPos base) {
			double dx = gate.getX() - base.getX();
			double dz = gate.getZ() - base.getZ();
			// atan2 with north(-z) as 0°, going clockwise through east(+x).
			double deg = Math.toDegrees(Math.atan2(dx, -dz));
			if (deg < 0) {
				deg += 360.0;
			}
			String[] dirs = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
			int idx = (int) Math.round(deg / 45.0) % 8;
			return dirs[idx];
		}
	}

	/**
	 * An immutable, finished-wave telemetry snapshot: how many enemies {@code spawned},
	 * how many {@code leaked} to the idol, how many were {@code killedByTowers} vs
	 * {@code killedByPlayers}, and how long the wave took ({@code durationTicks}).
	 */
	public record WaveTelemetry(int number, int spawned, int leaked,
			int killedByTowers, int killedByPlayers, int durationTicks) {

		/** JSON-serialisable view for the battlefield endpoint. */
		public Map<String, Object> toJson() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("number", number);
			m.put("spawned", spawned);
			m.put("leaked", leaked);
			m.put("killedByTowers", killedByTowers);
			m.put("killedByPlayers", killedByPlayers);
			m.put("durationTicks", durationTicks);
			return m;
		}
	}

	/** Mutable live-wave accumulator (server-thread-only). */
	private static final class Live {
		int number;
		int spawned;
		int leaked;
		int killedByTowers;
		int killedByPlayers;
		int killedByOther;
		long startTime;

		void reset(int wave, long worldTime) {
			number = wave;
			spawned = 0;
			leaked = 0;
			killedByTowers = 0;
			killedByPlayers = 0;
			killedByOther = 0;
			startTime = worldTime;
		}
	}
}
