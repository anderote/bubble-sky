package net.bubblesky.towerdefense.bridge;

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
import java.util.Map;
import net.bubblesky.towerdefense.bridge.AgentBridge.BridgeException;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;

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
