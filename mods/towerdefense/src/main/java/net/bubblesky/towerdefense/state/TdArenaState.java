package net.bubblesky.towerdefense.state;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;
import org.jetbrains.annotations.Nullable;

/**
 * World-saved arena state for the tower-defense game loop. This is the single
 * source of truth for the match: spawn points, the base being defended, the
 * current wave, and the wave state-machine's phase/counters. Persisting the
 * whole machine (not just config) means an in-progress match survives a
 * server reload.
 *
 * <p>1.21.6 note: saved data is now Codec-based via {@link PersistentStateType}.
 * Rather than hand-write a 12-field {@code RecordCodecBuilder}, we round-trip
 * through an {@link NbtCompound} with {@code NbtCompound.CODEC.xmap(...)} — this
 * keeps the read/write logic in plain, readable NBT calls. State always lives in
 * the overworld's {@code PersistentStateManager}; the arena's actual dimension is
 * stored in {@link #worldId} so enemies/base can live in any world.
 */
public class TdArenaState extends PersistentState {
	/** Wave state machine phases. */
	public enum Phase { IDLE, SPAWNING, ACTIVE, INTERMISSION }

	/** Default base hit points when a base is placed. */
	public static final int DEFAULT_BASE_HP = 100;

	// ---- persistent fields -------------------------------------------------
	/** Enemy spawn points (block positions). */
	public final List<BlockPos> spawnPoints = new ArrayList<>();
	/** The base to defend. {@code null} until {@code /td base} is run. */
	@Nullable
	public BlockPos base = null;
	/** Dimension id of the arena (e.g. {@code minecraft:overworld}). */
	public String worldId = "";
	public int baseHp = 0;
	public int baseMaxHp = DEFAULT_BASE_HP;
	/** Current (last-started) wave number; 0 = no wave yet. */
	public int currentWave = 0;
	public Phase phase = Phase.IDLE;
	/** Enemies still queued to spawn in the current wave. */
	public int enemiesRemaining = 0;
	/** Ticks until the next staggered spawn. */
	public int spawnCooldown = 0;
	/** Ticks left in the between-waves intermission. */
	public int intermissionCooldown = 0;
	/** Highest wave fully cleared / reached (for the game-over report). */
	public int wavesSurvived = 0;
	/** Set when the base is destroyed; halts spawning until reset. */
	public boolean gameOver = false;

	private static final Codec<TdArenaState> CODEC =
		NbtCompound.CODEC.xmap(TdArenaState::fromNbt, TdArenaState::toNbt);

	/** The saved-data type registered with the persistent state manager. */
	public static final PersistentStateType<TdArenaState> TYPE =
		new PersistentStateType<>("towerdefense_arena", TdArenaState::new, CODEC, DataFixTypes.LEVEL);

	public TdArenaState() {
	}

	/** Fetch (or create) the shared arena state, always kept in the overworld. */
	public static TdArenaState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
	}

	/** Resolve the arena's dimension, or {@code null} if unset/unknown. */
	@Nullable
	public ServerWorld getArenaWorld(MinecraftServer server) {
		if (worldId.isEmpty()) {
			return null;
		}
		Identifier id = Identifier.tryParse(worldId);
		if (id == null) {
			return null;
		}
		return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
	}

	/** True once a base exists and the match hasn't been lost. */
	public boolean isActiveArena() {
		return base != null && !gameOver;
	}

	/** Wipe everything back to a blank arena. */
	public void clear() {
		spawnPoints.clear();
		base = null;
		worldId = "";
		baseHp = 0;
		baseMaxHp = DEFAULT_BASE_HP;
		currentWave = 0;
		phase = Phase.IDLE;
		enemiesRemaining = 0;
		spawnCooldown = 0;
		intermissionCooldown = 0;
		wavesSurvived = 0;
		gameOver = false;
		markDirty();
	}

	// ---- serialization -----------------------------------------------------
	private NbtCompound toNbt() {
		NbtCompound nbt = new NbtCompound();
		long[] spawns = new long[spawnPoints.size()];
		for (int i = 0; i < spawns.length; i++) {
			spawns[i] = spawnPoints.get(i).asLong();
		}
		nbt.putLongArray("spawns", spawns);
		if (base != null) {
			nbt.putLong("base", base.asLong());
		}
		nbt.putString("worldId", worldId);
		nbt.putInt("baseHp", baseHp);
		nbt.putInt("baseMaxHp", baseMaxHp);
		nbt.putInt("wave", currentWave);
		nbt.putString("phase", phase.name());
		nbt.putInt("enemiesRemaining", enemiesRemaining);
		nbt.putInt("spawnCooldown", spawnCooldown);
		nbt.putInt("intermissionCooldown", intermissionCooldown);
		nbt.putInt("wavesSurvived", wavesSurvived);
		nbt.putBoolean("gameOver", gameOver);
		return nbt;
	}

	private static TdArenaState fromNbt(NbtCompound nbt) {
		TdArenaState s = new TdArenaState();
		for (long packed : nbt.getLongArray("spawns").orElse(new long[0])) {
			s.spawnPoints.add(BlockPos.fromLong(packed));
		}
		if (nbt.contains("base")) {
			s.base = BlockPos.fromLong(nbt.getLong("base", 0L));
		}
		s.worldId = nbt.getString("worldId", "");
		s.baseHp = nbt.getInt("baseHp", 0);
		s.baseMaxHp = nbt.getInt("baseMaxHp", DEFAULT_BASE_HP);
		s.currentWave = nbt.getInt("wave", 0);
		s.phase = parsePhase(nbt.getString("phase", "IDLE"));
		s.enemiesRemaining = nbt.getInt("enemiesRemaining", 0);
		s.spawnCooldown = nbt.getInt("spawnCooldown", 0);
		s.intermissionCooldown = nbt.getInt("intermissionCooldown", 0);
		s.wavesSurvived = nbt.getInt("wavesSurvived", 0);
		s.gameOver = nbt.getBoolean("gameOver", false);
		return s;
	}

	private static Phase parsePhase(String name) {
		try {
			return Phase.valueOf(name);
		} catch (IllegalArgumentException e) {
			return Phase.IDLE;
		}
	}
}
