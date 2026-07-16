package net.bubblesky.towerdefense.game;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Infinite-magazine system for Guns++ firearms: every gun in a player's inventory is kept
 * perpetually loaded, fire-ready, and topped up to its full magazine, so no ammo item and no
 * reload cycle is ever needed. This is the server-side half of the mod's "weapons just work"
 * policy — the TD bow is already unlimited by construction (see {@code item/TdBowItem}); this
 * covers the guns.
 *
 * <p><b>What makes a Guns++ gun fire.</b> A Guns++ gun is a vanilla stick item whose base type
 * encodes its loaded state (see {@link GunsPlusPlus#gun}): {@code carrot_on_a_stick} while empty,
 * {@code warped_fungus_on_a_stick} while loaded. Firing is driven entirely by the datapack's
 * per-tick loop ({@code data/thepa/function/loop.mcfunction}); a shot happens for a player only
 * when <em>both</em> of these hold at trigger time:
 * <ul>
 *   <li>the held gun is a {@code warped_fungus_on_a_stick} — right-clicking it ticks the
 *       {@code gz_crossbow} statistic ({@code minecraft.used:warped_fungus_on_a_stick}), the score
 *       the loop gates its whole shoot chain on. A {@code carrot_on_a_stick} instead ticks
 *       {@code gz_click} and merely dry-clicks; and</li>
 *   <li>the player's {@code gz_bullets} scoreboard is {@code >=1}. Vanilla Guns++ seeds that score
 *       during the (sneak-triggered) reload; since we never run the reload path, we seed it
 *       ourselves — otherwise the first click is silently swallowed (and a capacity-1 sniper, whose
 *       score drops to 0 after its single shot, would never re-fire).</li>
 * </ul>
 *
 * <p><b>What this sweep does each pass.</b> For every Guns++ gun in a player's inventory it:
 * <ol>
 *   <li>rewrites {@code gz_data.bullets = gz_data.capacity} whenever the magazine is below full;</li>
 *   <li>converts the gun back to the loaded {@code warped_fungus_on_a_stick} form if Guns++ swapped
 *       it to {@code carrot_on_a_stick} (which it does the instant a magazine empties — e.g. a
 *       capacity-1 sniper after one shot), so it stays fire-able rather than dry-clicking; and</li>
 *   <li>sets the holder's {@code gz_bullets} score to the selected gun's capacity, so the very next
 *       right-click fires.</li>
 * </ol>
 * Because Guns++ resets {@code gz_crossbow} to 0 every tick (loop tail), seeding {@code gz_bullets}
 * high does NOT cause runaway fire — the trigger still runs at most once per actual right-click.
 *
 * <p><b>Cadence.</b> The sweep runs on {@link ServerTickEvents#END_SERVER_TICK} but only acts once
 * every {@link #REFILL_INTERVAL_TICKS} ticks. Refilling every tick would fight Guns++'s own reload
 * animation (which counts {@code bullets} up over its {@code reload_time}); topping up a few times a
 * second is invisible to the player yet guarantees a full, fire-ready magazine before the next shot.
 */
public final class InfiniteAmmo {
	/** Base items a Guns++ gun can be: {@code carrot_on_a_stick} (unloaded) or
	 *  {@code warped_fungus_on_a_stick} (loaded/fire-ready). We recognise and refill both forms. */
	private static boolean isGunBase(ItemStack stack) {
		return stack.isOf(Items.CARROT_ON_A_STICK) || stack.isOf(Items.WARPED_FUNGUS_ON_A_STICK);
	}
	/** Custom-data sub-compound key Guns++ stores gun state under. */
	private static final String GZ_DATA_KEY = "gz_data";
	/** Magazine-size byte inside {@link #GZ_DATA_KEY}. */
	private static final String CAPACITY_KEY = "capacity";
	/** Currently-loaded-rounds byte inside {@link #GZ_DATA_KEY}. */
	private static final String BULLETS_KEY = "bullets";
	/** Guns++'s per-player "rounds in the chamber" scoreboard objective (a {@code dummy} objective
	 *  created by the datapack's {@code setup} function). The fire trigger requires it to be
	 *  {@code >=1}; we seed it to keep the selected gun ready to shoot. */
	private static final String GZ_BULLETS_OBJECTIVE = "gz_bullets";

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
			ServerScoreboard scoreboard = server.getScoreboard();
			// null until the Guns++ datapack's setup function has created the objective; if the
			// datapack isn't loaded there is nothing to fire anyway, so we simply skip seeding.
			ScoreboardObjective bulletsObjective = scoreboard.getNullableObjective(GZ_BULLETS_OBJECTIVE);
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				refillPlayerGuns(player);
				if (bulletsObjective != null) {
					seedSelectedGunAmmoScore(player, scoreboard, bulletsObjective);
				}
			}
		});
	}

	/** Keep every Guns++ gun in this player's inventory loaded, fire-ready, and full. */
	private static void refillPlayerGuns(ServerPlayerEntity player) {
		var inventory = player.getInventory();
		int size = inventory.size();
		for (int slot = 0; slot < size; slot++) {
			ItemStack fixed = fireReady(inventory.getStack(slot));
			if (fixed != null) {
				inventory.setStack(slot, fixed);
			}
		}
	}

	/**
	 * If {@code stack} is a Guns++ gun that is not already loaded (warped base) and full, return the
	 * corrected stack: {@code gz_data.bullets} topped to {@code gz_data.capacity} and the base item
	 * switched to {@code warped_fungus_on_a_stick} so it is fire-able. Returns {@code null} — meaning
	 * "leave the slot alone" — for empty stacks, non-gun items, ammo {@code clock}s, plain
	 * {@code carrot_on_a_stick}s carrying no {@code gz_data}, and guns that are already warped and full.
	 */
	private static ItemStack fireReady(ItemStack stack) {
		if (stack.isEmpty() || !isGunBase(stack)) {
			return null;
		}
		NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		NbtCompound custom = data.copyNbt();
		if (!custom.contains(GZ_DATA_KEY)) {
			return null; // not a Guns++ stack
		}
		NbtCompound gz = custom.getCompoundOrEmpty(GZ_DATA_KEY);
		if (!gz.contains(CAPACITY_KEY)) {
			return null; // ammo clocks and other gz stacks have no capacity — not a gun
		}
		byte capacity = gz.getByte(CAPACITY_KEY, (byte) 0);
		byte bullets = gz.getByte(BULLETS_KEY, (byte) 0);

		// A gun only fires as a loaded warped_fungus_on_a_stick. Guns++ swaps a gun back to
		// carrot_on_a_stick the moment its magazine hits 0 (notably a capacity-1 sniper after its
		// single shot), turning subsequent right-clicks into harmless dry-clicks — re-warp it.
		boolean needsRewarp = stack.isOf(Items.CARROT_ON_A_STICK);
		boolean needsTopUp = bullets < capacity;
		if (!needsRewarp && !needsTopUp) {
			return null; // already warped and full — nothing to do
		}

		gz.putByte(BULLETS_KEY, capacity);
		custom.put(GZ_DATA_KEY, gz);

		// copyComponentsToNewStack carries item_model / item_name / custom_data across to the warped
		// base; we then overwrite custom_data with the topped-up magazine.
		ItemStack out = needsRewarp
			? stack.copyComponentsToNewStack(Items.WARPED_FUNGUS_ON_A_STICK, stack.getCount())
			: stack;
		out.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(custom));
		return out;
	}

	/**
	 * Set the player's {@code gz_bullets} score to the capacity of the gun they are currently
	 * holding (main hand, else off hand), so the datapack's fire trigger's {@code gz_bullets >= 1}
	 * gate passes and the very next right-click fires. No-op when no gun is held.
	 */
	private static void seedSelectedGunAmmoScore(ServerPlayerEntity player, ServerScoreboard scoreboard,
			ScoreboardObjective objective) {
		int capacity = gunCapacity(player.getMainHandStack());
		if (capacity <= 0) {
			capacity = gunCapacity(player.getOffHandStack());
		}
		if (capacity <= 0) {
			return;
		}
		scoreboard.getOrCreateScore(player, objective).setScore(capacity);
	}

	/** The {@code gz_data.capacity} of {@code stack} if it is one of our Guns++ guns, else {@code -1}. */
	private static int gunCapacity(ItemStack stack) {
		if (stack.isEmpty() || !isGunBase(stack)) {
			return -1;
		}
		NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (data == null) {
			return -1;
		}
		NbtCompound gz = data.copyNbt().getCompoundOrEmpty(GZ_DATA_KEY);
		if (!gz.contains(CAPACITY_KEY)) {
			return -1; // ammo clock or non-gun gz stack
		}
		return gz.getByte(CAPACITY_KEY, (byte) 0);
	}
}
