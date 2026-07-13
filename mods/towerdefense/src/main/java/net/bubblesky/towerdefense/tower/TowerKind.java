package net.bubblesky.towerdefense.tower;

import java.util.function.Supplier;
import net.bubblesky.towerdefense.registry.ModBlocks;
import net.bubblesky.towerdefense.registry.ModItems;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;

/**
 * The buildable tower types. Each is a SINGLE functional block that fires from its
 * outer face; it carries the functional core {@link Block} (whose block entity does
 * the firing) and the one-shot "tower arrow" {@link Item} the shop hands out. The
 * {@code pole}/{@code ball}/{@code ballRadius} fields are vestigial from the old
 * stick-tower structure (radius 0 now keeps the muzzle offset at ~0.9).
 *
 * <p>Block/item references are wrapped in {@link Supplier}s so the enum can load
 * before {@link ModBlocks}/{@link ModItems} finish their static registration
 * (they are only dereferenced at runtime, never at class-load time).
 */
public enum TowerKind {
	/** Arrow tower: fast, cheap, single-target. */
	ARROW("arrow_tower", () -> ModBlocks.ARROW_TOWER, () -> ModItems.ARROW_TOWER_ARROW,
		Blocks.OAK_LOG, Blocks.STONE, 0),
	/** Cannon tower: slow, high-damage splash. */
	CANNON("cannon_tower", () -> ModBlocks.CANNON_TOWER, () -> ModItems.CANNON_TOWER_ARROW,
		Blocks.POLISHED_DEEPSLATE, Blocks.IRON_BLOCK, 0),
	/** Frost tower: slows the swarm. */
	FROST("frost_tower", () -> ModBlocks.FROST_TOWER, () -> ModItems.FROST_TOWER_ARROW,
		Blocks.PRISMARINE_BRICKS, Blocks.LIGHT_BLUE_CONCRETE, 0),
	/** Tower ball: a small, cheap, sticky mini arrow turret. */
	BALL("ball_tower", () -> ModBlocks.BALL_TOWER, () -> ModItems.BALL_TOWER_ARROW,
		Blocks.OAK_LOG, Blocks.STONE, 0),
	/** Lightning tower: slow, powerful, chains lightning bolts between enemies. */
	LIGHTNING("lightning_tower", () -> ModBlocks.LIGHTNING_TOWER, () -> ModItems.LIGHTNING_TOWER_ARROW,
		Blocks.LIGHTNING_ROD, Blocks.LIGHT_BLUE_CONCRETE, 0),
	/** Flamethrower tower: fast, close-range; sprays fire and leaves burning ground. */
	FLAME("flame_tower", () -> ModBlocks.FLAME_TOWER, () -> ModItems.FLAME_TOWER_ARROW,
		Blocks.MAGMA_BLOCK, Blocks.BLAST_FURNACE, 0),
	/** Sharpshooter tower: very long range, slow cadence; targets the toughest enemy in range. */
	SHARPSHOOTER("sharpshooter_tower", () -> ModBlocks.SHARPSHOOTER_TOWER, () -> ModItems.SHARPSHOOTER_TOWER_ARROW,
		Blocks.POLISHED_BLACKSTONE, Blocks.EMERALD_BLOCK, 0);

	private final String id;
	private final Supplier<Block> core;
	private final Supplier<Item> arrowItem;
	private final Block pole;
	private final Block ball;
	private final int ballRadius;

	TowerKind(String id, Supplier<Block> core, Supplier<Item> arrowItem,
			Block pole, Block ball, int ballRadius) {
		this.id = id;
		this.core = core;
		this.arrowItem = arrowItem;
		this.pole = pole;
		this.ball = ball;
		this.ballRadius = ballRadius;
	}

	/** The shop/command id, e.g. {@code "arrow_tower"}. */
	public String id() {
		return id;
	}

	/** The functional tower block (holds the firing block entity) placed at the ball's core. */
	public Block block() {
		return core.get();
	}

	/** The one-shot "tower arrow" item the shop grants for this kind. */
	public Item arrowItem() {
		return arrowItem.get();
	}

	/** Decorative pole block (the 1x1 stalk). */
	public Block pole() {
		return pole;
	}

	/** Decorative ball block (the orb at the top). */
	public Block ball() {
		return ball;
	}

	/** Vestigial (all towers are single-block); kept at 0 so the muzzle offset stays ~0.9. */
	public int ballRadius() {
		return ballRadius;
	}

	/** Resolve a shop id ("arrow_tower"/…) to a kind, or null if unknown. */
	public static TowerKind fromId(String id) {
		if (id == null) {
			return null;
		}
		for (TowerKind k : values()) {
			if (k.id.equalsIgnoreCase(id.trim())) {
				return k;
			}
		}
		return null;
	}

	/** Ordinal lookup that never throws (defaults to {@link #ARROW}). */
	public static TowerKind fromOrdinal(int ordinal) {
		TowerKind[] all = values();
		return (ordinal >= 0 && ordinal < all.length) ? all[ordinal] : ARROW;
	}
}
