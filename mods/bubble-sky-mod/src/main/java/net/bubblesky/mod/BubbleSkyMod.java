package net.bubblesky.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bubble Sky: a minimal, server-side, vanilla-compatible Fabric mod.
 *
 * <p>It intentionally registers NO custom blocks or items so that vanilla
 * clients and Mineflayer bots stay fully compatible (the coexistence rule).
 */
public class BubbleSkyMod implements ModInitializer {
	public static final String MOD_ID = "bubble-sky";
	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("[bubble-sky] mod initialized");

		// Register /bubblesky, executable by anyone (no permission requirement)
		// so vanilla players and bots can invoke it.
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(
				CommandManager.literal("bubblesky").executes(context -> {
					context.getSource().sendFeedback(
						() -> Text.literal("bubble-sky mod is alive ✦"), false);
					return 1;
				})
			)
		);
	}
}
