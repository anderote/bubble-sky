package net.bubblesky.towerdefense.progression.net;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C: a snapshot of one player's progression, pushed on join/respawn/world-change and
 * after every allocation. The client caches it (for the HUD + Character screen). The
 * {@code allocations} array is indexed by {@link net.bubblesky.towerdefense.progression.PlayerProgress.Stat}
 * ordinal.
 *
 * <p>{@code xp} is progress within the CURRENT level; the client derives the level
 * threshold locally via {@code PlayerProgress.xpForLevel(level)} (a pure function), so
 * the wire stays compact.
 */
public record ProgressSyncPayload(int xp, int level, int unspentPoints, int[] allocations) implements CustomPayload {

	public static final CustomPayload.Id<ProgressSyncPayload> ID =
		new CustomPayload.Id<>(Identifier.of(TowerDefenseMod.MOD_ID, "progress_sync"));

	/** Hand-rolled codec (a length-prefixed int array won't fit the tuple helpers cleanly). */
	public static final PacketCodec<RegistryByteBuf, ProgressSyncPayload> CODEC =
		PacketCodec.of(ProgressSyncPayload::write, ProgressSyncPayload::read);

	private void write(RegistryByteBuf buf) {
		buf.writeVarInt(xp);
		buf.writeVarInt(level);
		buf.writeVarInt(unspentPoints);
		buf.writeVarInt(allocations.length);
		for (int a : allocations) {
			buf.writeVarInt(a);
		}
	}

	private static ProgressSyncPayload read(RegistryByteBuf buf) {
		int xp = buf.readVarInt();
		int level = buf.readVarInt();
		int points = buf.readVarInt();
		int n = buf.readVarInt();
		int[] alloc = new int[n];
		for (int i = 0; i < n; i++) {
			alloc[i] = buf.readVarInt();
		}
		return new ProgressSyncPayload(xp, level, points, alloc);
	}

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
