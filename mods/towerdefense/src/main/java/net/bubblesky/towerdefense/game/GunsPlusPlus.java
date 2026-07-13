package net.bubblesky.towerdefense.game;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * Builds Guns++ (namespace {@code thepa}) gun and ammo {@link ItemStack}s in Java.
 *
 * <p>Guns++ guns/ammo are NOT registered items — they are vanilla base items
 * ({@code carrot_on_a_stick} for guns, {@code clock} for ammo) carrying custom data
 * components. The gun's behavior keys off {@code custom_data.gz_data} plus the
 * {@code item_model} id, so those must be byte-for-byte exact for Guns++ to recognize
 * the stack. Each factory here mirrors one of Guns++'s own datapack {@code /give}
 * functions.
 *
 * <p><b>Version sync:</b> these definitions mirror Guns++ datapack give functions as of
 * v5.8.7. If Guns++ updates, re-sync the {@code gz_data} ids/models and item models from
 * {@code data/thepa/function/give/*.mcfunction} inside the Guns++ jar.
 */
public final class GunsPlusPlus {
	private GunsPlusPlus() {
	}

	// ---- Guns (base item carrot_on_a_stick) --------------------------------

	/** Glock pistol — starter sidearm. Fires pistol {@link #bullets(int)}. */
	public static ItemStack glock() {
		NbtCompound gz = new NbtCompound();
		gz.putByte("capacity", (byte) 17);
		gz.putByte("bullets", (byte) 0);
		gz.putDouble("reload_time", 2.7d);
		gz.putByte("id", (byte) 18);
		gz.putDouble("debuff", -0.5d);
		return gun("guns/glock", gz, true, "thepa.item.name.gun_18");
	}

	/** AR-15 rifle — fires {@link #mediumRounds(int)}. */
	public static ItemStack ar15() {
		NbtCompound gz = new NbtCompound();
		gz.putByte("capacity", (byte) 30);
		gz.putByte("bullets", (byte) 0);
		gz.putByte("reload_time", (byte) 5);
		gz.putByte("id", (byte) 16);
		gz.putDouble("debuff", -0.5d);
		return gun("guns/ar_15", gz, true, "thepa.item.name.gun_16");
	}

	/** AK-47 rifle — fires {@link #mediumRounds(int)}. */
	public static ItemStack ak47() {
		NbtCompound gz = new NbtCompound();
		gz.putByte("capacity", (byte) 25);
		gz.putByte("bullets", (byte) 0);
		gz.putByte("reload_time", (byte) 5);
		gz.putByte("id", (byte) 23);
		gz.putDouble("debuff", -0.5d);
		return gun("guns/ak_47", gz, true, "thepa.item.name.gun_23");
	}

	/** Desert Eagle — fires {@link #fiftyCal(int)}. */
	public static ItemStack deagle() {
		NbtCompound gz = new NbtCompound();
		gz.putByte("capacity", (byte) 7);
		gz.putByte("bullets", (byte) 0);
		gz.putByte("reload_time", (byte) 5);
		gz.putByte("id", (byte) 26);
		gz.putDouble("debuff", -0.5d);
		return gun("guns/deagle", gz, true, "thepa.item.name.gun_26");
	}

	/** Hunting rifle — a sniper; fires {@link #heavyRounds(int)}. Omits HideFlags. */
	public static ItemStack huntingRifle() {
		NbtCompound gz = new NbtCompound();
		gz.putByte("capacity", (byte) 1);
		gz.putByte("bullets", (byte) 0);
		gz.putByte("reload_time", (byte) 3);
		gz.putByte("id", (byte) 21);
		gz.putInt("debuff", -200);
		gz.putByte("sniper", (byte) 1);
		return gun("guns/hunting_rifle/hunting_rifle", gz, false, "thepa.item.name.gun_21");
	}

	/** AWP — a sniper; fires {@link #heavyRounds(int)}. Omits HideFlags. */
	public static ItemStack awp() {
		NbtCompound gz = new NbtCompound();
		gz.putByte("capacity", (byte) 1);
		gz.putByte("bullets", (byte) 0);
		gz.putDouble("reload_time", 5.5d);
		gz.putByte("id", (byte) 11);
		gz.putInt("debuff", -200);
		gz.putByte("sniper", (byte) 1);
		return gun("guns/awp/awp", gz, false, "thepa.item.name.gun_11");
	}

	// ---- Ammo (base item clock) --------------------------------------------

	/** Pistol bullets (bullet_type 0) — feeds the {@link #glock()}. */
	public static ItemStack bullets(int count) {
		return ammo("ammo/bullets", (byte) 0, "thepa.item.name.bullet_0", count);
	}

	/** Medium rounds (bullet_type 6) — feed the {@link #ar15()} / {@link #ak47()}. */
	public static ItemStack mediumRounds(int count) {
		return ammo("ammo/medium_ammo", (byte) 6, "thepa.item.name.bullet_6", count);
	}

	/** Heavy rounds (bullet_type 11) — feed the {@link #huntingRifle()} / {@link #awp()}. */
	public static ItemStack heavyRounds(int count) {
		return ammo("ammo/sniper_ammo", (byte) 11, "thepa.item.name.bullet_11", count);
	}

	/** .50 cal rounds (bullet_type 26) — feed the {@link #deagle()}. */
	public static ItemStack fiftyCal(int count) {
		return ammo("ammo/50_cal_ammo", (byte) 26, "thepa.item.name.bullet_26", count);
	}

	// ---- Builders ----------------------------------------------------------

	/**
	 * Build a Guns++ gun stack: a {@code carrot_on_a_stick} carrying the given item model
	 * and {@code custom_data} = {@code { gz_data: {...}, HideFlags: 32 }} (HideFlags omitted
	 * for snipers), named yellow from the given translation key.
	 */
	private static ItemStack gun(String model, NbtCompound gzData, boolean hideFlags, String nameKey) {
		ItemStack stack = new ItemStack(Items.CARROT_ON_A_STICK);
		stack.set(DataComponentTypes.ITEM_MODEL, Identifier.of("minecraft", model));
		NbtCompound custom = new NbtCompound();
		custom.put("gz_data", gzData);
		if (hideFlags) {
			custom.putInt("HideFlags", 32);
		}
		stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(custom));
		stack.set(DataComponentTypes.ITEM_NAME, Text.translatable(nameKey).formatted(Formatting.YELLOW));
		return stack;
	}

	/**
	 * Build a Guns++ ammo stack: a {@code clock} carrying the given item model and
	 * {@code custom_data} = {@code { gz_data: { bullet_type: <byte> }, HideFlags: 32 }},
	 * named white from the given translation key.
	 */
	private static ItemStack ammo(String model, byte bulletType, String nameKey, int count) {
		ItemStack stack = new ItemStack(Items.CLOCK, count);
		stack.set(DataComponentTypes.ITEM_MODEL, Identifier.of("minecraft", model));
		NbtCompound gz = new NbtCompound();
		gz.putByte("bullet_type", bulletType);
		NbtCompound custom = new NbtCompound();
		custom.put("gz_data", gz);
		custom.putInt("HideFlags", 32);
		stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(custom));
		stack.set(DataComponentTypes.ITEM_NAME, Text.translatable(nameKey).formatted(Formatting.WHITE));
		return stack;
	}
}
