package net.bubblesky.towerdefense.bridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-wide, thread-safe state shared between the {@link AgentBridge} HTTP
 * handlers and the server-thread event hooks:
 *
 * <ul>
 *   <li>a bounded chat <b>ring buffer</b> with a monotonic {@code seq} so agents
 *       can long-poll {@code GET /chat?since=<seq>} for new player chat (and the
 *       bridge's own {@code /say} lines);</li>
 *   <li>an <b>agent status</b> map ({@code name -> {activity, detail, progress,
 *       ts}}) that agents report to via {@code POST /status/agent} and a HUD
 *       reads via {@code GET /status}.</li>
 * </ul>
 *
 * <p>Static + synchronized because the chat event fires on the server thread
 * while HTTP handlers read from worker threads. No world access happens here.
 */
final class BridgeState {
	private BridgeState() {}

	/** Max chat lines retained; older lines are evicted (their seq stays valid, just gone). */
	private static final int MAX_CHAT = 512;

	// ---- chat ring buffer ----

	static final class ChatLine {
		final long seq;
		final String player;
		final String text;
		final long ts;

		ChatLine(long seq, String player, String text, long ts) {
			this.seq = seq;
			this.player = player;
			this.text = text;
			this.ts = ts;
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("seq", seq);
			m.put("player", player);
			m.put("text", text);
			m.put("ts", ts);
			return m;
		}
	}

	private static final Object CHAT_LOCK = new Object();
	private static final Deque<ChatLine> CHAT = new ArrayDeque<>();
	private static long chatSeq = 0;

	/** Append a chat line (player message or a bridge {@code /say}) and return its assigned seq. */
	static long recordChat(String player, String text) {
		synchronized (CHAT_LOCK) {
			long seq = ++chatSeq;
			CHAT.addLast(new ChatLine(seq, player == null ? "" : player, text == null ? "" : text,
				System.currentTimeMillis()));
			while (CHAT.size() > MAX_CHAT) {
				CHAT.removeFirst();
			}
			return seq;
		}
	}

	/** The current highest assigned seq (cursor for a caller that only wants NEW lines). */
	static long chatCursor() {
		synchronized (CHAT_LOCK) {
			return chatSeq;
		}
	}

	/** All retained lines with {@code seq > since}, in order. */
	static List<Map<String, Object>> chatSince(long since) {
		List<Map<String, Object>> out = new ArrayList<>();
		synchronized (CHAT_LOCK) {
			for (ChatLine line : CHAT) {
				if (line.seq > since) {
					out.add(line.toMap());
				}
			}
		}
		return out;
	}

	// ---- agent status map ----

	static final class Status {
		volatile String activity = "";
		volatile String detail = "";
		volatile Double progress = null;
		volatile long ts = 0;

		Map<String, Object> toMap(String name) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", name);
			m.put("activity", activity);
			m.put("detail", detail);
			m.put("progress", progress);
			m.put("ts", ts);
			return m;
		}
	}

	private static final Map<String, Status> STATUS = new LinkedHashMap<>();

	static void putStatus(String name, String activity, String detail, Double progress) {
		if (name == null || name.isBlank()) {
			return;
		}
		synchronized (STATUS) {
			Status s = STATUS.computeIfAbsent(name, k -> new Status());
			s.activity = activity == null ? "" : activity;
			s.detail = detail == null ? "" : detail;
			s.progress = progress;
			s.ts = System.currentTimeMillis();
		}
	}

	static List<Map<String, Object>> statuses() {
		List<Map<String, Object>> out = new ArrayList<>();
		synchronized (STATUS) {
			for (Map.Entry<String, Status> e : STATUS.entrySet()) {
				out.add(e.getValue().toMap(e.getKey()));
			}
		}
		return out;
	}
}
