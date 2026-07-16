package net.bubblesky.towerdefense.towerui.net;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: a client pressed the "toggle enemy outline" keybind (default {@code N}).
 *
 * <p>An EMPTY payload — it carries no data, it is purely a signal. The actual
 * outline state is a single server-authoritative, session-scoped GLOBAL flag in
 * {@link net.bubblesky.towerdefense.game.WaveManager}; receiving this packet simply
 * flips that shared flag (for ALL players) and re-syncs every live wave enemy's
 * {@code GLOWING} effect to match. Nothing here is per-player, so the codec is the
 * trivial {@linkplain PacketCodec#unit(Object) unit} codec that reads/writes zero
 * bytes and always decodes to the singleton instance.
 */
public record ToggleEnemyGlowPayload() implements CustomPayload {

	public static final CustomPayload.Id<ToggleEnemyGlowPayload> ID =
		new CustomPayload.Id<>(Identifier.of(TowerDefenseMod.MOD_ID, "toggle_enemy_glow"));

	/** Zero-byte codec: no fields to (de)serialize, always yields a fresh empty record. */
	public static final PacketCodec<RegistryByteBuf, ToggleEnemyGlowPayload> CODEC =
		PacketCodec.unit(new ToggleEnemyGlowPayload());

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
