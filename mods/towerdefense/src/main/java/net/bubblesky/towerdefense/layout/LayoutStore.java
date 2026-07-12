package net.bubblesky.towerdefense.layout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.bubblesky.towerdefense.TowerDefenseMod;

/**
 * Process-wide, thread-safe store for the Layout Wand planning tool: named
 * <b>flags</b> (single points) and rectangular <b>regions</b> (corner A → corner
 * B). This is the ONE shared source of truth — the in-game wand, the AI-agent
 * HTTP bridge ({@code GET/POST/DELETE /flags}, {@code GET/POST /regions}) and
 * Grok's chat flag system all read and write here.
 *
 * <p>Persisted to {@code <runDir>/config/bubblesky-layout.json} so plans survive
 * restarts. All mutating methods are {@code synchronized}; the wand mutates on
 * the server thread while HTTP handlers read from worker threads (they marshal
 * onto the server thread anyway, but the lock keeps reads consistent regardless).
 */
public final class LayoutStore {
	private LayoutStore() {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Object LOCK = new Object();

	/** name -> flag record. LinkedHashMap keeps insertion order for stable listings. */
	private static final Map<String, Flag> FLAGS = new LinkedHashMap<>();
	/** name -> region record. */
	private static final Map<String, Region> REGIONS = new LinkedHashMap<>();

	private static Path file;

	// ---- records ----------------------------------------------------------

	public record Flag(String name, int x, int y, int z, String dim) {
		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", name);
			m.put("x", x);
			m.put("y", y);
			m.put("z", z);
			m.put("dim", dim);
			return m;
		}
	}

	public record Region(String name, int ax, int ay, int az, int bx, int by, int bz, String dim) {
		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", name);
			m.put("a", corner(ax, ay, az));
			m.put("b", corner(bx, by, bz));
			m.put("dim", dim);
			return m;
		}

