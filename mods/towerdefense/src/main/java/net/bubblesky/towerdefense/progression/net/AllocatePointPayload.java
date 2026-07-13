package net.bubblesky.towerdefense.progression.net;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.bubblesky.towerdefense.progression.PlayerProgress.Stat;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: "spend one skill point on this stat". Sent by the Character screen's per-stat
 * {@code +} button. Carries only the {@link Stat} ordinal; the server is authoritative
 * — it validates the player actually has an unspent point, allocates, re-applies
 * {@link net.bubblesky.towerdefense.progression.StatModifiers}, saves, and resyncs.
 */
public record AllocatePointPayload(int statOrdinal) implements CustomPayload {

	public static final CustomPayload.Id<AllocatePointPayload> ID =
		new CustomPayload.Id<>(Identifier.of(TowerDefenseMod.MOD_ID, "allocate_point"));

	/** Single VAR_INT (the stat ordinal). */
	public static final PacketCodec<RegistryByteBuf, AllocatePointPayload> CODEC =
		PacketCodec.tuple(PacketCodecs.VAR_INT, AllocatePointPayload::statOrdinal, AllocatePointPayload::new);

	public AllocatePointPayload(Stat stat) {
		this(stat.ordinal());
	}

	/** Resolve the ordinal back to a {@link Stat}, clamped so a bad packet can't crash. */
	public Stat stat() {
		Stat[] values = Stat.values();
		return values[Math.floorMod(statOrdinal, values.length)];
	}

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
