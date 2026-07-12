package net.bubblesky.towerdefense.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.bubblesky.towerdefense.TowerDefenseMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/**
 * In-JVM HTTP control surface for AI agents on a MODDED server.
 *
 * <p>Instead of speaking the vanilla protocol (which breaks Mineflayer-style
 * bots the moment the mod adds custom blocks/entities), agents talk plain HTTP
 * to this bridge. The bridge is:
 * <ul>
 *   <li>bound to {@code 127.0.0.1} (localhost only),</li>
 *   <li>token-gated via the {@code X-Bridge-Token} header,</li>
 *   <li>thread-safe — every world read/mutation is marshalled onto the server
 *       thread via {@link #runOnServer(Supplier)} ({@code server.submit(...)}).</li>
 * </ul>
 *
 * <p>Lifecycle: started on {@link ServerLifecycleEvents#SERVER_STARTED} and
 * stopped on {@link ServerLifecycleEvents#SERVER_STOPPING}. If disabled in
 * config, no socket is opened.
 */
public final class AgentBridge {
	/** Bounded wait for any server-thread task; prevents an HTTP thread hanging on a stalled server. */
	static final long SERVER_TASK_TIMEOUT_MS = 5000L;

	private static AgentBridge instance;

	private final MinecraftServer server;
	private final BridgeConfig config;
	private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
	private HttpServer httpServer;
	private ExecutorService executor;

	private AgentBridge(MinecraftServer server, BridgeConfig config) {
		this.server = server;
		this.config = config;
	}

	/** Registers the lifecycle listeners. Call once from the mod initializer. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(AgentBridge::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
		// Capture player chat into the shared ring buffer so agents can long-poll
		// GET /chat. Fires on the server thread; BridgeState is synchronized.
		net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			try {
				BridgeState.recordChat(sender.getName().getString(), message.getSignedContent());
			} catch (Exception ignored) {
				// never let chat capture break the game loop
			}
		});
	}

	private static void start(MinecraftServer server) {
		BridgeConfig config = BridgeConfig.load(server.getRunDirectory());
		if (!config.enabled) {
			TowerDefenseMod.LOGGER.info("[bridge] disabled by config — not starting HTTP server");
			return;
		}
		AgentBridge bridge = new AgentBridge(server, config);
		try {
			bridge.startHttp();
			instance = bridge;
		} catch (IOException e) {
			TowerDefenseMod.LOGGER.error("[bridge] failed to start on 127.0.0.1:{}: {}", config.port, e.toString());
		}
	}

	private static void stop() {
		if (instance != null) {
			instance.stopHttp();
			instance = null;
		}
	}

	private void startHttp() throws IOException {
		httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", config.port), 0);
		// Small pool of DAEMON threads: work is quick and mostly blocks on the
		// server-thread hop. Daemon + explicit shutdown so the pool never keeps
		// the Minecraft JVM alive after SERVER_STOPPING.
		AtomicInteger n = new AtomicInteger();
		executor = Executors.newFixedThreadPool(4, r -> {
			Thread t = new Thread(r, "bubblesky-bridge-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
		httpServer.setExecutor(executor);
		BridgeHandlers handlers = new BridgeHandlers(this);
		handlers.register(httpServer);
		httpServer.start();
		TowerDefenseMod.LOGGER.info("[bridge] listening on http://127.0.0.1:{} (token required in X-Bridge-Token)", config.port);
	}

	private void stopHttp() {
		if (httpServer != null) {
			httpServer.stop(0);
			httpServer = null;
		}
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
		TowerDefenseMod.LOGGER.info("[bridge] stopped");
	}

	// ---- accessors used by handlers ----

	MinecraftServer server() {
		return server;
	}

	Gson gson() {
		return gson;
	}

	/**
	 * Runs {@code task} on the server thread and blocks (bounded) for the result.
	 * The world must NEVER be touched from an HTTP worker thread; route all reads
	 * and mutations through here.
	 */
	<T> T runOnServer(Supplier<T> task) {
		CompletableFuture<T> future = server.submit(task);
		try {
			return future.get(SERVER_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			throw new RuntimeException("server-thread task failed: " + e.getMessage(), e);
		}
	}

	// ---- HTTP plumbing shared by handlers ----

	/** A route that returns a JSON-serializable object or throws (mapped to a 4xx/5xx). */
	@FunctionalInterface
	interface Route {
		Object handle(HttpExchange exchange, JsonBody body) throws Exception;
	}

	/** Thrown by a route to signal a client error with a specific status. */
	static final class BridgeException extends RuntimeException {
		final int status;

		BridgeException(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	/**
	 * Wraps a route with token auth, request-body parsing, JSON serialization and
	 * uniform error handling. GET routes ignore the body.
	 */
	HttpHandler secure(Route route) {
		return exchange -> {
			try (exchange) {
				String token = exchange.getRequestHeaders().getFirst("X-Bridge-Token");
				if (token == null || !token.equals(config.token)) {
					send(exchange, 401, error("unauthorized: missing or bad X-Bridge-Token"));
					return;
				}
				JsonBody body = readBody(exchange);
				Object result;
				try {
					result = route.handle(exchange, body);
				} catch (BridgeException be) {
					send(exchange, be.status, error(be.getMessage()));
					return;
				} catch (Exception e) {
					send(exchange, 500, error(e.getMessage() == null ? e.toString() : e.getMessage()));
					return;
				}
				send(exchange, 200, result);
			} catch (Exception outer) {
				TowerDefenseMod.LOGGER.warn("[bridge] handler error: {}", outer.toString());
			}
		};
	}

	private JsonBody readBody(HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody()) {
			byte[] raw = in.readAllBytes();
			if (raw.length == 0) {
				return new JsonBody(gson, null);
			}
			String text = new String(raw, StandardCharsets.UTF_8);
			return JsonBody.parse(gson, text);
		}
	}

	private Object error(String message) {
		return java.util.Map.of("ok", false, "error", message == null ? "error" : message);
	}

	private void send(HttpExchange exchange, int status, Object payload) throws IOException {
		byte[] out = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, out.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(out);
		}
	}
}
