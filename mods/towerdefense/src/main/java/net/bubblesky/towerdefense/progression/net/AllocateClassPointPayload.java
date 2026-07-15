package net.bubblesky.towerdefense.progression.net;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: "spend one CLASS point on this skill". Sent by the Character screen's Skills-tab
 * {@code +} buttons — the per-class cousin of {@link AllocatePointPayload} (which spends the
 * GLOBAL 7-stat points). Carries only the target skill {@code id}; the server is authoritative
 * and validates everything (active class present, unspent class points, the skill exists in that
 * class's {@link net.bubblesky.towerdefense.progression.ClassSkillTree tree}, current rank below
 * its max, and the class level meets the tier gate) before allocating, re-applying
 * {@link net.bubblesky.towerdefense.progression.StatModifiers}, saving, and resyncing.
 */
public record AllocateClassPointPayload(String skillId) implements CustomPayload {

	public static final CustomPayload.Id<AllocateClassPointPayload> ID =
		new CustomPayload.Id<>(Identifier.of(TowerDefenseMod.MOD_ID, "allocate_class_point"));

	/** Single string (the skill id). */
	public static final PacketCodec<RegistryByteBuf, AllocateClassPointPayload> CODEC =
		PacketCodec.tuple(PacketCodecs.STRING, AllocateClassPointPayload::skillId, AllocateClassPointPayload::new);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
