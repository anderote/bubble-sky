package net.bubblesky.towerdefense.construction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

/** Pure geometry shared by the client ghost and the server-authoritative builder. */
public final class ConstructionGeometry {
	private ConstructionGeometry() {
	}

	public enum Operation {
		PLACE,
		STAIR,
		FILL_AIR,
		CLEAR
	}

	public record PlannedCell(BlockPos pos, Operation operation) {
	}

	public record PreviewBox(Box box, int color, float alpha) {
	}

	public static List<PlannedCell> cells(BlockPos playerPos, Direction facing, ConstructionConfig raw) {
		ConstructionConfig config = raw.normalized();
		Map<BlockPos, Operation> cells = new LinkedHashMap<>();
		Basis basis = basis(playerPos, horizontal(facing), config);
		switch (config.type()) {
			case BRIDGE -> bridgeCells(cells, basis, config);
			case WALL -> wallCells(cells, basis, config);
			case FLATTEN -> flattenCells(cells, basis, config);
			case PLATFORM -> platformCells(cells, basis, config);
			case STAIRS -> stairCells(cells, basis, config);
			case LANE -> laneCells(cells, basis, config);
		}
		return cells.entrySet().stream()
			.map(entry -> new PlannedCell(entry.getKey(), entry.getValue()))
			.toList();
	}

	public static List<PreviewBox> previewBoxes(BlockPos playerPos, Direction facing, ConstructionConfig raw) {
		ConstructionConfig config = raw.normalized();
		Basis b = basis(playerPos, horizontal(facing), config);
		List<PreviewBox> boxes = new ArrayList<>();
		int color = config.material().color();
		switch (config.type()) {
			case BRIDGE -> {
				int start = -config.width() / 2;
				boxes.add(new PreviewBox(cuboid(b, start, config.width(), 0, config.length(),
					b.baseY - config.thickness() + 1, config.thickness()), color, 0.25f));
				if (config.decorated() && config.auxiliary() > 0) {
					boxes.add(new PreviewBox(cuboid(b, start, 1, 0, config.length(),
						b.baseY + 1, config.auxiliary()), color, 0.30f));
					if (config.width() > 1) {
						boxes.add(new PreviewBox(cuboid(b, start + config.width() - 1, 1, 0,
							config.length(), b.baseY + 1, config.auxiliary()), color, 0.30f));
					}
				}
			}
			case WALL -> {
				int start = -config.length() / 2;
				boxes.add(new PreviewBox(cuboid(b, start, config.length(), 0, config.thickness(),
					b.baseY, config.height()), color, 0.25f));
				if (config.decorated()) {
					for (int x = 0; x < config.length(); x += 2) {
						boxes.add(new PreviewBox(cuboid(b, start + x, 1, 0, config.thickness(),
							b.baseY + config.height(), 1), color, 0.32f));
					}
				}
			}
			case FLATTEN -> {
				int start = -config.width() / 2;
				boxes.add(new PreviewBox(cuboid(b, start, config.width(), 0, config.length(),
					b.baseY, 1), color, 0.22f));
				if (config.auxiliary() > 0) {
					boxes.add(new PreviewBox(cuboid(b, start, config.width(), 0, config.length(),
						b.baseY + 1, config.auxiliary()), 0xE85D62, 0.075f));
				}
				if (config.fillDepth() > 0) {
					boxes.add(new PreviewBox(cuboid(b, start, config.width(), 0, config.length(),
						b.baseY - config.fillDepth(), config.fillDepth()), 0x5A9BE8, 0.065f));
				}
			}
			case PLATFORM -> {
				int start = -config.width() / 2;
				boxes.add(new PreviewBox(cuboid(b, start, config.width(), 0, config.length(),
					b.baseY - config.thickness() + 1, config.thickness()), color, 0.25f));
			}
			case STAIRS -> {
				int start = -config.width() / 2;
				for (int step = 0; step < config.height(); step++) {
					boxes.add(new PreviewBox(cuboid(b, start, config.width(), step, 1,
						b.baseY + step, 1), color, 0.23f));
				}
				if (config.auxiliary() > 0) {
					boxes.add(new PreviewBox(cuboid(b, start, config.width(), config.height(),
						config.auxiliary(), b.baseY + config.height() - 1, 1), color, 0.27f));
				}
			}
			case LANE -> {
				int outerLeft = -config.width() / 2 - config.thickness();
				int outerRight = -config.width() / 2 + config.width();
				boxes.add(new PreviewBox(cuboid(b, outerLeft, config.thickness(), 0, config.length(),
					b.baseY, config.height()), color, 0.25f));
				boxes.add(new PreviewBox(cuboid(b, outerRight, config.thickness(), 0, config.length(),
					b.baseY, config.height()), color, 0.25f));
			}
		}
		return boxes;
	}

	/**
	 * Block-sized outline guides make height and thickness readable as a 3D hologram instead of
	 * looking like a flat footprint. Normal presets get an exact block grid; very large custom
	 * builds fall back to one horizontal guide per level to keep rendering bounded.
	 */
	public static List<Box> previewGuideBoxes(BlockPos playerPos, Direction facing, ConstructionConfig raw) {
		ConstructionConfig config = raw.normalized();
		if (config.type() == ConstructionConfig.Type.FLATTEN) {
			return List.of();
		}
		List<PlannedCell> cells = cells(playerPos, facing, config);
		if (cells.size() <= 2_048) {
			return cells.stream()
				.map(cell -> new Box(cell.pos()))
				.toList();
		}
		return previewBoxes(playerPos, facing, config).stream()
			.map(PreviewBox::box)
			.toList();
	}

