package net.bubblesky.towerdefense.colony;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;
import org.jetbrains.annotations.Nullable;

/**
 * World-saved registry of COLONY HOME FLAGS — the anchors {@code /colony flag} plants.
 * Each flag is a named position (with its dimension) that colonists bind to and range
 * around; the mod discovers chests/work near the flag. v1 assumes one active colony
 * (colonists bind to the nearest flag), but the store already holds many so multiple
 * colonies are a later, additive step.
 *
 * <p>Persisted, like {@link net.bubblesky.towerdefense.state.TdArenaState}, by
 * round-tripping through an {@link NbtCompound} with a {@code CODEC.xmap} rather than a
 * hand-written record codec, and always kept in the overworld's state manager.
 */
public class ColonyState extends PersistentState {
	/** A single planted colony flag: a name + position + dimension id. */
	public record Flag(String name, BlockPos pos, String dim) {
	}

	/** Every planted colony flag (insertion order = creation order). */
	public final List<Flag> flags = new ArrayList<>();

	private static final Codec<ColonyState> CODEC =
		NbtCompound.CODEC.xmap(ColonyState::fromNbt, ColonyState::toNbt);

	public static final PersistentStateType<ColonyState> TYPE =
		new PersistentStateType<>("towerdefense_colony", ColonyState::new, CODEC, DataFixTypes.LEVEL);

	public ColonyState() {
	}

	/** Fetch (or create) the shared colony state, always kept in the overworld. */
	public static ColonyState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
	}

	/** Add a flag (auto-named {@code Colony N}) and mark the store dirty. */
	public Flag addFlag(BlockPos pos, String dim) {
		Flag flag = new Flag("Colony " + (flags.size() + 1), pos.toImmutable(), dim);
		flags.add(flag);
		markDirty();
		return flag;
	}

	/** The colony flag nearest {@code pos} in dimension {@code dim}, or null if none. */
	@Nullable
	public Flag nearestFlag(BlockPos pos, String dim) {
		Flag best = null;
		double bestSq = Double.MAX_VALUE;
		for (Flag f : flags) {
			if (!f.dim().equals(dim)) {
				continue;
			}
			double sq = f.pos().getSquaredDistance(pos);
			if (sq < bestSq) {
				bestSq = sq;
				best = f;
			}
		}
		return best;
	}

	// ---- serialization -----------------------------------------------------
	private NbtCompound toNbt() {
		NbtCompound nbt = new NbtCompound();
		NbtList list = new NbtList();
		for (Flag f : flags) {
			NbtCompound c = new NbtCompound();
			c.putString("name", f.name());
			c.putLong("pos", f.pos().asLong());
			c.putString("dim", f.dim());
			list.add(c);
		}
		nbt.put("flags", list);
		return nbt;
	}

	private static ColonyState fromNbt(NbtCompound nbt) {
		ColonyState s = new ColonyState();
		NbtList list = nbt.getListOrEmpty("flags");
		for (int i = 0; i < list.size(); i++) {
			NbtCompound c = list.getCompoundOrEmpty(i);
			s.flags.add(new Flag(
				c.getString("name", "Colony"),
				BlockPos.fromLong(c.getLong("pos", 0L)),
				c.getString("dim", "minecraft:overworld")));
		}
		return s;
	}
}
