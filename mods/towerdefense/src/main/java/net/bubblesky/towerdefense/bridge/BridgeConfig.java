package net.bubblesky.towerdefense.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.bubblesky.towerdefense.TowerDefenseMod;

/**
 * Configuration for the in-JVM {@link AgentBridge} HTTP API.
 *
 * <p>Resolution order (later wins):
 * <ol>
 *   <li>Built-in defaults ({@code enabled=true}, {@code port=25580}).</li>
 *   <li>{@code config/bubblesky-bridge.json} in the server run directory.</li>
 *   <li>Environment variables {@code BUBBLESKY_BRIDGE_ENABLED},
 *       {@code BUBBLESKY_BRIDGE_PORT}, {@code BUBBLESKY_BRIDGE_TOKEN}.</li>
 * </ol>
 *
 * <p>The bridge is localhost-bound <em>and</em> token-gated. If no token is
 * supplied by file or env, a random one is generated and written back to the
 * config file (and logged) so a co-located agent can read it.
 */
public final class BridgeConfig {
	private static final int DEFAULT_PORT = 25580;
	private static final String DEFAULT_BIND_HOST = "127.0.0.1";

	public final boolean enabled;
	public final int port;
	public final String token;
	public final String bindHost;

	private BridgeConfig(boolean enabled, int port, String token, String bindHost) {
		this.enabled = enabled;
		this.port = port;
		this.token = token;
		this.bindHost = bindHost;
	}

	/** Loads config from {@code <runDir>/config/bubblesky-bridge.json} + env, persisting a generated token. */
	public static BridgeConfig load(Path runDir) {
		boolean enabled = true;
		int port = DEFAULT_PORT;
		String token = "";
		String bindHost = DEFAULT_BIND_HOST;

		Path configPath = runDir.resolve("config").resolve("bubblesky-bridge.json");
		try {
			if (Files.isRegularFile(configPath)) {
				JsonObject json = JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();
				if (json.has("enabled")) {
					enabled = json.get("enabled").getAsBoolean();
				}
				if (json.has("port")) {
					port = json.get("port").getAsInt();
				}
				if (json.has("token")) {
					token = json.get("token").getAsString();
				}
				if (json.has("bindHost")) {
					bindHost = json.get("bindHost").getAsString().trim();
				}
			}
		} catch (Exception e) {
			TowerDefenseMod.LOGGER.warn("[bridge] failed to read {}: {}", configPath, e.toString());
		}

		String envEnabled = System.getenv("BUBBLESKY_BRIDGE_ENABLED");
		if (envEnabled != null && !envEnabled.isBlank()) {
			enabled = !envEnabled.equalsIgnoreCase("false") && !envEnabled.equals("0");
		}
		String envPort = System.getenv("BUBBLESKY_BRIDGE_PORT");
		if (envPort != null && !envPort.isBlank()) {
			try {
				port = Integer.parseInt(envPort.trim());
			} catch (NumberFormatException ignored) {
				TowerDefenseMod.LOGGER.warn("[bridge] bad BUBBLESKY_BRIDGE_PORT={}", envPort);
			}
		}
		String envToken = System.getenv("BUBBLESKY_BRIDGE_TOKEN");
		if (envToken != null && !envToken.isBlank()) {
			token = envToken.trim();
		}
		String envBindHost = System.getenv("BUBBLESKY_BRIDGE_BIND_HOST");
		if (envBindHost != null && !envBindHost.isBlank()) {
			bindHost = envBindHost.trim();
		}
		if (bindHost.isBlank()) {
			bindHost = DEFAULT_BIND_HOST;
		}

		boolean generated = false;
		if (token == null || token.isBlank()) {
			token = "td-" + UUID.randomUUID().toString().replace("-", "");
			generated = true;
		}

		BridgeConfig config = new BridgeConfig(enabled, port, token, bindHost);

		if (generated) {
			try {
				Files.createDirectories(configPath.getParent());
				Gson gson = new GsonBuilder().setPrettyPrinting().create();
				JsonObject out = new JsonObject();
				out.addProperty("enabled", enabled);
				out.addProperty("port", port);
				out.addProperty("token", token);
				out.addProperty("bindHost", bindHost);
				Files.writeString(configPath, gson.toJson(out));
				TowerDefenseMod.LOGGER.info("[bridge] generated token written to {} (X-Bridge-Token: {})",
					configPath, token);
			} catch (Exception e) {
				TowerDefenseMod.LOGGER.warn("[bridge] could not persist generated token: {}", e.toString());
			}
		}

		return config;
	}
}
