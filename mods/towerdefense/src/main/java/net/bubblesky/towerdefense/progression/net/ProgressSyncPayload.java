package net.bubblesky.towerdefense.progression.net;

import java.util.LinkedHashMap;
import java.util.Map;
import net.bubblesky.towerdefense.TowerDefenseMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C: a snapshot of one player's progression, pushed on join/respawn/world-change and
 * after every allocation or class change. The client caches it (for the HUD + Character
 * screen). The {@code allocations} array is indexed by
 * {@link net.bubblesky.towerdefense.progression.PlayerProgress.Stat} ordinal.
 *
 * <p>{@code xp} is progress within the CURRENT global level; the client derives the level
 * threshold locally via {@code PlayerProgress.xpForLevel(level)} (a pure function), so the
 * wire stays compact. {@code gold} is the player's gold-bank balance.
 *
 * <p>The trailing fields carry the CLASS layer: current {@code mana}/{@code maxMana}, the
 * active class id ({@code ""} when unpicked), and — for the active class — its own
 * {@code classLevel}, {@code classXp} (within-level), and unspent {@code classPoints}. All
 * zero/empty when the player has no active class. These feed the mana bar + class readouts
 * added in later phases; appended after the original fields so the codec stays additive.
 *
 * <p>The final {@code classAllocations} map carries the ACTIVE class's per-skill ranks (skill
 * id → rank), so the Character screen's Skills tab can render each row's current rank without a
 * second round-trip. Empty when unclassed; appended last, so the codec stays additive.
 */
public record ProgressSyncPayload(int xp, int level, int unspentPoints, int gold, int[] allocations,
		int mana, int maxMana, String activeClass, int classLevel, int classXp, int classPoints,
		Map<String, Integer> classAllocations)
		implements CustomPayload {

	public static final CustomPayload.Id<ProgressSyncPayload> ID =
		new CustomPayload.Id<>(Identifier.of(TowerDefenseMod.MOD_ID, "progress_sync"));

	/** Hand-rolled codec (a length-prefixed int array won't fit the tuple helpers cleanly). */
	public static final PacketCodec<RegistryByteBuf, ProgressSyncPayload> CODEC =
		PacketCodec.of(ProgressSyncPayload::write, ProgressSyncPayload::read);

	private void write(RegistryByteBuf buf) {
		buf.writeVarInt(xp);
		buf.writeVarInt(level);
		buf.writeVarInt(unspentPoints);
		buf.writeVarInt(gold);
		buf.writeVarInt(allocations.length);
		for (int a : allocations) {
			buf.writeVarInt(a);
		}
		// ---- class layer (appended) ----
		buf.writeVarInt(mana);
		buf.writeVarInt(maxMana);
		buf.writeString(activeClass == null ? "" : activeClass);
		buf.writeVarInt(classLevel);
		buf.writeVarInt(classXp);
		buf.writeVarInt(classPoints);
		// ---- active class's per-skill ranks (id -> rank), appended last ----
		Map<String, Integer> alloc = classAllocations == null ? Map.of() : classAllocations;
		buf.writeVarInt(alloc.size());
		for (Map.Entry<String, Integer> e : alloc.entrySet()) {
			buf.writeString(e.getKey());
			buf.writeVarInt(e.getValue());
		}
	}

	private static ProgressSyncPayload read(RegistryByteBuf buf) {
		int xp = buf.readVarInt();
		int level = buf.readVarInt();
		int points = buf.readVarInt();
		int gold = buf.readVarInt();
		int n = buf.readVarInt();
		int[] alloc = new int[n];
		for (int i = 0; i < n; i++) {
			alloc[i] = buf.readVarInt();
		}
		int mana = buf.readVarInt();
		int maxMana = buf.readVarInt();
		String activeClass = buf.readString();
		int classLevel = buf.readVarInt();
		int classXp = buf.readVarInt();
		int classPoints = buf.readVarInt();
		int allocN = buf.readVarInt();
		Map<String, Integer> classAllocations = new LinkedHashMap<>();
		for (int i = 0; i < allocN; i++) {
			String key = buf.readString();
			int rank = buf.readVarInt();
			classAllocations.put(key, rank);
		}
		return new ProgressSyncPayload(xp, level, points, gold, alloc,
			mana, maxMana, activeClass, classLevel, classXp, classPoints, classAllocations);
	}

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
