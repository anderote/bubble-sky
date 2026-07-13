package net.bubblesky.towerdefense.tower;

import java.util.function.Supplier;
import net.bubblesky.towerdefense.registry.ModBlocks;
import net.bubblesky.towerdefense.registry.ModItems;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;

/**
 * The three buildable tower types, each carrying everything the "shoot-to-place"
 * flow needs: the functional core {@link Block} (the ArrowTower/Cannon/Frost block
 * whose block entity does the firing), the one-shot "tower arrow" {@link Item} the
 * shop hands out, and the decorative stick-structure palette (pole block + ball
 * block + ball radius) used to build the tall thin tower around the core.
 *
 * <p>Block/item references are wrapped in {@link Supplier}s so the enum can load
 * before {@link ModBlocks}/{@link ModItems} finish their static registration
 * (they are only dereferenced at runtime, never at class-load time).
 */
public enum TowerKind {
	/** Arrow tower: wood pole + stone ball. Fast, cheap, single-target. */
	ARROW("arrow_tower", () -> ModBlocks.ARROW_TOWER, () -> ModItems.ARROW_TOWER_ARROW,
		Blocks.OAK_LOG, Blocks.STONE, 1),
	/** Cannon tower: dark-stone pole + a BIGGER iron ball. Slow, splash damage. */
	CANNON("cannon_tower", () -> ModBlocks.CANNON_TOWER, () -> ModItems.CANNON_TOWER_ARROW,
		Blocks.POLISHED_DEEPSLATE, Blocks.IRON_BLOCK, 2),
	/** Frost tower: prismarine pole + a light-blue ball. Slows the swarm. */
	FROST("frost_tower", () -> ModBlocks.FROST_TOWER, () -> ModItems.FROST_TOWER_ARROW,
		Blocks.PRISMARINE_BRICKS, Blocks.LIGHT_BLUE_CONCRETE, 1),
	/**
	 * Tower ball: a single sticky one-block mini arrow turret (no pole/orb — it is
	 * NEVER built via {@link TowerStructure}). The pole/ball palette is only carried
	 * for interface symmetry; {@code ballRadius 0} keeps the muzzle offset at ~0.9.
	 */
	BALL("ball_tower", () -> ModBlocks.BALL_TOWER, () -> ModItems.BALL_TOWER_ARROW,
		Blocks.OAK_LOG, Blocks.STONE, 0);

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

	/** Radius of the top orb in blocks (1 = ~3 wide, 2 = ~5 wide). */
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
