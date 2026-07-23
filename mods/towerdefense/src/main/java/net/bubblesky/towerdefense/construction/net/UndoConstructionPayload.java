package net.bubblesky.towerdefense.construction.net;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** C2S signal: restore the player's most recently completed construction spell. */
public record UndoConstructionPayload() implements CustomPayload {
	public static final CustomPayload.Id<UndoConstructionPayload> ID =
		new CustomPayload.Id<>(Identifier.of(TowerDefenseMod.MOD_ID, "undo_construction"));
	public static final PacketCodec<RegistryByteBuf, UndoConstructionPayload> CODEC =
		PacketCodec.unit(new UndoConstructionPayload());

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