	public static int estimatedScannedBlocks(ConstructionConfig config) {
		config = config.normalized();
		return switch (config.type()) {
			case BRIDGE -> config.width() * config.length() * config.thickness()
				+ (config.decorated() ? 2 * config.length() * config.auxiliary() : 0);
			case WALL -> config.length() * config.thickness() * config.height()
				+ (config.decorated() ? ((config.length() + 1) / 2) * config.thickness() : 0);
			case FLATTEN -> config.width() * config.length() * (1 + config.auxiliary() + config.fillDepth());
			case PLATFORM -> config.width() * config.length() * config.thickness();
			case STAIRS -> config.width() * (config.height() + config.auxiliary());
			case LANE -> 2 * config.thickness() * config.length() * config.height();
		};
	}

	private static void bridgeCells(Map<BlockPos, Operation> out, Basis b, ConstructionConfig c) {
		int start = -c.width() / 2;
		for (int z = 0; z < c.length(); z++) {
			for (int x = 0; x < c.width(); x++) {
				for (int y = 0; y < c.thickness(); y++) {
					put(out, b.at(start + x, -y, z), Operation.PLACE);
				}
			}
			if (c.decorated()) {
				for (int y = 1; y <= c.auxiliary(); y++) {
					put(out, b.at(start, y, z), Operation.PLACE);
					put(out, b.at(start + c.width() - 1, y, z), Operation.PLACE);
				}
			}
		}
	}

	private static void wallCells(Map<BlockPos, Operation> out, Basis b, ConstructionConfig c) {
		int start = -c.length() / 2;
		for (int x = 0; x < c.length(); x++) {
			for (int z = 0; z < c.thickness(); z++) {
				for (int y = 0; y < c.height(); y++) {
					put(out, b.at(start + x, y, z), Operation.PLACE);
				}
				if (c.decorated() && x % 2 == 0) {
					put(out, b.at(start + x, c.height(), z), Operation.PLACE);
				}
			}
		}
	}

	private static void flattenCells(Map<BlockPos, Operation> out, Basis b, ConstructionConfig c) {
		int start = -c.width() / 2;
		for (int x = 0; x < c.width(); x++) {
			for (int z = 0; z < c.length(); z++) {
				for (int y = 1; y <= c.auxiliary(); y++) {
					put(out, b.at(start + x, y, z), Operation.CLEAR);
				}
				for (int y = 1; y <= c.fillDepth(); y++) {
					put(out, b.at(start + x, -y, z), Operation.FILL_AIR);
				}
				put(out, b.at(start + x, 0, z), Operation.PLACE);
			}
		}
	}

	private static void platformCells(Map<BlockPos, Operation> out, Basis b, ConstructionConfig c) {
		int start = -c.width() / 2;
		for (int x = 0; x < c.width(); x++) {
			for (int z = 0; z < c.length(); z++) {
				for (int y = 0; y < c.thickness(); y++) {
					put(out, b.at(start + x, -y, z), Operation.PLACE);
				}
			}
		}
	}

	private static void stairCells(Map<BlockPos, Operation> out, Basis b, ConstructionConfig c) {
		int start = -c.width() / 2;
		for (int step = 0; step < c.height(); step++) {
			for (int x = 0; x < c.width(); x++) {
				put(out, b.at(start + x, step, step), Operation.STAIR);
			}
		}
		for (int z = 0; z < c.auxiliary(); z++) {
			for (int x = 0; x < c.width(); x++) {
				put(out, b.at(start + x, c.height() - 1, c.height() + z), Operation.PLACE);
			}
		}
	}

	private static void laneCells(Map<BlockPos, Operation> out, Basis b, ConstructionConfig c) {
		int outerLeft = -c.width() / 2 - c.thickness();
		int outerRight = -c.width() / 2 + c.width();
		for (int z = 0; z < c.length(); z++) {
			for (int t = 0; t < c.thickness(); t++) {
				for (int y = 0; y < c.height(); y++) {
					put(out, b.at(outerLeft + t, y, z), Operation.PLACE);
					put(out, b.at(outerRight + t, y, z), Operation.PLACE);
				}
			}
		}
	}

	private static void put(Map<BlockPos, Operation> out, BlockPos pos, Operation operation) {
		out.put(pos.toImmutable(), operation);
	}

	private static Basis basis(BlockPos playerPos, Direction facing, ConstructionConfig config) {
		Direction right = facing.rotateYClockwise();
		BlockPos origin = playerPos.offset(facing, config.forwardOffset());
		int relativeY = switch (config.type()) {
			case WALL, STAIRS, LANE -> 0;
			default -> -1;
		};
		int baseY = playerPos.getY() + config.verticalOffset() + relativeY;
		return new Basis(origin.getX(), baseY, origin.getZ(), facing, right);
	}

	private static Direction horizontal(Direction facing) {
		return facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
	}

	private static Box cuboid(Basis b, int rightStart, int rightLength, int forwardStart,
			int forwardLength, int y, int height) {
		BlockPos a = b.at(rightStart, y - b.baseY, forwardStart);
		BlockPos z = b.at(rightStart + rightLength - 1, y - b.baseY, forwardStart + forwardLength - 1);
		return new Box(
			Math.min(a.getX(), z.getX()), y, Math.min(a.getZ(), z.getZ()),
			Math.max(a.getX(), z.getX()) + 1, y + height, Math.max(a.getZ(), z.getZ()) + 1);
	}

	private record Basis(int originX, int baseY, int originZ, Direction forward, Direction right) {
		BlockPos at(int rightOffset, int yOffset, int forwardOffset) {
			return new BlockPos(
				originX + right.getOffsetX() * rightOffset + forward.getOffsetX() * forwardOffset,
				baseY + yOffset,
				originZ + right.getOffsetZ() * rightOffset + forward.getOffsetZ() * forwardOffset);
		}
	}
}