		private static Map<String, Object> corner(int x, int y, int z) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("x", x);
			m.put("y", y);
			m.put("z", z);
			return m;
		}
	}

	// ---- lifecycle --------------------------------------------------------

	/** Point the store at its backing file and load any persisted plan. Idempotent. */
	public static void init(Path runDir) {
		synchronized (LOCK) {
			file = runDir.resolve("config").resolve("bubblesky-layout.json");
			load();
		}
	}

	private static void load() {
		FLAGS.clear();
		REGIONS.clear();
		if (file == null || !Files.isRegularFile(file)) {
			return;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			if (root.has("flags") && root.get("flags").isJsonObject()) {
				for (var e : root.getAsJsonObject("flags").entrySet()) {
					JsonObject f = e.getValue().getAsJsonObject();
					FLAGS.put(e.getKey(), new Flag(e.getKey(),
						f.get("x").getAsInt(), f.get("y").getAsInt(), f.get("z").getAsInt(),
						f.has("dim") ? f.get("dim").getAsString() : "overworld"));
				}
			}
			if (root.has("regions") && root.get("regions").isJsonObject()) {
				for (var e : root.getAsJsonObject("regions").entrySet()) {
					JsonObject r = e.getValue().getAsJsonObject();
					JsonObject a = r.getAsJsonObject("a");
					JsonObject b = r.getAsJsonObject("b");
					REGIONS.put(e.getKey(), new Region(e.getKey(),
						a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt(),
						b.get("x").getAsInt(), b.get("y").getAsInt(), b.get("z").getAsInt(),
						r.has("dim") ? r.get("dim").getAsString() : "overworld"));
				}
			}
			TowerDefenseMod.LOGGER.info("[layout] loaded {} flags + {} regions from {}",
				FLAGS.size(), REGIONS.size(), file);
		} catch (Exception e) {
			TowerDefenseMod.LOGGER.warn("[layout] failed to read {}: {}", file, e.toString());
		}
	}

	private static void save() {
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			JsonObject flags = new JsonObject();
			for (Flag f : FLAGS.values()) {
				JsonObject o = new JsonObject();
				o.addProperty("x", f.x);
				o.addProperty("y", f.y);
				o.addProperty("z", f.z);
				o.addProperty("dim", f.dim);
				flags.add(f.name, o);
			}
			JsonObject regions = new JsonObject();
			for (Region r : REGIONS.values()) {
				JsonObject o = new JsonObject();
				JsonObject a = new JsonObject();
				a.addProperty("x", r.ax); a.addProperty("y", r.ay); a.addProperty("z", r.az);
				JsonObject b = new JsonObject();
				b.addProperty("x", r.bx); b.addProperty("y", r.by); b.addProperty("z", r.bz);
				o.add("a", a);
				o.add("b", b);
				o.addProperty("dim", r.dim);
				regions.add(r.name, o);
			}
			root.add("flags", flags);
			root.add("regions", regions);
			Files.writeString(file, GSON.toJson(root));
		} catch (Exception e) {
			TowerDefenseMod.LOGGER.warn("[layout] could not persist {}: {}", file, e.toString());
		}
	}

	// ---- flags ------------------------------------------------------------

	/** Case-insensitive lookup. Returns null if absent. */
	public static Flag getFlag(String name) {
		if (name == null) {
			return null;
		}
		synchronized (LOCK) {
			for (Flag f : FLAGS.values()) {
				if (f.name.equalsIgnoreCase(name.trim())) {
					return f;
				}
			}
			return null;
		}
	}

	/** Plant/replace a flag. Returns the stored record. */
	public static Flag putFlag(String name, int x, int y, int z, String dim) {
		String clean = (name == null || name.isBlank()) ? "flag" : name.trim();
		synchronized (LOCK) {
			// Remove any existing key that matches case-insensitively, then insert.
			FLAGS.keySet().removeIf(k -> k.equalsIgnoreCase(clean));
			Flag f = new Flag(clean, x, y, z, dim == null ? "overworld" : dim);
			FLAGS.put(clean, f);
			save();
			return f;
		}
	}

	/** Remove a flag by (case-insensitive) name. Returns the removed record, or null. */
	public static Flag removeFlag(String name) {
		if (name == null) {
			return null;
		}
		synchronized (LOCK) {
			String key = null;
			for (String k : FLAGS.keySet()) {
				if (k.equalsIgnoreCase(name.trim())) {
					key = k;
					break;
				}
			}
			if (key == null) {
				return null;
			}
			Flag removed = FLAGS.remove(key);
			save();
			return removed;
		}
	}

	public static List<Map<String, Object>> flagMaps() {
		synchronized (LOCK) {
			List<Map<String, Object>> out = new ArrayList<>();
			for (Flag f : FLAGS.values()) {
				out.add(f.toMap());
			}
			return out;
		}
	}

	public static List<Flag> flags() {
		synchronized (LOCK) {
			return new ArrayList<>(FLAGS.values());
		}
	}

	/**
	 * Next auto-name for a flag given a single-letter prefix (e.g. "A" → "A1",
	 * "A2", …). Scans existing {@code <letter><n>} names and returns letter + one
	 * past the highest number seen.
	 */
	public static String nextFlagName(String letter) {
		String p = (letter == null || letter.isBlank())
			? "A" : letter.trim().substring(0, 1).toUpperCase();
		synchronized (LOCK) {
			int max = 0;
			for (String k : FLAGS.keySet()) {
				if (k.length() >= 2 && k.substring(0, 1).equalsIgnoreCase(p)) {
					try {
						max = Math.max(max, Integer.parseInt(k.substring(1)));
					} catch (NumberFormatException ignored) {
						// non-numeric suffix — skip
					}
				}
			}
			return p + (max + 1);
		}
	}

	/** Nearest flag to (x,y,z) within {@code maxDist} blocks, or null. */
	public static Flag nearestFlag(double x, double y, double z, double maxDist) {
		synchronized (LOCK) {
			Flag best = null;
			double bestSq = maxDist * maxDist;
			for (Flag f : FLAGS.values()) {
				double dx = f.x + 0.5 - x, dy = f.y + 0.5 - y, dz = f.z + 0.5 - z;
				double sq = dx * dx + dy * dy + dz * dz;
				if (sq <= bestSq) {
					bestSq = sq;
					best = f;
				}
			}
			return best;
		}
	}

	// ---- regions ----------------------------------------------------------

	public static Region getRegion(String name) {
		if (name == null) {
			return null;
		}
		synchronized (LOCK) {
			for (Region r : REGIONS.values()) {
				if (r.name.equalsIgnoreCase(name.trim())) {
					return r;
				}
			}
			return null;
		}
	}

	public static Region putRegion(String name, int ax, int ay, int az, int bx, int by, int bz, String dim) {
		String clean = (name == null || name.isBlank()) ? nextRegionName() : name.trim();
		synchronized (LOCK) {
			REGIONS.keySet().removeIf(k -> k.equalsIgnoreCase(clean));
			Region r = new Region(clean, ax, ay, az, bx, by, bz, dim == null ? "overworld" : dim);
			REGIONS.put(clean, r);
			save();
			return r;
		}
	}

	public static Region removeRegion(String name) {
		if (name == null) {
			return null;
		}
		synchronized (LOCK) {
			String key = null;
			for (String k : REGIONS.keySet()) {
				if (k.equalsIgnoreCase(name.trim())) {
					key = k;
					break;
				}
			}
			if (key == null) {
				return null;
			}
			Region removed = REGIONS.remove(key);
			save();
			return removed;
		}
	}

	public static List<Map<String, Object>> regionMaps() {
		synchronized (LOCK) {
			List<Map<String, Object>> out = new ArrayList<>();
			for (Region r : REGIONS.values()) {
				out.add(r.toMap());
			}
			return out;
		}
	}

	public static List<Region> regions() {
		synchronized (LOCK) {
			return new ArrayList<>(REGIONS.values());
		}
	}

	/** Next auto-name for a region: "Region 1", "Region 2", … */
	public static String nextRegionName() {
		synchronized (LOCK) {
			int max = 0;
			for (String k : REGIONS.keySet()) {
				if (k.toLowerCase().startsWith("region")) {
					try {
						max = Math.max(max, Integer.parseInt(k.replaceAll("[^0-9]", "")));
					} catch (NumberFormatException ignored) {
						// no number — skip
					}
				}
			}
			return "Region " + (max + 1);
		}
	}
}
