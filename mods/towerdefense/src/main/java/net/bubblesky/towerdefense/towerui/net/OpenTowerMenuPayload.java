package net.bubblesky.towerdefense.towerui.net;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C: open (or refresh) the per-tower context menu for the tower at {@code posId}.
 *
 * <p>Sent when a player PUNCHES / starts mining a tower block (see the {@code AttackBlockCallback}
 * in {@code TowerUiEvents}), and again after an in-menu Upgrade so the screen shows the new stats.
 * Carries a full snapshot of the tower's display stats so the client screen renders without any
 * further server round-trips:
 *
 * <ul>
 *   <li>{@code kindOrdinal} — {@link net.bubblesky.towerdefense.tower.TowerKind} ordinal (name/id
 *       is derived client-side).</li>
 *   <li>{@code tier}/{@code maxTier} — upgrade level and its cap.</li>
 *   <li>{@code kills}/{@code veterancy}/{@code maxVeterancy}/{@code killsToNext} — veterancy rank
 *       progress.</li>
 *   <li>{@code dmgPct} — damage multiplier x100 (display as {@code dmgPct/100.0}).</li>
 *   <li>{@code range} — search/fire radius in blocks (rounded).</li>
 *   <li>{@code cooldownTicks} — ticks between shots (display as fire rate).</li>
 *   <li>{@code invested} — coins sunk into this tower.</li>
 *   <li>{@code sellRefund} — coins returned on Sell.</li>
 *   <li>{@code upgradeCost} — coins to reach the next tier, or {@code -1} if maxed.</li>
 *   <li>{@code isOwner} — whether the RECEIVING player placed this tower (gates the action buttons).</li>
 *   <li>{@code ownerName} — the placer's display name (shown to non-owners).</li>
 * </ul>
 */
public record OpenTowerMenuPayload(
		long posId,
		int kindOrdinal,
		int tier,
		int maxTier,
		int kills,
		int veterancy,
		int maxVeterancy,
		int killsToNext,
		int dmgPct,
		int range,
		int cooldownTicks,
		int invested,
		int sellRefund,
		int upgradeCost,
		boolean isOwner,
		String ownerName) implements CustomPayload {

	public static final CustomPayload.Id<OpenTowerMenuPayload> ID =
		new CustomPayload.Id<>(Identifier.of(TowerDefenseMod.MOD_ID, "open_tower_menu"));

	public static final PacketCodec<RegistryByteBuf, OpenTowerMenuPayload> CODEC =
		PacketCodec.of(OpenTowerMenuPayload::write, OpenTowerMenuPayload::read);

	private void write(RegistryByteBuf buf) {
		buf.writeLong(posId);
		buf.writeVarInt(kindOrdinal);
		buf.writeVarInt(tier);
		buf.writeVarInt(maxTier);
		buf.writeVarInt(kills);
		buf.writeVarInt(veterancy);
		buf.writeVarInt(maxVeterancy);
		buf.writeVarInt(killsToNext);
		buf.writeVarInt(dmgPct);
		buf.writeVarInt(range);
		buf.writeVarInt(cooldownTicks);
		buf.writeVarInt(invested);
		buf.writeVarInt(sellRefund);
		// upgradeCost may be -1 (maxed), so it is NOT a VarInt (which is unsigned-friendly but
		// wasteful for negatives) — a plain int keeps the sentinel cheap and unambiguous.
		buf.writeInt(upgradeCost);
		buf.writeBoolean(isOwner);
		buf.writeString(ownerName);
	}

	private static OpenTowerMenuPayload read(RegistryByteBuf buf) {
		return new OpenTowerMenuPayload(
			buf.readLong(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readInt(),
			buf.readBoolean(),
			buf.readString());
	}

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
