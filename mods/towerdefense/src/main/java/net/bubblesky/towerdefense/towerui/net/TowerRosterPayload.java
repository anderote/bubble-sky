package net.bubblesky.towerdefense.towerui.net;

import java.util.ArrayList;
import java.util.List;
import net.bubblesky.towerdefense.TowerDefenseMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** S2C: a player's owned-tower roster snapshot, pushed on open and after each action. */
public record TowerRosterPayload(int coins, List<Row> rows) implements CustomPayload {

	public record Row(long posId, int kindOrdinal, int tier, int range, int cooldownTicks,
		int dmgPct, int upgradeCost, int refund, boolean maxed) {
	}

	public static final CustomPayload.Id<TowerRosterPayload> ID =
		new CustomPayload.Id<>(Identifier.of(TowerDefenseMod.MOD_ID, "tower_roster"));

	public static final PacketCodec<RegistryByteBuf, TowerRosterPayload> CODEC =
		PacketCodec.of(TowerRosterPayload::write, TowerRosterPayload::read);

	private void write(RegistryByteBuf buf) {
		buf.writeVarInt(coins);
		buf.writeVarInt(rows.size());
		for (Row r : rows) {
			buf.writeLong(r.posId());
			buf.writeVarInt(r.kindOrdinal());
			buf.writeVarInt(r.tier());
			buf.writeVarInt(r.range());
			buf.writeVarInt(r.cooldownTicks());
			buf.writeVarInt(r.dmgPct());
			buf.writeVarInt(r.upgradeCost());
			buf.writeVarInt(r.refund());
			buf.writeBoolean(r.maxed());
		}
	}

	private static TowerRosterPayload read(RegistryByteBuf buf) {
		int coins = buf.readVarInt();
		int n = buf.readVarInt();
		List<Row> rows = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			rows.add(new Row(buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
				buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));
		}
		return new TowerRosterPayload(coins, rows);
	}

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
