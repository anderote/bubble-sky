package net.bubblesky.towerdefense.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Thin typed accessor over a parsed JSON request body (or query params). Missing
 * required fields raise a {@link AgentBridge.BridgeException} with a 400 status
 * so handlers stay terse.
 */
final class JsonBody {
	private final Gson gson;
	private final JsonObject obj;

	JsonBody(Gson gson, JsonObject obj) {
		this.gson = gson;
		this.obj = obj == null ? new JsonObject() : obj;
	}

	static JsonBody parse(Gson gson, String text) {
		JsonElement el = JsonParser.parseString(text);
		if (!el.isJsonObject()) {
			throw new AgentBridge.BridgeException(400, "request body must be a JSON object");
		}
		return new JsonBody(gson, el.getAsJsonObject());
	}

	Gson gson() {
		return gson;
	}

	/** The underlying JSON object (for nested access like corner {x,y,z}). */
	JsonObject raw() {
		return obj;
	}

	boolean has(String key) {
		return obj.has(key) && !obj.get(key).isJsonNull();
	}

	int requireInt(String key) {
		if (!has(key)) {
			throw new AgentBridge.BridgeException(400, "missing required int field: " + key);
		}
		try {
			return obj.get(key).getAsInt();
		} catch (Exception e) {
			throw new AgentBridge.BridgeException(400, "field '" + key + "' must be an int");
		}
	}

	int getInt(String key, int def) {
		if (!has(key)) {
			return def;
		}
		try {
			return obj.get(key).getAsInt();
		} catch (Exception e) {
			throw new AgentBridge.BridgeException(400, "field '" + key + "' must be an int");
		}
	}

	Double getDouble(String key) {
		if (!has(key)) {
			return null;
		}
		try {
			return obj.get(key).getAsDouble();
		} catch (Exception e) {
			throw new AgentBridge.BridgeException(400, "field '" + key + "' must be a number");
		}
	}

	String requireString(String key) {
		if (!has(key)) {
			throw new AgentBridge.BridgeException(400, "missing required string field: " + key);
		}
		return obj.get(key).getAsString();
	}

	String getString(String key, String def) {
		return has(key) ? obj.get(key).getAsString() : def;
	}

	JsonArray getArray(String key) {
		if (!has(key) || !obj.get(key).isJsonArray()) {
			throw new AgentBridge.BridgeException(400, "missing required array field: " + key);
		}
		return obj.get(key).getAsJsonArray();
	}
}
