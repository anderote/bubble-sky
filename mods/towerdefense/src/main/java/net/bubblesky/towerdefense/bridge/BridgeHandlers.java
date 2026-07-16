package net.bubblesky.towerdefense.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.bubblesky.towerdefense.bridge.AgentBridge.BridgeException;
import net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity;
import net.bubblesky.towerdefense.colony.ColonistEntity;
import net.bubblesky.towerdefense.colony.ColonyState;
import net.bubblesky.towerdefense.colony.ColonyWorkGoal;
import net.bubblesky.towerdefense.game.WarlordDirector;
import net.bubblesky.towerdefense.registry.ModEntities;
import net.bubblesky.towerdefense.layout.LayoutStore;
import net.bubblesky.towerdefense.progression.PlayerClass;
import net.bubblesky.towerdefense.progression.PlayerProgress;
import net.bubblesky.towerdefense.progression.ProgressState;
import net.bubblesky.towerdefense.state.TdArenaState;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * The bridge's HTTP endpoints. All world access happens inside
 * {@link AgentBridge#runOnServer} lambdas so it runs on the server thread.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /health}</li>
 *   <li>{@code GET  /block?x&y&z}</li>
 *   <li>{@code GET  /region?x&y&z&rx&ry&rz}</li>
 *   <li>{@code GET  /scan?x&y&z&r}</li>
 *   <li>{@code POST /setblock {x,y,z,block}}</li>
 *   <li>{@code POST /fill {x1,y1,z1,x2,y2,z2,block}}</li>
 *   <li>{@code POST /command {command}}</li>
 *   <li>{@code POST /batch {ops:[...]}}</li>
 * </ul>
 */
final class BridgeHandlers {
	/** Reported mod version (keep in sync with gradle.properties mod_version). */
	private static final String MOD_VERSION = "1.0.0";

	/** Max cells returned by /region (guards response size + server-thread time). */
	private static final long MAX_REGION_VOLUME = 32_768L; // 32^3
	/** Max radius per axis for /region. */
	private static final int MAX_REGION_RADIUS = 48;
	/** Max cells mutated by a single /fill (guards server-thread time). */
	private static final long MAX_FILL_VOLUME = 262_144L; // 64^3
	/** Max scan radius for /scan. */
	private static final int MAX_SCAN_RADIUS = 64;
	/** Default force-tick count for the DEBUG /td/debug/tickcolony endpoint. */
	private static final int DEBUG_TICK_DEFAULT = 100;
	/** Hard cap on force-ticks per DEBUG /td/debug/tickcolony call (guards server-thread time). */
	private static final int DEBUG_TICK_CAP = 2000;

	private final AgentBridge bridge;

	BridgeHandlers(AgentBridge bridge) {
		this.bridge = bridge;
	}

	void register(HttpServer http) {
		http.createContext("/health", bridge.secure(this::health));
		http.createContext("/block", bridge.secure(this::block));
		http.createContext("/region", bridge.secure(this::region));
		http.createContext("/scan", bridge.secure(this::scan));
		http.createContext("/setblock", bridge.secure(this::setblock));
		http.createContext("/fill", bridge.secure(this::fill));
		http.createContext("/command", bridge.secure(this::command));
		http.createContext("/batch", bridge.secure(this::batch));
		// chat + player + status (one-server unification: agents run over the bridge)
		http.createContext("/chat", bridge.secure(this::chat));
		http.createContext("/say", bridge.secure(this::say));
		http.createContext("/player", bridge.secure(this::player));
		http.createContext("/players", bridge.secure(this::players));
		http.createContext("/status/agent", bridge.secure(this::statusAgent));
		http.createContext("/status", bridge.secure(this::status));
		http.createContext("/test/chat", bridge.secure(this::testChat));
		// layout planning: shared flag/region store (wand + agents + chat = one store)
		http.createContext("/flags", bridge.secure(this::flags));
		http.createContext("/regions", bridge.secure(this::regions));
		// Enemy AI Warlord (#17a): battlefield snapshot + wave-plan submission + taunt.
		http.createContext("/td/battlefield", bridge.secure(this::tdBattlefield));
		http.createContext("/td/waveplan", bridge.secure(this::tdWavePlan));
		http.createContext("/td/taunt", bridge.secure(this::tdTaunt));
		// LLM colonists (#18a): colony snapshot + colonist job/build assignment + priorities.
		http.createContext("/td/colony/assign", bridge.secure(this::tdColonyAssign));
		http.createContext("/td/colony/priorities", bridge.secure(this::tdColonyPriorities));
		http.createContext("/td/colony", bridge.secure(this::tdColony));
		// DEBUG (test-only): force-advance colonist work goals off the natural entity-tick loop.
		http.createContext("/td/debug/tickcolony", bridge.secure(this::tdDebugTickColony));
	}

	// ---------- Enemy AI Warlord (#17a) endpoints ----------

	/**
	 * {@code GET /td/battlefield} — a full snapshot the external Warlord agent reads each
	 * intermission to plan the next wave. Shape:
	 * <pre>{ok, wave, waveInProgress, intermission{active,ticksLeft},
	 *      idol{x,y,z,hp,max}|null, towers[{type,x,y,z,tier}], budget,
	 *      players[{name,class,x,y,z,level}], lastWave{...}|null,
	 *      enemyTypes[{id,threatCost}]}</pre>
	 * All world/state reads run on the server thread; the budget + enemyTypes + lastWave
	 * come from the (thread-safe) {@link WarlordDirector}.
	 */
	private Object tdBattlefield(HttpExchange exchange, JsonBody body) {
		return bridge.runOnServer(() -> {
			MinecraftServer server = bridge.server();
			TdArenaState st = TdArenaState.get(server);
			WarlordDirector director = WarlordDirector.get();
			int nextWave = st.currentWave + 1;

			Map<String, Object> out = new LinkedHashMap<>();
			out.put("ok", true);
			out.put("wave", st.currentWave);
			out.put("waveInProgress",
				st.phase == TdArenaState.Phase.SPAWNING || st.phase == TdArenaState.Phase.ACTIVE);
			Map<String, Object> inter = new LinkedHashMap<>();
			inter.put("active", st.phase == TdArenaState.Phase.INTERMISSION);
			inter.put("ticksLeft", st.intermissionCooldown);
			out.put("intermission", inter);
			out.put("gameOver", st.gameOver);

			// Idol (base) — position + health, or null if unset.
			if (st.base != null) {
				Map<String, Object> idol = new LinkedHashMap<>();
				idol.put("x", st.base.getX());
				idol.put("y", st.base.getY());
				idol.put("z", st.base.getZ());
				idol.put("hp", st.baseHp);
				idol.put("max", st.baseMaxHp);
				out.put("idol", idol);
			} else {
				out.put("idol", null);
			}

			// Towers — type + position + tier, read live from each core's block entity.
			ServerWorld world = st.getArenaWorld(server);
			List<Map<String, Object>> towers = new ArrayList<>();
			if (world != null) {
				for (BlockPos pos : st.towers) {
					if (world.getBlockEntity(pos) instanceof AbstractTowerBlockEntity tower) {
						Map<String, Object> t = new LinkedHashMap<>();
						t.put("type", tower.kind().id());
						t.put("x", pos.getX());
						t.put("y", pos.getY());
						t.put("z", pos.getZ());
						t.put("tier", tower.getTier());
						towers.add(t);
					}
				}
			}
			out.put("towers", towers);

			// Players — name + active class + position + character level.
			ProgressState progressState = ProgressState.get(server);
			List<Map<String, Object>> players = new ArrayList<>();
			for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
				PlayerProgress progress = progressState.forPlayer(p.getUuid());
				PlayerClass active = progress.getActiveClass();
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", p.getName().getString());
				m.put("class", active == null ? null : active.id());
				m.put("x", round(p.getX()));
				m.put("y", round(p.getY()));
				m.put("z", round(p.getZ()));
				m.put("level", progress.getLevel());
				players.add(m);
			}
			out.put("players", players);

			// Threat budget for the wave the Warlord would plan next + the last wave's outcome.
			out.put("budget", WarlordDirector.budgetFor(nextWave));
			out.put("nextWave", nextWave);
			WarlordDirector.WaveTelemetry last = director.lastWave();
			out.put("lastWave", last == null ? null : last.toJson());
			// Adaptive escalation factor (the rubber-band): >= 1.0, climbs when the defence
			// dominates and eases back toward baseline when enemies threaten the Idol. The
			// lastWave object carries the pressure metrics (closestApproach + idolDamage) that
			// drove it, so the external Warlord agent can taunt/plan around the current state.
			out.put("escalation", director.escalation());
			out.put("enemyTypes", WarlordDirector.enemyTypeInfos());
			return out;
		});
	}

	/**
	 * {@code POST /td/waveplan {wave, composition{id:count}, spawnEmphasis, tactic, taunt}} —
	 * validate + clamp the plan against the wave's threat budget, store it as the pending plan
	 * for that wave, and echo back the CLAMPED plan (so the caller sees exactly what was
	 * accepted). If a {@code taunt} is present it is broadcast as a Warlord chat line.
	 */
	private Object tdWavePlan(HttpExchange exchange, JsonBody body) {
		int wave = body.requireInt("wave");
		Map<String, Integer> composition = parseComposition(body);
		WarlordDirector.SpawnEmphasis emphasis = parseEmphasis(body);
		String tactic = body.getString("tactic", "");
		String taunt = body.getString("taunt", "");
		WarlordDirector.WavePlan plan = WarlordDirector.get()
			.submitPlan(wave, composition, emphasis, tactic, taunt);
		if (!taunt.isBlank()) {
			broadcastTaunt(taunt);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("accepted", plan.toJson());
		out.put("budget", WarlordDirector.budgetFor(wave));
		return out;
	}

	/** {@code POST /td/taunt {text}} — broadcast a Warlord-persona chat line. */
	private Object tdTaunt(HttpExchange exchange, JsonBody body) {
		String text = body.requireString("text");
		broadcastTaunt(text);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("text", text);
		return out;
	}

	/** Broadcast a styled Warlord taunt to every player (server thread). */
	private void broadcastTaunt(String text) {
		bridge.runOnServer(() -> {
			Text line = Text.literal("☠ Warlord: ")
				.formatted(net.minecraft.util.Formatting.DARK_RED, net.minecraft.util.Formatting.BOLD)
				.append(Text.literal(text)
					.formatted(net.minecraft.util.Formatting.DARK_RED, net.minecraft.util.Formatting.ITALIC));
			bridge.server().getPlayerManager().broadcast(line, false);
			return null;
		});
		BridgeState.recordChat("Warlord", text);
	}

	/** Parse the {@code composition} object ({@code {id:count}}) from a wave-plan body. */
	private Map<String, Integer> parseComposition(JsonBody body) {
		Map<String, Integer> out = new LinkedHashMap<>();
		if (!body.has("composition") || !body.raw().get("composition").isJsonObject()) {
			return out;
		}
		JsonObject comp = body.raw().getAsJsonObject("composition");
		for (Map.Entry<String, JsonElement> e : comp.entrySet()) {
			try {
				out.put(e.getKey(), e.getValue().getAsInt());
			} catch (Exception ignored) {
				// skip any non-integer count
			}
		}
		return out;
	}

	/**
	 * Parse a {@code spawnEmphasis} object into a {@link WarlordDirector.SpawnEmphasis}: an
	 * object carrying {@code x}+{@code z} is a focus point; otherwise its keys are treated as
	 * per-direction weights ({@code n/s/e/w/…}). Missing/invalid → {@code null} (uniform).
	 */
	private WarlordDirector.SpawnEmphasis parseEmphasis(JsonBody body) {
		if (!body.has("spawnEmphasis") || !body.raw().get("spawnEmphasis").isJsonObject()) {
			return null;
		}
		JsonObject se = body.raw().getAsJsonObject("spawnEmphasis");
		// Nested {focus:{x,z}} or {weights:{...}} forms are also accepted.
		JsonObject focus = se.has("focus") && se.get("focus").isJsonObject()
			? se.getAsJsonObject("focus") : se;
		if (focus.has("x") && focus.has("z")) {
			try {
				return new WarlordDirector.SpawnEmphasis(
					focus.get("x").getAsInt(), focus.get("z").getAsInt(), null);
			} catch (Exception ignored) {
				return null;
			}
		}
		JsonObject weightsObj = se.has("weights") && se.get("weights").isJsonObject()
			? se.getAsJsonObject("weights") : se;
		Map<String, Double> weights = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : weightsObj.entrySet()) {
			try {
				weights.put(e.getKey().toLowerCase(), e.getValue().getAsDouble());
			} catch (Exception ignored) {
				// skip non-numeric weight
			}
		}
		return weights.isEmpty() ? null : new WarlordDirector.SpawnEmphasis(null, null, weights);
	}

	// ---------- LLM colonists (#18a) endpoints ----------

	/**
	 * {@code GET /td/colony} — the snapshot the external foreman agent reads to steer the colony.
	 * Shape:
	 * <pre>{ok, flags[{name,x,y,z,dim}],
	 *      colonists[{name, uuid, x, y, z, job, priorities[], owner, invCount,
	 *                 target:{x,y,z,length,dir,height,block,repairOnly}|null}]}</pre>
	 * {@code job}/{@code priorities} use the STABLE lowercase job strings
	 * ({@code mine/chop/hunt/forage/haul/idle/build}) that map 1:1 to {@link ColonistEntity.Job}.
	 * Every live colonist in the arena world (found by {@link ModEntities#COLONIST} type) is
	 * listed; {@code owner} is the recruiter's name when online, else their UUID string.
	 */
	private Object tdColony(HttpExchange exchange, JsonBody body) {
		return bridge.runOnServer(() -> {
			MinecraftServer server = bridge.server();
			ServerWorld world = colonyWorld(server);

			Map<String, Object> out = new LinkedHashMap<>();
			out.put("ok", true);

			// Colony home flags (the anchors colonists bind to).
			List<Map<String, Object>> flags = new ArrayList<>();
			for (ColonyState.Flag f : ColonyState.get(server).flags) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", f.name());
				m.put("x", f.pos().getX());
				m.put("y", f.pos().getY());
				m.put("z", f.pos().getZ());
				m.put("dim", f.dim());
				flags.add(m);
			}
			out.put("flags", flags);

			// Every live colonist in the arena world.
			List<Map<String, Object>> colonists = new ArrayList<>();
			for (ColonistEntity c : world.getEntitiesByType(ModEntities.COLONIST, ColonistEntity::isAlive)) {
				colonists.add(colonistJson(server, c));
			}
			out.put("colonists", colonists);
			return out;
		});
	}

	/**
	 * {@code POST /td/colony/assign {colonist, job, target?{x,y,z}, length?, dir?, height?, block?,
	 * repairOnly?}} — resolve the colonist by UUID or (case-insensitive) name, validate the job,
	 * and apply it on the server thread. {@code job=build} REQUIRES a {@code target} and installs a
	 * clamped build task (length ≤ 16, height ≤ 6, block defaulted to cobblestone) that overrides
	 * the rule-based brain until the segment is finished; any other job clears an existing build
	 * task and sets the job directly. Echoes {@code {ok, colonist, job, target?}}.
	 */
	private Object tdColonyAssign(HttpExchange exchange, JsonBody body) {
		String who = body.requireString("colonist");
		String jobStr = body.requireString("job").trim();
		ColonistEntity.Job job;
		try {
			job = ColonistEntity.Job.valueOf(jobStr.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new BridgeException(400, "unknown job '" + jobStr
				+ "' (use mine, chop, hunt, forage, haul, idle or build)");
		}

		// For BUILD, parse + validate the target struct up front (proper 400s off the server thread).
		final ColonistEntity.BuildTarget buildTarget;
		if (job == ColonistEntity.Job.BUILD) {
			if (!body.has("target") || !body.raw().get("target").isJsonObject()) {
				throw new BridgeException(400, "job 'build' requires a target {x,y,z}");
			}
			JsonObject t = body.raw().getAsJsonObject("target");
			if (!t.has("x") || !t.has("y") || !t.has("z")) {
				throw new BridgeException(400, "build target must be {x,y,z}");
			}
			BlockPos origin = new BlockPos(t.get("x").getAsInt(), t.get("y").getAsInt(), t.get("z").getAsInt());
			int length = body.getInt("length", 4);
			int height = body.getInt("height", 3);
			Direction dir = ColonistEntity.parseDirection(body.getString("dir", "east"));
			// Validate the block string (400 on a bad id) and store its canonical id.
			BlockState blockState = parseBlock(body.getString("block", ColonistEntity.DEFAULT_BUILD_BLOCK));
			String blockId = Registries.BLOCK.getId(blockState.getBlock()).toString();
			boolean repairOnly = body.has("repairOnly") && body.raw().get("repairOnly").getAsBoolean();
			buildTarget = new ColonistEntity.BuildTarget(origin, length, dir, height, blockId, repairOnly);
		} else {
			buildTarget = null;
		}

		Map<String, Object> out = bridge.runOnServer(() -> {
			ColonistEntity c = findColonist(bridge.server(), who);
			if (c == null) {
				Map<String, Object> miss = new LinkedHashMap<>();
				miss.put("ok", false);
				miss.put("error", "no colonist matched '" + who + "' (by uuid or name)");
				return miss;
			}
			if (job == ColonistEntity.Job.BUILD) {
				c.setBuildTarget(buildTarget); // clamps + switches the colonist to BUILD
			} else {
				c.clearBuildTarget(); // drop any pending wall task…
				c.setJob(job);          // …and take the plain job
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("ok", true);
			m.put("colonist", c.getColonistName());
			m.put("uuid", c.getUuidAsString());
			m.put("job", c.getJob().name().toLowerCase(Locale.ROOT));
			m.put("target", buildTargetJson(c.getBuildTarget()));
			return m;
		});
		if (Boolean.FALSE.equals(out.get("ok"))) {
			throw new BridgeException(404, (String) out.get("error"));
		}
		return out;
	}

	/**
	 * {@code POST /td/colony/priorities {colonist, priorities:[job,...]}} — validate every entry
	 * against {@link ColonistEntity.Job} and set the colonist's priority ordering (gather types
	 * missing from the list are appended so the rule-based brain still has a full rotation). Echoes
	 * {@code {ok, colonist, priorities[]}}.
	 */
	private Object tdColonyPriorities(HttpExchange exchange, JsonBody body) {
		String who = body.requireString("colonist");
		JsonArray arr = body.getArray("priorities");
		List<ColonistEntity.Job> order = new ArrayList<>();
		for (JsonElement el : arr) {
			String s = el.getAsString().trim();
			try {
				ColonistEntity.Job j = ColonistEntity.Job.valueOf(s.toUpperCase(Locale.ROOT));
				if (!order.contains(j)) {
					order.add(j);
				}
			} catch (IllegalArgumentException e) {
				throw new BridgeException(400, "unknown job in priorities: '" + s + "'");
			}
		}
		// Guarantee every gather type is present so decide() always has a full rotation.
		for (ColonistEntity.Job j : List.of(ColonistEntity.Job.HAUL, ColonistEntity.Job.MINE,
				ColonistEntity.Job.CHOP, ColonistEntity.Job.FORAGE, ColonistEntity.Job.HUNT)) {
			if (!order.contains(j)) {
				order.add(j);
			}
		}

		Map<String, Object> out = bridge.runOnServer(() -> {
			ColonistEntity c = findColonist(bridge.server(), who);
			if (c == null) {
				Map<String, Object> miss = new LinkedHashMap<>();
				miss.put("ok", false);
				miss.put("error", "no colonist matched '" + who + "' (by uuid or name)");
				return miss;
			}
			c.getPriorities().clear();
			c.getPriorities().addAll(order);
			List<String> echo = new ArrayList<>();
			for (ColonistEntity.Job j : c.getPriorities()) {
				echo.add(j.name().toLowerCase(Locale.ROOT));
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("ok", true);
			m.put("colonist", c.getColonistName());
			m.put("priorities", echo);
			return m;
		});
		if (Boolean.FALSE.equals(out.get("ok"))) {
			throw new BridgeException(404, (String) out.get("error"));
		}
		return out;
	}

	/**
	 * {@code POST /td/debug/tickcolony {ticks?, colonist?}} — DEBUG / TEST-ONLY endpoint.
	 *
	 * <p>WHY: this headless server does NOT tick entities while no player is online, so a colonist's
	 * work behaviour (notably the BUILD/wall job) never advances and cannot be verified from the
	 * console. The server MAIN THREAD runs regardless, so this endpoint force-advances colonist work
	 * goals ON THE SERVER THREAD — invoking each colonist's {@link ColonyWorkGoal#tick()} (via
	 * {@link ColonistEntity#debugRunWork}) {@code ticks} times in a loop. That lets {@code stepBuild}
	 * actually place wall blocks (and any other work step run) without natural entity ticking.
	 *
	 * <p>This does NOT alter normal gameplay: it is a manual force-tick reachable only over the
	 * secured bridge. When a player is online the goalSelector drives the same goal every real tick.
	 *
	 * <p>{@code ticks} defaults to {@value #DEBUG_TICK_DEFAULT} and is clamped to
	 * [1, {@value #DEBUG_TICK_CAP}]. {@code colonist} (optional) restricts the force-tick to the one
	 * colonist matching by UUID or (case-insensitive) name; omitted → every live colonist in the
	 * arena world. Returns the POST-RUN state so the caller can see whether a build completed (job
	 * back to {@code idle}, {@code buildTarget} null):
	 * <pre>{ok, ticks, colonists:[{name, uuid, job, x, y, z, buildTarget:{x,y,z,length,dir,height,block,repairOnly}|null}]}</pre>
	 */
	private Object tdDebugTickColony(HttpExchange exchange, JsonBody body) {
		int requested = body.getInt("ticks", DEBUG_TICK_DEFAULT);
		final int ticks = Math.max(1, Math.min(DEBUG_TICK_CAP, requested));
		final String who = body.getString("colonist", "").trim();

		return bridge.runOnServer(() -> {
			MinecraftServer server = bridge.server();
			ServerWorld world = colonyWorld(server);

			// Select the target colonists: the single match if a name/uuid was given, else all live.
			List<ColonistEntity> targets = new ArrayList<>();
			if (!who.isEmpty()) {
				ColonistEntity one = findColonist(server, who);
				if (one != null) {
					targets.add(one);
				}
			} else {
				targets.addAll(world.getEntitiesByType(ModEntities.COLONIST, ColonistEntity::isAlive));
			}

			// Force-advance each selected colonist's work goal on this (server) thread.
			for (ColonistEntity c : targets) {
				for (int i = 0; i < ticks; i++) {
					c.debugRunWork(world);
				}
			}

			// Report POST-RUN state so the caller sees whether the build finished.
			List<Map<String, Object>> colonists = new ArrayList<>();
			for (ColonistEntity c : targets) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", c.getColonistName());
				m.put("uuid", c.getUuidAsString());
				m.put("job", c.getJob().name().toLowerCase(Locale.ROOT));
				m.put("x", round(c.getX()));
				m.put("y", round(c.getY()));
				m.put("z", round(c.getZ()));
				m.put("buildTarget", buildTargetJson(c.getBuildTarget()));
				colonists.add(m);
			}
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("ok", true);
			out.put("ticks", ticks);
			out.put("colonists", colonists);
			return out;
		});
	}

	/** The arena world colonists live in (from the arena state), falling back to the overworld. */
	private ServerWorld colonyWorld(MinecraftServer server) {
		ServerWorld world = TdArenaState.get(server).getArenaWorld(server);
		return world != null ? world : server.getOverworld();
	}

	/** Resolve a colonist across the arena world by UUID string first, else case-insensitive name. */
	private ColonistEntity findColonist(MinecraftServer server, String who) {
		UUID uuid = null;
		try {
			uuid = UUID.fromString(who);
		} catch (IllegalArgumentException ignored) {
			// not a UUID — fall through to name matching
		}
		ColonistEntity byName = null;
		for (ColonistEntity c : colonyWorld(server)
				.getEntitiesByType(ModEntities.COLONIST, ColonistEntity::isAlive)) {
			if (uuid != null && c.getUuid().equals(uuid)) {
				return c;
			}
			if (byName == null && c.getColonistName().equalsIgnoreCase(who)) {
				byName = c;
			}
		}
		return byName;
	}

	/** Serialize a colonist to the {@code /td/colony} JSON shape (server thread). */
	private Map<String, Object> colonistJson(MinecraftServer server, ColonistEntity c) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", c.getColonistName());
		m.put("uuid", c.getUuidAsString());
		m.put("x", round(c.getX()));
		m.put("y", round(c.getY()));
		m.put("z", round(c.getZ()));
		m.put("job", c.getJob().name().toLowerCase(Locale.ROOT));
		List<String> prio = new ArrayList<>();
		for (ColonistEntity.Job j : c.getPriorities()) {
			prio.add(j.name().toLowerCase(Locale.ROOT));
		}
		m.put("priorities", prio);
		m.put("owner", ownerLabel(server, c.getOwner()));
		int invCount = 0;
		for (int i = 0; i < c.getInventory().size(); i++) {
			invCount += c.getInventory().getStack(i).getCount();
		}
		m.put("invCount", invCount);
		m.put("target", buildTargetJson(c.getBuildTarget()));
		return m;
	}

	/** A build target as {@code {x,y,z,length,dir,height,block,repairOnly}}, or {@code null}. */
	private Map<String, Object> buildTargetJson(ColonistEntity.BuildTarget t) {
		if (t == null) {
			return null;
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("x", t.origin().getX());
		m.put("y", t.origin().getY());
		m.put("z", t.origin().getZ());
		m.put("length", t.length());
		m.put("dir", t.dir().asString());
		m.put("height", t.height());
		m.put("block", t.blockId());
		m.put("repairOnly", t.repairOnly());
		return m;
	}

	/** The owner's online name if resolvable, else the raw UUID string (or {@code null} if none). */
	private String ownerLabel(MinecraftServer server, UUID owner) {
		if (owner == null) {
			return null;
		}
		ServerPlayerEntity p = server.getPlayerManager().getPlayer(owner);
		return p != null ? p.getName().getString() : owner.toString();
	}

	// ---------- /flags : GET (list) | POST {name?,x,y,z,dim?} | DELETE ?name= ----------

	private Object flags(HttpExchange exchange, JsonBody body) {
		String method = exchange.getRequestMethod();
		if ("GET".equalsIgnoreCase(method)) {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("ok", true);
			out.put("flags", LayoutStore.flagMaps());
			return out;
		}
		if ("DELETE".equalsIgnoreCase(method)) {
			JsonBody q = query(exchange);
			String name = q.requireString("name");
			LayoutStore.Flag removed = LayoutStore.removeFlag(name);
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("ok", true);
			out.put("removed", removed != null);
			out.put("name", name);
			return out;
		}
		if ("POST".equalsIgnoreCase(method)) {
			String name = body.getString("name", "").trim();
			int x = body.requireInt("x");
			int y = body.requireInt("y");
			int z = body.requireInt("z");
			String dim = body.getString("dim", "overworld");
			if (name.isEmpty()) {
				name = LayoutStore.nextFlagName(body.getString("letter", "A"));
			}
			LayoutStore.Flag f = LayoutStore.putFlag(name, x, y, z, dim);
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("ok", true);
			out.put("name", f.name());
			out.put("x", f.x());
			out.put("y", f.y());
			out.put("z", f.z());
			return out;
		}
		throw new BridgeException(405, "method not allowed on /flags: " + method);
	}

	// ---------- /regions : GET (list) | POST {name?,a:{x,y,z},b:{x,y,z},dim?} | DELETE ?name= ----------

	private Object regions(HttpExchange exchange, JsonBody body) {
		String method = exchange.getRequestMethod();
		if ("GET".equalsIgnoreCase(method)) {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("ok", true);
			out.put("regions", LayoutStore.regionMaps());
			return out;
		}
		if ("DELETE".equalsIgnoreCase(method)) {
			JsonBody q = query(exchange);
			String name = q.requireString("name");
			LayoutStore.Region removed = LayoutStore.removeRegion(name);
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("ok", true);
			out.put("removed", removed != null);
			out.put("name", name);
			return out;
		}
		if ("POST".equalsIgnoreCase(method)) {
			String name = body.getString("name", "").trim();
			int[] a = requireCorner(body, "a");
			int[] b = requireCorner(body, "b");
			String dim = body.getString("dim", "overworld");
			LayoutStore.Region r = LayoutStore.putRegion(name.isEmpty() ? null : name,
				a[0], a[1], a[2], b[0], b[1], b[2], dim);
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("ok", true);
			out.put("name", r.name());
			out.put("a", new int[] {r.ax(), r.ay(), r.az()});
			out.put("b", new int[] {r.bx(), r.by(), r.bz()});
			return out;
		}
		throw new BridgeException(405, "method not allowed on /regions: " + method);
	}

	/** Pull a {x,y,z} corner object out of a POST /regions body. */
	private int[] requireCorner(JsonBody body, String key) {
		if (!body.has(key)) {
			throw new BridgeException(400, "missing required corner object: " + key);
		}
		JsonObject c = body.raw().getAsJsonObject(key);
		if (c == null || !c.has("x") || !c.has("y") || !c.has("z")) {
			throw new BridgeException(400, "corner '" + key + "' must be {x,y,z}");
		}
		return new int[] {c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt()};
	}

	// ---------- GET /chat?since=<seq> ----------

	private Object chat(HttpExchange exchange, JsonBody body) {
		JsonBody q = query(exchange);
		long since = q.getInt("since", 0);
		List<Map<String, Object>> messages = BridgeState.chatSince(since);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("cursor", BridgeState.chatCursor());
		out.put("messages", messages);
		return out;
	}

	// ---------- POST /say {name?, message} ----------

	private Object say(HttpExchange exchange, JsonBody body) {
		String name = body.getString("name", "").trim();
		String message = body.requireString("message");
		String line = name.isEmpty() ? message : "<" + name + "> " + message;
		bridge.runOnServer(() -> {
			bridge.server().getPlayerManager().broadcast(Text.literal(line), false);
			return null;
		});
		// Record so agents (including the speaker) see their own line via /chat.
		long seq = BridgeState.recordChat(name.isEmpty() ? "server" : name, message);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("seq", seq);
		out.put("line", line);
		return out;
	}

	// ---------- POST /test/chat {player, text} — synthetic chat injection ----------

	private Object testChat(HttpExchange exchange, JsonBody body) {
		String player = body.requireString("player");
		String text = body.requireString("text");
		long seq = BridgeState.recordChat(player, text);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("seq", seq);
		return out;
	}

	// ---------- GET /player?name=<n> ----------

	private Object player(HttpExchange exchange, JsonBody body) {
		JsonBody q = query(exchange);
		String name = q.requireString("name");
		Map<String, Object> out = bridge.runOnServer(() -> {
			ServerPlayerEntity p = bridge.server().getPlayerManager().getPlayer(name);
			if (p == null) {
				Map<String, Object> miss = new LinkedHashMap<>();
				miss.put("ok", false);
				miss.put("error", "no player named '" + name + "' online");
				miss.put("online", false);
				return miss;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("ok", true);
			m.put("online", true);
			m.put("name", p.getName().getString());
			m.put("pos", posMap(p.getX(), p.getY(), p.getZ()));
			m.put("yaw", round(p.getYaw()));
			m.put("pitch", round(p.getPitch()));
			m.put("lookingAt", lookingAt(p));
			return m;
		});
		if (Boolean.FALSE.equals(out.get("ok"))) {
			throw new BridgeException(404, (String) out.get("error"));
		}
		return out;
	}

	// ---------- GET /players ----------

	private Object players(HttpExchange exchange, JsonBody body) {
		return bridge.runOnServer(() -> {
			List<Map<String, Object>> list = new ArrayList<>();
			for (ServerPlayerEntity p : bridge.server().getPlayerManager().getPlayerList()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", p.getName().getString());
				m.put("pos", posMap(p.getX(), p.getY(), p.getZ()));
				m.put("yaw", round(p.getYaw()));
				m.put("pitch", round(p.getPitch()));
				list.add(m);
			}
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("ok", true);
			out.put("players", list);
			return out;
		});
	}

	// ---------- POST /status/agent {name, activity, detail?, progress?} ----------

	private Object statusAgent(HttpExchange exchange, JsonBody body) {
		String name = body.requireString("name");
		String activity = body.getString("activity", "");
		String detail = body.getString("detail", "");
		Double progress = body.has("progress") ? body.getDouble("progress") : null;
		BridgeState.putStatus(name, activity, detail, progress);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		return out;
	}

	// ---------- GET /status ----------

	private Object status(HttpExchange exchange, JsonBody body) {
		List<Map<String, Object>> players = bridge.runOnServer(() -> {
			List<Map<String, Object>> list = new ArrayList<>();
			for (ServerPlayerEntity p : bridge.server().getPlayerManager().getPlayerList()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", p.getName().getString());
				m.put("pos", posMap(p.getX(), p.getY(), p.getZ()));
				list.add(m);
			}
			return list;
		});
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("agents", BridgeState.statuses());
		out.put("players", players);
		return out;
	}

	/** Server-thread raycast (~64 blocks) from a player's eyes → the block they look at, or null. */
	private Map<String, Object> lookingAt(ServerPlayerEntity p) {
		HitResult hit = p.raycast(64.0, 1.0f, false);
		if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		BlockPos pos = ((BlockHitResult) hit).getBlockPos();
		String blockName = BlockArgumentParser.stringifyBlockState(
			bridge.server().getOverworld().getBlockState(pos));
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("x", pos.getX());
		m.put("y", pos.getY());
		m.put("z", pos.getZ());
		m.put("block", blockName);
		return m;
	}

	private static Map<String, Object> posMap(double x, double y, double z) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("x", round(x));
		m.put("y", round(y));
		m.put("z", round(z));
		return m;
	}

	// ---------- GET /health ----------

	private Object health(HttpExchange exchange, JsonBody body) {
		MinecraftServer server = bridge.server();
		long nanos = server.getAverageNanosPerTick();
		double tps = nanos > 0 ? Math.min(20.0, 1_000_000_000.0 / nanos) : 20.0;
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("mcVersion", server.getVersion());
		out.put("modVersion", MOD_VERSION);
		out.put("tps", Math.round(tps * 100.0) / 100.0);
		return out;
	}

	// ---------- GET /block ----------

	private Object block(HttpExchange exchange, JsonBody body) {
		JsonBody q = query(exchange);
		int x = q.requireInt("x");
		int y = q.requireInt("y");
		int z = q.requireInt("z");
		String name = bridge.runOnServer(() -> {
			ServerWorld world = bridge.server().getOverworld();
			BlockState state = world.getBlockState(new BlockPos(x, y, z));
			return BlockArgumentParser.stringifyBlockState(state);
		});
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("x", x);
		out.put("y", y);
		out.put("z", z);
		out.put("block", name);
		return out;
	}

	// ---------- GET /region ----------

	private Object region(HttpExchange exchange, JsonBody body) {
		JsonBody q = query(exchange);
		int x = q.requireInt("x");
		int y = q.requireInt("y");
		int z = q.requireInt("z");
		int rx = clampRadius(q.getInt("rx", 4), MAX_REGION_RADIUS, "rx");
		int ry = clampRadius(q.getInt("ry", 4), MAX_REGION_RADIUS, "ry");
		int rz = clampRadius(q.getInt("rz", 4), MAX_REGION_RADIUS, "rz");
		long volume = (2L * rx + 1) * (2L * ry + 1) * (2L * rz + 1);
		if (volume > MAX_REGION_VOLUME) {
			throw new BridgeException(400, "region too large: " + volume + " cells (max " + MAX_REGION_VOLUME + ")");
		}

		return bridge.runOnServer(() -> {
			ServerWorld world = bridge.server().getOverworld();
			List<String> palette = new ArrayList<>();
			Map<String, Integer> paletteIndex = new LinkedHashMap<>();
			List<int[]> cells = new ArrayList<>();
			BlockPos.Mutable pos = new BlockPos.Mutable();
			for (int dx = -rx; dx <= rx; dx++) {
				for (int dy = -ry; dy <= ry; dy++) {
					for (int dz = -rz; dz <= rz; dz++) {
						pos.set(x + dx, y + dy, z + dz);
						BlockState state = world.getBlockState(pos);
						if (state.isAir()) {
							continue;
						}
						String key = BlockArgumentParser.stringifyBlockState(state);
						Integer idx = paletteIndex.get(key);
						if (idx == null) {
							idx = palette.size();
							palette.add(key);
							paletteIndex.put(key, idx);
						}
						cells.add(new int[] {x + dx, y + dy, z + dz, idx});
					}
				}
			}
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("ok", true);
			out.put("origin", new int[] {x, y, z});
			out.put("size", new int[] {2 * rx + 1, 2 * ry + 1, 2 * rz + 1});
			out.put("palette", palette);
			out.put("cells", cells);
			out.put("count", cells.size());
			return out;
		});
	}

	// ---------- GET /scan ----------

	private Object scan(HttpExchange exchange, JsonBody body) {
		JsonBody q = query(exchange);
		int x = q.requireInt("x");
		int y = q.requireInt("y");
		int z = q.requireInt("z");
		int r = clampRadius(q.getInt("r", 16), MAX_SCAN_RADIUS, "r");

		return bridge.runOnServer(() -> {
			ServerWorld world = bridge.server().getOverworld();
			Box box = new Box(x - r, y - r, z - r, x + r, y + r, z + r);
			List<Map<String, Object>> players = new ArrayList<>();
			List<Map<String, Object>> entities = new ArrayList<>();
			for (Entity e : world.getOtherEntities(null, box, e -> true)) {
				Map<String, Object> rec = new LinkedHashMap<>();
				rec.put("name", e.getName().getString());
				rec.put("uuid", e.getUuidAsString());
				rec.put("x", round(e.getX()));
				rec.put("y", round(e.getY()));
				rec.put("z", round(e.getZ()));
				if (e instanceof PlayerEntity) {
					rec.put("type", "player");
					players.add(rec);
				} else {
					rec.put("type", Registries.ENTITY_TYPE.getId(e.getType()).toString());
					entities.add(rec);
				}
			}
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("ok", true);
			out.put("origin", new int[] {x, y, z});
			out.put("radius", r);
			out.put("players", players);
			out.put("entities", entities);
			return out;
		});
	}

	// ---------- POST /setblock ----------

	private Object setblock(HttpExchange exchange, JsonBody body) {
		int x = body.requireInt("x");
		int y = body.requireInt("y");
		int z = body.requireInt("z");
		String blockStr = body.requireString("block");
		BlockState state = parseBlock(blockStr);
		boolean changed = bridge.runOnServer(() ->
			doSetBlock(bridge.server().getOverworld(), x, y, z, state));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("changed", changed);
		out.put("block", BlockArgumentParser.stringifyBlockState(state));
		return out;
	}

	// ---------- POST /fill ----------

	private Object fill(HttpExchange exchange, JsonBody body) {
		int x1 = body.requireInt("x1");
		int y1 = body.requireInt("y1");
		int z1 = body.requireInt("z1");
		int x2 = body.requireInt("x2");
		int y2 = body.requireInt("y2");
		int z2 = body.requireInt("z2");
		String blockStr = body.requireString("block");
		BlockState state = parseBlock(blockStr);
		int[] lo = {Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2)};
		int[] hi = {Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)};
		long volume = (long) (hi[0] - lo[0] + 1) * (hi[1] - lo[1] + 1) * (hi[2] - lo[2] + 1);
		if (volume > MAX_FILL_VOLUME) {
			throw new BridgeException(400, "fill too large: " + volume + " cells (max " + MAX_FILL_VOLUME + ")");
		}
		int count = bridge.runOnServer(() ->
			doFill(bridge.server().getOverworld(), lo, hi, state));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("count", count);
		out.put("volume", volume);
		out.put("block", BlockArgumentParser.stringifyBlockState(state));
		return out;
	}

	// ---------- POST /command ----------

	private Object command(HttpExchange exchange, JsonBody body) {
		String cmd = body.requireString("command");
		String output = bridge.runOnServer(() -> doCommand(cmd));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("output", output);
		return out;
	}

	// ---------- POST /batch ----------

	private Object batch(HttpExchange exchange, JsonBody body) {
		var ops = body.getArray("ops");
		return bridge.runOnServer(() -> {
			ServerWorld world = bridge.server().getOverworld();
			List<Map<String, Object>> results = new ArrayList<>();
			for (JsonElement el : ops) {
				JsonObject op = el.getAsJsonObject();
				String type = op.has("op") ? op.get("op").getAsString() : "";
				Map<String, Object> res = new LinkedHashMap<>();
				res.put("op", type);
				try {
					switch (type) {
						case "setblock" -> {
							BlockState st = parseBlock(op.get("block").getAsString());
							boolean changed = doSetBlock(world,
								op.get("x").getAsInt(), op.get("y").getAsInt(), op.get("z").getAsInt(), st);
							res.put("ok", true);
							res.put("changed", changed);
						}
						case "fill" -> {
							BlockState st = parseBlock(op.get("block").getAsString());
							int[] lo = {
								Math.min(op.get("x1").getAsInt(), op.get("x2").getAsInt()),
								Math.min(op.get("y1").getAsInt(), op.get("y2").getAsInt()),
								Math.min(op.get("z1").getAsInt(), op.get("z2").getAsInt())};
							int[] hi = {
								Math.max(op.get("x1").getAsInt(), op.get("x2").getAsInt()),
								Math.max(op.get("y1").getAsInt(), op.get("y2").getAsInt()),
								Math.max(op.get("z1").getAsInt(), op.get("z2").getAsInt())};
							long volume = (long) (hi[0] - lo[0] + 1) * (hi[1] - lo[1] + 1) * (hi[2] - lo[2] + 1);
							if (volume > MAX_FILL_VOLUME) {
								throw new BridgeException(400, "fill too large in batch: " + volume);
							}
							int count = doFill(world, lo, hi, st);
							res.put("ok", true);
							res.put("count", count);
						}
						case "command" -> {
							String output = doCommand(op.get("command").getAsString());
							res.put("ok", true);
							res.put("output", output);
						}
						default -> {
							res.put("ok", false);
							res.put("error", "unknown op: " + type);
						}
					}
				} catch (Exception e) {
					res.put("ok", false);
					res.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
				}
				results.add(res);
			}
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("ok", true);
			out.put("results", results);
			return out;
		});
	}

	// ---------- server-thread helpers (assume they run ON the server thread) ----------

	private boolean doSetBlock(ServerWorld world, int x, int y, int z, BlockState state) {
		BlockPos pos = new BlockPos(x, y, z);
		BlockState existing = world.getBlockState(pos);
		if (existing.equals(state)) {
			return false;
		}
		return world.setBlockState(pos, state);
	}

	private int doFill(ServerWorld world, int[] lo, int[] hi, BlockState state) {
		int count = 0;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int x = lo[0]; x <= hi[0]; x++) {
			for (int y = lo[1]; y <= hi[1]; y++) {
				for (int z = lo[2]; z <= hi[2]; z++) {
					pos.set(x, y, z);
					if (world.setBlockState(pos, state)) {
						count++;
					}
				}
			}
		}
		return count;
	}

	private String doCommand(String cmd) {
		MinecraftServer server = bridge.server();
		StringBuilder captured = new StringBuilder();
		CommandOutput sink = new CommandOutput() {
			@Override
			public void sendMessage(Text message) {
				if (captured.length() > 0) {
					captured.append('\n');
				}
				captured.append(message.getString());
			}

			@Override
			public boolean shouldReceiveFeedback() {
				return true;
			}

			@Override
			public boolean shouldTrackOutput() {
				return true;
			}

			@Override
			public boolean shouldBroadcastConsoleToOps() {
				return false;
			}
		};
		ServerCommandSource source = server.getCommandSource()
			.withOutput(sink)
			.withLevel(4);
		server.getCommandManager().executeWithPrefix(source, cmd);
		return captured.toString();
	}

	// ---------- misc helpers ----------

	private BlockState parseBlock(String blockStr) {
		try {
			return BlockArgumentParser.block(Registries.BLOCK, blockStr, false).blockState();
		} catch (CommandSyntaxException e) {
			throw new BridgeException(400, "bad block '" + blockStr + "': " + e.getMessage());
		}
	}

	private int clampRadius(int value, int max, String name) {
		if (value < 0) {
			throw new BridgeException(400, name + " must be >= 0");
		}
		return Math.min(value, max);
	}

	private static double round(double v) {
		return Math.round(v * 1000.0) / 1000.0;
	}

	/** Parses the URI query string into a {@link JsonBody} of string properties. */
	private JsonBody query(HttpExchange exchange) {
		JsonObject obj = new JsonObject();
		String raw = exchange.getRequestURI().getRawQuery();
		if (raw != null && !raw.isEmpty()) {
			for (String pair : raw.split("&")) {
				int eq = pair.indexOf('=');
				if (eq < 0) {
					continue;
				}
				String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
				String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
				obj.addProperty(k, v);
			}
		}
		return new JsonBody(bridge.gson(), obj);
	}
}
