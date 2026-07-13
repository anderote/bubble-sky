package net.bubblesky.towerdefense.colony;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Natural-language colony control in ordinary chat. Registers a
 * {@link ServerMessageEvents#CHAT_MESSAGE} listener that parses a leading
 * {@code "<name|colonists|all> <verb> [target]"} out of any chat line and routes it to
 * the same {@link ColonyOrders} logic the {@code /colony} command uses — so
 * <em>"Alden mine iron"</em>, <em>"colonists forage"</em> or <em>"Bram prioritize chop"</em>
 * all work. The chat message is NOT cancelled; it still shows normally (and this is a
 * post-broadcast listener regardless), with a small confirmation whispered back to the
 * speaker when an order lands.
 */
public final class ColonyChat {
	private ColonyChat() {
	}

	/** The verbs that make a chat line a colony order. */
	private static final Set<String> VERBS = Set.of(
		"mine", "chop", "hunt", "forage", "haul", "come", "stop", "idle", "rest",
		"follow", "here", "prioritize", "priority", "prefer");

	public static void register() {
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
			handle(sender, message.getSignedContent()));
	}

	private static void handle(ServerPlayerEntity sender, String raw) {
		if (sender == null || raw == null || raw.isBlank()) {
			return;
		}
		// Split into up to three parts: <who> <verb> <rest-as-target>.
		String[] parts = raw.trim().split("\\s+", 3);
		if (parts.length < 2) {
			return;
		}
		String who = parts[0];
		// Strip a trailing comma/colon from a name address ("Alden, mine").
		if (who.endsWith(",") || who.endsWith(":")) {
			who = who.substring(0, who.length() - 1);
		}
		String verb = parts[1].toLowerCase(Locale.ROOT);
		String target = parts.length >= 3 ? parts[2].trim() : null;

		// For "prioritize", the work type is the third token; keep only the first word.
		if ((verb.equals("prioritize") || verb.equals("priority") || verb.equals("prefer"))
				&& target != null) {
			target = target.split("\\s+")[0];
		}
		// For "mine <ore> [wood]" keep just the first target word as the ore keyword.
		if (verb.equals("mine") && target != null) {
			target = target.split("\\s+")[0];
		}

		if (!VERBS.contains(verb)) {
			return; // not a colony order — leave the chat line be
		}

		ServerWorld world = (ServerWorld) sender.getWorld();
		List<ColonistEntity> colonists = ColonyOrders.resolve(world, sender, who);
		if (colonists.isEmpty()) {
			return; // "who" didn't name a colonist / the colony — ignore quietly
		}
		String feedback = ColonyOrders.apply(sender, colonists, verb, target);
		if (feedback != null) {
			sender.sendMessage(Text.literal("[colony] " + feedback).formatted(Formatting.AQUA), false);
		}
	}
}
