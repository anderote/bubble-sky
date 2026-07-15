package net.bubblesky.towerdefense.progression;

import net.bubblesky.towerdefense.registry.ModItems;
import net.bubblesky.towerdefense.spell.SpellType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Grants (and clears) a {@link PlayerClass}'s per-life LOADOUT — the class weapon plus a
 * fixed row of spell placeholders — into DEDICATED hotbar slots, and is the single
 * server-side entry point for actually SELECTING a class ({@link #select}).
 *
 * <h2>Reserved hotbar slots (no clash with the starter kit)</h2>
 * The survival starter kit ({@code TowerDefenseMod.registerStartingGear}) places building
 * materials in main-inventory slots <b>9–13</b> and its weapons in the low hotbar. This
 * loadout owns a disjoint block of HOTBAR slots at the far (right) end:
 * <ul>
 *   <li>{@link #GEAR_SLOT} = <b>5</b> — the class weapon/tool.</li>
 *   <li>{@link #SPELL_SLOTS} = <b>{6, 7, 8}</b> — one placeholder per signature spell.</li>
 * </ul>
 * These never overlap 9–13 (a different inventory region entirely) nor the build-materials
 * kit, so raising/clearing a loadout leaves the starter build kit untouched.
 *
 * <h2>Real spells (Phase 2)</h2>
 * Each spell slot holds the registered {@code SpellItem} for one of the class's three
 * signature {@link SpellType}s — a right-clickable, mana-costed, cooldown-gated cast (no more
 * placeholder paper). The grants are still tagged {@link #CLASS_ITEM_KEY} so {@link #clear}
 * only ever removes OUR items, never a player's own parked gear.
 */
public final class ClassLoadout {

	private ClassLoadout() {
	}

	/** Hotbar slot for the class weapon/tool (right of the starter weapons; clear of 9–13). */
	public static final int GEAR_SLOT = 5;
	/** Hotbar slots reserved for the (placeholder) spell items — one per signature spell. */
	public static final int[] SPELL_SLOTS = {6, 7, 8};

	/**
	 * Custom-data marker key set to {@code 1} on every stack this loadout grants. Only
	 * marked stacks are removed when clearing/regranting, so a player's own items parked in
	 * these slots are never destroyed by a class switch.
	 */
	private static final String CLASS_ITEM_KEY = "td_class_item";

	/**
	 * The three signature {@link SpellType}s per class, indexed to {@link #SPELL_SLOTS}.
	 * Order matches the design's class table. These resolve to the registered spell items
	 * that {@link #grant} places into the hotbar.
	 */
	private static SpellType[] spellsFor(PlayerClass cls) {
		return switch (cls) {
			case MAGE -> new SpellType[] {SpellType.FIREBALL, SpellType.FROST_NOVA, SpellType.CHAIN_LIGHTNING};
			case RANGER -> new SpellType[] {SpellType.MULTISHOT, SpellType.TRAP, SpellType.SUMMON_WOLF};
			case ENGINEER -> new SpellType[] {SpellType.DEPLOY_TURRET, SpellType.REPAIR_PULSE, SpellType.WALL_OF_ACID};
			case NECROMANCER -> new SpellType[] {SpellType.RAISE_DEAD, SpellType.SUMMON_SQUAD, SpellType.BONE_SPEAR};
		};
	}

	/** The (placeholder) class weapon item + display name for {@link #GEAR_SLOT}. */
	private static ItemStack gearStack(PlayerClass cls) {
		Item item;
		String name;
		switch (cls) {
			case MAGE -> { item = Items.BLAZE_ROD; name = "Mage Staff"; }
			case RANGER -> { item = Items.BOW; name = "Ranger Bow"; }
			case ENGINEER -> { item = Items.IRON_PICKAXE; name = "Engineer's Wrench"; }
			case NECROMANCER -> { item = Items.BONE; name = "Bone Staff"; }
			default -> { item = Items.STICK; name = "Class Gear"; }
		}
		return marked(new ItemStack(item), name, Formatting.GOLD, false);
	}

	// ---- select / grant / clear -------------------------------------------
	/**
	 * SELECT {@code cls} for {@code player}: set it as the active class on their record,
	 * (re)grant the loadout, re-apply {@link StatModifiers} (so the per-life bias + mana
	 * cap refresh), persist, and resync the HUD. This is the shared implementation behind
	 * both {@code /td class <name>} and the {@code SelectClassPayload} handler.
	 *
	 * @return {@code true} once selected (always succeeds for a non-null class)
	 */
	public static boolean select(ServerPlayerEntity player, PlayerClass cls) {
		if (cls == null) {
			return false;
		}
		MinecraftServer server = player.getServer();
		if (server == null) {
			return false;
		}
		ProgressState state = ProgressState.get(server);
		PlayerProgress progress = state.forPlayer(player.getUuid());
		progress.setActiveClass(cls);
		state.markDirty();

		grant(player, cls);
		// Re-apply attributes so the new class's stat bias + max-mana take effect, then push
		// a fresh snapshot (which now carries mana/activeClass/active-class level to the HUD).
		StatModifiers.apply(player, progress);
		ProgressEvents.sync(player, progress);
		return true;
	}

	/**
	 * (Re)grant {@code cls}'s loadout into the reserved slots: clear any previously-granted
	 * class items first (so switching never stacks or leaks the old kit), then place the
	 * class weapon and the placeholder spell row. Called on selection and on respawn to
	 * restore the current class's kit.
	 */
	public static void grant(ServerPlayerEntity player, PlayerClass cls) {
		clear(player);
		player.getInventory().setStack(GEAR_SLOT, gearStack(cls));
		SpellType[] spells = spellsFor(cls);
		for (int i = 0; i < SPELL_SLOTS.length && i < spells.length; i++) {
			// The real registered SpellItem for this signature spell, tagged as a class grant
			// (so clear() only ever removes our items). Its own item name/model are shown.
			ItemStack spell = markOnly(new ItemStack(ModItems.spellItem(spells[i])));
			player.getInventory().setStack(SPELL_SLOTS[i], spell);
		}
	}

	/**
	 * Remove every loadout-granted stack ({@link #CLASS_ITEM_KEY}-tagged) from the reserved
	 * slots {@link #GEAR_SLOT} + {@link #SPELL_SLOTS}. Leaves any non-class item a player
	 * happens to have parked there untouched. Safe to call when unclassed.
	 */
	public static void clear(ServerPlayerEntity player) {
		clearSlot(player, GEAR_SLOT);
		for (int slot : SPELL_SLOTS) {
			clearSlot(player, slot);
		}
	}

	private static void clearSlot(ServerPlayerEntity player, int slot) {
		ItemStack existing = player.getInventory().getStack(slot);
		if (isClassItem(existing)) {
			player.getInventory().setStack(slot, ItemStack.EMPTY);
		}
	}

	// ---- helpers -----------------------------------------------------------
	/** Whether a stack was granted by this loadout (carries the {@link #CLASS_ITEM_KEY} tag). */
	private static boolean isClassItem(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
		return data != null && data.copyNbt().getInt(CLASS_ITEM_KEY, 0) == 1;
	}

	/**
	 * Tag a stack as a class grant WITHOUT renaming it — used for real spell items so their
	 * own registered name/model show through, while {@link #clear} still recognizes them.
	 */
	private static ItemStack markOnly(ItemStack stack) {
		NbtCompound custom = new NbtCompound();
		custom.putInt(CLASS_ITEM_KEY, 1);
		stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(custom));
		return stack;
	}

	/**
	 * Tag a stack as a class grant (so {@link #clear} recognizes it) and give it a custom
	 * display name. {@code placeholder} adds a lore-style hint that this is a Phase-1 stub.
	 */
	private static ItemStack marked(ItemStack stack, String name, Formatting color, boolean placeholder) {
		NbtCompound custom = new NbtCompound();
		custom.putInt(CLASS_ITEM_KEY, 1);
		if (placeholder) {
			custom.putInt("td_spell_placeholder", 1);
		}
		stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(custom));
		stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name).formatted(color));
		return stack;
	}
}
