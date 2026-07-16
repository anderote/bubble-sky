package net.bubblesky.towerdefense.game;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Infinite-magazine system for Guns++ firearms: every gun in a player's inventory is kept
 * perpetually topped up to its full magazine, so no ammo item and no reload cycle is ever
 * needed. This is the server-side half of the mod's "weapons just work" policy — the TD bow
 * is already unlimited by construction (see {@code item/TdBowItem}); this covers the guns.
 *
 * <p><b>How Guns++ stores a magazine.</b> Guns++ guns are vanilla {@code carrot_on_a_stick}
 * stacks carrying a {@link DataComponentTypes#CUSTOM_DATA} component whose NBT holds a
 * {@code gz_data} sub-compound (see {@link GunsPlusPlus#glock()} et al.). Inside {@code gz_data}
 * the two magazine bytes are:
 * <ul>
 *   <li>{@code capacity} — the magazine size (byte), e.g. 17 for a Glock.</li>
 *   <li>{@code bullets} — the currently loaded rounds (byte), which Guns++ decrements on fire
 *       and refills from {@code clock} ammo items on reload. Guns start at {@code 0}.</li>
 * </ul>
 * By rewriting {@code gz_data.bullets = gz_data.capacity} whenever it drops below full, every
 * gun stays loaded forever and the reload path never engages.
 *
 * <p><b>Cadence.</b> The sweep runs on {@link ServerTickEvents#END_SERVER_TICK} but only acts
 * once every {@link #REFILL_INTERVAL_TICKS} ticks. Refilling every tick would fight Guns++'s
 * own reload animation (which counts {@code bullets} up over its {@code reload_time}); topping
 * up a few times a second is invisible to the player yet guarantees a full magazine before the
 * next shot. The write only happens when {@code bullets < capacity}, so a full gun costs nothing.
 */
public final class InfiniteAmmo {
	/** Base items a Guns++ gun can be. Guns++ SWAPS a gun between {@code carrot_on_a_stick} and
	 *  {@code warped_fungus_on_a_stick} while it is being fired (it detects successive clicks via
	 *  two separate {@code minecraft.used:*} scoreboard criteria). We MUST top up both forms — if
	 *  we only refilled the carrot form the magazine would drain to 0 mid-fire ("out of ammo"). */
	private static boolean isGunBase(ItemStack stack) {
		return stack.isOf(Items.CARROT_ON_A_STICK) || stack.isOf(Items.WARPED_FUNGUS_ON_A_STICK);
	}
	/** Custom-data sub-compound key Guns++ stores gun state under. */
	private static final String GZ_DATA_KEY = "gz_data";
	/** Magazine-size byte inside {@link #GZ_DATA_KEY}. */
	private static final String CAPACITY_KEY = "capacity";
	/** Currently-loaded-rounds byte inside {@link #GZ_DATA_KEY}. */
	private static final String BULLETS_KEY = "bullets";

	/** Only refill every N server ticks (20 ticks = 1s), to stay clear of reload animations. */
	private static final int REFILL_INTERVAL_TICKS = 5;

	/** Rolling tick counter for the interval gate. */
	private static int tick;

	private InfiniteAmmo() {
	}

	/** Register the periodic magazine-refill sweep. Call once from mod init. */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++tick % REFILL_INTERVAL_TICKS != 0) {
				return;
			}
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				refillPlayerGuns(player);
			}
		});
	}

	/** Top every Guns++ gun in this player's inventory back to a full magazine. */
	private static void refillPlayerGuns(ServerPlayerEntity player) {
		var inventory = player.getInventory();
		int size = inventory.size();
		for (int slot = 0; slot < size; slot++) {
			topUpMagazine(inventory.getStack(slot));
		}
	}

	/**
	 * If {@code stack} is a Guns++ gun below full, rewrite its {@code gz_data.bullets} up to
	 * {@code gz_data.capacity}, preserving every other field. No-op for empty stacks, non-gun
	 * items, plain {@code carrot_on_a_stick}s, and already-full guns.
	 */
	private static void topUpMagazine(ItemStack stack) {
		if (stack.isEmpty() || !isGunBase(stack)) {
			return;
		}
		NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (data == null) {
			return;
		}
		NbtCompound custom = data.copyNbt();
		if (!custom.contains(GZ_DATA_KEY)) {
			return; // not a Guns++ stack
		}
		NbtCompound gz = custom.getCompoundOrEmpty(GZ_DATA_KEY);
		if (!gz.contains(CAPACITY_KEY)) {
			return; // ammo clocks and other gz stacks have no capacity — not a gun
		}
		byte capacity = gz.getByte(CAPACITY_KEY, (byte) 0);
		byte bullets = gz.getByte(BULLETS_KEY, (byte) 0);
		if (bullets >= capacity) {
			return; // already full
		}
		gz.putByte(BULLETS_KEY, capacity);
		custom.put(GZ_DATA_KEY, gz);
		stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(custom));
	}
}
