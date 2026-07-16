package net.bubblesky.towerdefense.towerui.net;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: act on the tower at {@code posId}.
 * action: 0=upgrade, 1=sell, 2=recycle (return the block item), 3=destroy (teardown, no return),
 * -1 (or any other value)=just resync the roster.
 */
public record TowerActionPayload(long posId, int action) implements CustomPayload {
	public static final int ACTION_UPGRADE = 0;
	public static final int ACTION_SELL = 1;
	/** Owner-only: tear the tower down and hand the placeable block item back (no coin refund). */
	public static final int ACTION_RECYCLE = 2;
	/** Owner-only: tear the tower down with no refund and no item returned. */
	public static final int ACTION_DESTROY = 3;
	public static final int ACTION_REFRESH = -1;

	public static final CustomPayload.Id<TowerActionPayload> ID =
		new CustomPayload.Id<>(Identifier.of(TowerDefenseMod.MOD_ID, "tower_action"));

	public static final PacketCodec<RegistryByteBuf, TowerActionPayload> CODEC = PacketCodec.tuple(
		PacketCodecs.VAR_LONG, TowerActionPayload::posId,
		PacketCodecs.VAR_INT, TowerActionPayload::action,
		TowerActionPayload::new);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
