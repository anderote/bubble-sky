package net.bubblesky.towerdefense.construction;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

/**
 * All player-configurable construction-spell settings. The record crosses the network, so
 * {@link #normalized()} is the server-side trust boundary: clients can request useful large
 * builds, but cannot smuggle unbounded dimensions or arbitrary block registry IDs.
 */
public record ConstructionConfig(
		int typeOrdinal,
		int materialOrdinal,
		int replaceOrdinal,
		int width,
		int length,
		int height,
		int thickness,
		int forwardOffset,
		int verticalOffset,
		int auxiliary,
		int fillDepth,
		boolean decorated,
		boolean consumeMaterials) {

	public enum Type {
		BRIDGE("Bridge"),
		WALL("Wall"),
		FLATTEN("Flatten"),
		PLATFORM("Tower Pad"),
		STAIRS("Tower Stairs"),
		LANE("Kill Lane");

		private final String display;
		Type(String display) { this.display = display; }
		public String display() { return display; }
		public Type next() { return values()[(ordinal() + 1) % values().length]; }
	}

	public enum Material {
		STONE_BRICKS("Stone Bricks", 0x7390A8),
		COBBLESTONE("Cobblestone", 0x7D858C),
		OAK_PLANKS("Oak Planks", 0xB98B4E),
		SPRUCE_PLANKS("Spruce Planks", 0x765335),
		BRICKS("Bricks", 0xA95545),
		DEEPSLATE_BRICKS("Deepslate Bricks", 0x4F515A),
		GLASS("Glass", 0xA4D9E8),
		DIRT("Dirt", 0x76533A);

		private final String display;
		private final int color;
		Material(String display, int color) {
			this.display = display;
			this.color = color;
		}
		public String display() { return display; }
		/** Resolve lazily so pure geometry tests do not need to bootstrap Minecraft registries. */
		public Block block() {
			return switch (this) {
				case STONE_BRICKS -> Blocks.STONE_BRICKS;
				case COBBLESTONE -> Blocks.COBBLESTONE;
				case OAK_PLANKS -> Blocks.OAK_PLANKS;
				case SPRUCE_PLANKS -> Blocks.SPRUCE_PLANKS;
				case BRICKS -> Blocks.BRICKS;
				case DEEPSLATE_BRICKS -> Blocks.DEEPSLATE_BRICKS;
				case GLASS -> Blocks.GLASS;
				case DIRT -> Blocks.DIRT;
			};
		}
		public Block stairBlock() {
			return switch (this) {
				case STONE_BRICKS -> Blocks.STONE_BRICK_STAIRS;
				case COBBLESTONE -> Blocks.COBBLESTONE_STAIRS;
				case OAK_PLANKS -> Blocks.OAK_STAIRS;
				case SPRUCE_PLANKS -> Blocks.SPRUCE_STAIRS;
				case BRICKS -> Blocks.BRICK_STAIRS;
				case DEEPSLATE_BRICKS -> Blocks.DEEPSLATE_BRICK_STAIRS;
				case GLASS, DIRT -> Blocks.STONE_BRICK_STAIRS;
			};
		}
		public boolean supportsStairs() {
			return this != GLASS && this != DIRT;
		}
		public int color() { return color; }
		public Material next() { return values()[(ordinal() + 1) % values().length]; }
		public Material next(Type type) {
			Material candidate = next();
			while (type == Type.STAIRS && !candidate.supportsStairs()) {
				candidate = candidate.next();
			}
			return candidate;
		}
	}

	public enum ReplaceMode {
		SAFE("Safe: air/soft only"),
		TERRAIN("Terrain"),
		ANY("Anything unprotected");

		private final String display;
		ReplaceMode(String display) { this.display = display; }
		public String display() { return display; }
		public ReplaceMode next() { return values()[(ordinal() + 1) % values().length]; }
	}

	public static ConstructionConfig defaults(Type type) {
		return switch (type) {
			case BRIDGE -> new ConstructionConfig(type.ordinal(), Material.STONE_BRICKS.ordinal(),
				ReplaceMode.TERRAIN.ordinal(), 2, 30, 1, 2, 2, 0, 1, 0, false, false);
			case WALL -> new ConstructionConfig(type.ordinal(), Material.STONE_BRICKS.ordinal(),
				ReplaceMode.SAFE.ordinal(), 1, 30, 4, 1, 3, 0, 0, 0, false, false);
			case FLATTEN -> new ConstructionConfig(type.ordinal(), Material.DIRT.ordinal(),
				ReplaceMode.TERRAIN.ordinal(), 50, 50, 1, 1, 2, 0, 12, 6, false, false);
			case PLATFORM -> new ConstructionConfig(type.ordinal(), Material.STONE_BRICKS.ordinal(),
				ReplaceMode.TERRAIN.ordinal(), 7, 7, 1, 1, 4, 0, 0, 0, false, false);
			case STAIRS -> new ConstructionConfig(type.ordinal(), Material.STONE_BRICKS.ordinal(),
				ReplaceMode.TERRAIN.ordinal(), 2, 16, 16, 1, 2, 0, 3, 0, false, false);
			case LANE -> new ConstructionConfig(type.ordinal(), Material.STONE_BRICKS.ordinal(),
				ReplaceMode.TERRAIN.ordinal(), 4, 24, 2, 1, 2, 0, 0, 0, false, false);
		};
	}

	public ConstructionConfig normalized() {
		Type type = type();
		Material material = material();
		if (type == Type.STAIRS && !material.supportsStairs()) {
			material = Material.STONE_BRICKS;
		}
		int maxWidth = type == Type.FLATTEN ? 64 : 16;
		int maxLength = type == Type.FLATTEN ? 64 : 128;
		return new ConstructionConfig(
			type.ordinal(), material.ordinal(), replaceMode().ordinal(),
			clamp(width, 1, maxWidth), clamp(length, 1, maxLength),
			clamp(height, 1, 32), clamp(thickness, 1, 4),
			clamp(forwardOffset, 1, 24), clamp(verticalOffset, -16, 32),
			clamp(auxiliary, 0, 32), clamp(fillDepth, 0, 16),
			decorated, consumeMaterials);
	}

	public Type type() {
		Type[] values = Type.values();
		return values[Math.floorMod(typeOrdinal, values.length)];
	}

	public Material material() {
		Material[] values = Material.values();
		return values[Math.floorMod(materialOrdinal, values.length)];
	}

	public ReplaceMode replaceMode() {
		ReplaceMode[] values = ReplaceMode.values();
		return values[Math.floorMod(replaceOrdinal, values.length)];
	}

	public ConstructionConfig withType(Type value) {
		return defaults(value);
	}

	public ConstructionConfig withMaterial(Material value) {
		return new ConstructionConfig(typeOrdinal, value.ordinal(), replaceOrdinal, width, length, height,
			thickness, forwardOffset, verticalOffset, auxiliary, fillDepth, decorated, consumeMaterials);
	}

	public ConstructionConfig withReplaceMode(ReplaceMode value) {
		return new ConstructionConfig(typeOrdinal, materialOrdinal, value.ordinal(), width, length, height,
			thickness, forwardOffset, verticalOffset, auxiliary, fillDepth, decorated, consumeMaterials);
	}

	public ConstructionConfig withWidth(int value) { return copy(value, length, height, thickness, forwardOffset, verticalOffset, auxiliary, fillDepth, decorated, consumeMaterials); }
	public ConstructionConfig withLength(int value) { return copy(width, value, height, thickness, forwardOffset, verticalOffset, auxiliary, fillDepth, decorated, consumeMaterials); }
	public ConstructionConfig withHeight(int value) { return copy(width, length, value, thickness, forwardOffset, verticalOffset, auxiliary, fillDepth, decorated, consumeMaterials); }
	public ConstructionConfig withThickness(int value) { return copy(width, length, height, value, forwardOffset, verticalOffset, auxiliary, fillDepth, decorated, consumeMaterials); }
	public ConstructionConfig withForwardOffset(int value) { return copy(width, length, height, thickness, value, verticalOffset, auxiliary, fillDepth, decorated, consumeMaterials); }
	public ConstructionConfig withVerticalOffset(int value) { return copy(width, length, height, thickness, forwardOffset, value, auxiliary, fillDepth, decorated, consumeMaterials); }
	public ConstructionConfig withAuxiliary(int value) { return copy(width, length, height, thickness, forwardOffset, verticalOffset, value, fillDepth, decorated, consumeMaterials); }
	public ConstructionConfig withFillDepth(int value) { return copy(width, length, height, thickness, forwardOffset, verticalOffset, auxiliary, value, decorated, consumeMaterials); }
	public ConstructionConfig withDecorated(boolean value) { return copy(width, length, height, thickness, forwardOffset, verticalOffset, auxiliary, fillDepth, value, consumeMaterials); }
	public ConstructionConfig withConsumeMaterials(boolean value) { return copy(width, length, height, thickness, forwardOffset, verticalOffset, auxiliary, fillDepth, decorated, value); }

	private ConstructionConfig copy(int width, int length, int height, int thickness, int forwardOffset,
			int verticalOffset, int auxiliary, int fillDepth, boolean decorated, boolean consume) {
		return new ConstructionConfig(typeOrdinal, materialOrdinal, replaceOrdinal, width, length, height,
			thickness, forwardOffset, verticalOffset, auxiliary, fillDepth, decorated, consume).normalized();
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
