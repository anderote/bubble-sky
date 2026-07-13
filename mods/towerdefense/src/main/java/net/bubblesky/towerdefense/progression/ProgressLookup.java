package net.bubblesky.towerdefense.progression;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Tiny server-side convenience for reading a player's use-time multipliers straight
 * from a {@link PlayerEntity}, so gameplay call-sites (bow fire, coin payout) can apply
 * progression with a one-line lookup and no {@link ProgressState} boilerplate.
 *
 * <p>Both helpers default to {@code 1.0} (no effect) for anything that isn't a fully
 * resolved server player — keeping the hooks safe to call unconditionally.
 */
public final class ProgressLookup {

	private ProgressLookup() {
	}

	/** Fired-arrow damage multiplier for a player (1.0 if not a server player). */
	public static double bowMult(PlayerEntity player) {
		PlayerProgress progress = progressOf(player);
		return progress == null ? 1.0 : StatModifiers.bowMult(progress);
	}

	/** Coin-payout multiplier for a player (1.0 if not a server player). */
	public static double coinMult(PlayerEntity player) {
		PlayerProgress progress = progressOf(player);
		return progress == null ? 1.0 : StatModifiers.coinMult(progress);
	}

	private static PlayerProgress progressOf(PlayerEntity player) {
		if (!(player instanceof ServerPlayerEntity serverPlayer)) {
			return null;
		}
		MinecraftServer server = serverPlayer.getServer();
		if (server == null) {
			return null;
		}
		return ProgressState.get(server).forPlayer(serverPlayer.getUuid());
	}
}
