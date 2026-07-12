package net.bubblesky.towerdefense.registry;

import java.util.function.Function;
import net.bubblesky.towerdefense.TowerDefenseMod;
import net.bubblesky.towerdefense.item.AcidBucketItem;
import net.minecraft.item.Item;
import net.minecraft.item.ToolMaterial;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/**
 * Registers the mod's items: three medieval melee weapons plus the {@code coin}
 * currency item.
 *
 * <p>Weapons use the 1.21.6 {@code Item.Settings.sword(ToolMaterial, attackDamage,
 * attackSpeed)} helper, which wires up the melee attack attribute modifiers, the
 * tool data component and durability from the {@link ToolMaterial}. Values are
 * tuned to feel distinct: the spear is quick, the mace hits hard, the war hammer
 * is the slow heavy-hitter.
 */
public final class ModItems {
	private ModItems() {
	}

	/** Number of weapon items (for logging). */
	public static final int WEAPON_COUNT = 3;

	// Quick, moderate damage — reach-flavored poke weapon.
	public static final Item SPEAR = register("spear",
		Item::new, new Item.Settings().sword(ToolMaterial.IRON, 3.0f, -2.6f));

	// Heavy, slow, high single-hit damage.
	public static final Item MACE = register("mace",
		Item::new, new Item.Settings().sword(ToolMaterial.IRON, 5.0f, -3.0f));

	// Heaviest and slowest — the biggest single hit.
	public static final Item WAR_HAMMER = register("war_hammer",
		Item::new, new Item.Settings().sword(ToolMaterial.IRON, 6.0f, -3.4f));

	// Currency dropped by hostile mobs; spent at the (future) shop.
	public static final Item COIN = register("coin",
		Item::new, new Item.Settings());

	// Places a full-charge acid source on use, then empties to a bucket.
	public static final Item ACID_BUCKET = register("acid_bucket",
		AcidBucketItem::new, new Item.Settings().maxCount(1));

	public static Item register(String name, Function<Item.Settings, Item> factory, Item.Settings settings) {
		RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(TowerDefenseMod.MOD_ID, name));
		Item item = factory.apply(settings.registryKey(key));
		return Registry.register(Registries.ITEM, key, item);
	}

	/** Forces class load so the static registrations above run. */
	public static void initialize() {
	}
}
