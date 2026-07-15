package net.bubblesky.towerdefense.progression.net;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: "make this my active class". Carries the class {@link #classId} string (matching
 * {@link net.bubblesky.towerdefense.progression.PlayerClass#id()}). The server is
 * authoritative — it validates the id, sets the active class, grants the loadout, re-applies
 * {@link net.bubblesky.towerdefense.progression.StatModifiers}, and resyncs (exactly what
 * {@code /td class <name>} does). A dedicated class-pick SCREEN is a later phase; this
 * payload + the chat command are the Phase-1 selection paths.
 */
public record SelectClassPayload(String classId) implements CustomPayload {

	public static final CustomPayload.Id<SelectClassPayload> ID =
		new CustomPayload.Id<>(Identifier.of(TowerDefenseMod.MOD_ID, "select_class"));

	/** Single string (the class id). */
	public static final PacketCodec<RegistryByteBuf, SelectClassPayload> CODEC =
		PacketCodec.tuple(PacketCodecs.STRING, SelectClassPayload::classId, SelectClassPayload::new);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
