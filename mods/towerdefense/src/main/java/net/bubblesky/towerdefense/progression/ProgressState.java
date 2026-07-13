package net.bubblesky.towerdefense.progression;

import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

/**
 * World-saved store of every player's {@link PlayerProgress}, keyed by player UUID.
 * Progression is PERMANENT, so this is the durable home for it: like
 * {@link net.bubblesky.towerdefense.state.TdArenaState} it lives in the OVERWORLD's
 * persistent-state manager (a single global blob, not per-dimension) and survives
 * server restarts.
 *
 * <p>1.21.6 note: saved data is Codec-based via {@link PersistentStateType}. Rather
 * than hand-write a record codec, the map round-trips through an {@link NbtCompound}
 * (one sub-tag per player, named by UUID string) with {@code NbtCompound.CODEC.xmap}.
 * The persistent key is {@code towerdefense_progress}.
 */
public class ProgressState extends PersistentState {

	/** Per-player progression, keyed by UUID. */
	private final Map<UUID, PlayerProgress> byPlayer = new HashMap<>();

	private static final Codec<ProgressState> CODEC =
		NbtCompound.CODEC.xmap(ProgressState::fromNbt, ProgressState::toNbt);

	/** The saved-data type registered with the persistent state manager. */
	public static final PersistentStateType<ProgressState> TYPE =
		new PersistentStateType<>("towerdefense_progress", ProgressState::new, CODEC, DataFixTypes.LEVEL);

	public ProgressState() {
	}

	/** Fetch (or create) the shared progression store, always kept in the overworld. */
	public static ProgressState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
	}

	/**
	 * Get-or-create the progression record for a player UUID. Creating a fresh record
	 * marks the store dirty (so a brand-new player is saved); a plain read of an
	 * existing record does not, keeping saves proportional to real change.
	 */
	public PlayerProgress forPlayer(UUID uuid) {
		return byPlayer.computeIfAbsent(uuid, k -> {
			markDirty();
			return new PlayerProgress();
		});
	}

	// ---- serialization -----------------------------------------------------
	private NbtCompound toNbt() {
		NbtCompound nbt = new NbtCompound();
		NbtCompound players = new NbtCompound();
		for (Map.Entry<UUID, PlayerProgress> e : byPlayer.entrySet()) {
			players.put(e.getKey().toString(), e.getValue().writeNbt());
		}
		nbt.put("players", players);
		return nbt;
	}

	private static ProgressState fromNbt(NbtCompound nbt) {
		ProgressState state = new ProgressState();
		NbtCompound players = nbt.getCompoundOrEmpty("players");
		for (String key : players.getKeys()) {
			try {
				UUID uuid = UUID.fromString(key);
				state.byPlayer.put(uuid, PlayerProgress.readNbt(players.getCompoundOrEmpty(key)));
			} catch (IllegalArgumentException ignored) {
				// Skip an unparseable UUID key rather than fail the whole load.
			}
		}
		return state;
	}
}
