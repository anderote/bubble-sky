package net.bubblesky.towerdefense.construction.net;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.bubblesky.towerdefense.construction.ConstructionConfig;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** C2S: confirm the currently previewed, server-clamped construction spell. */
public record ConfirmConstructionPayload(ConstructionConfig config) implements CustomPayload {
	public static final CustomPayload.Id<ConfirmConstructionPayload> ID =
		new CustomPayload.Id<>(Identifier.of(TowerDefenseMod.MOD_ID, "confirm_construction"));
	public static final PacketCodec<RegistryByteBuf, ConfirmConstructionPayload> CODEC =
		PacketCodec.of(ConfirmConstructionPayload::write, ConfirmConstructionPayload::read);

	private void write(RegistryByteBuf buf) {
		ConstructionConfig c = config.normalized();
		buf.writeVarInt(c.typeOrdinal());
		buf.writeVarInt(c.materialOrdinal());
		buf.writeVarInt(c.replaceOrdinal());
		buf.writeVarInt(c.width());
		buf.writeVarInt(c.length());
		buf.writeVarInt(c.height());
		buf.writeVarInt(c.thickness());
		buf.writeVarInt(c.forwardOffset());
		buf.writeVarInt(c.verticalOffset());
		buf.writeVarInt(c.auxiliary());
		buf.writeVarInt(c.fillDepth());
		buf.writeBoolean(c.decorated());
		buf.writeBoolean(c.consumeMaterials());
	}

	private static ConfirmConstructionPayload read(RegistryByteBuf buf) {
		return new ConfirmConstructionPayload(new ConstructionConfig(
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), buf.readBoolean(), buf.readBoolean()).normalized());
	}

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
