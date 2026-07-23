package net.bubblesky.towerdefense.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

class ConstructionGeometryTest {
	private static final BlockPos PLAYER = new BlockPos(0, 65, 0);

	@Test
	void bridgePresetIsTwoByTwoAndThirtyLongInFrontOfPlayer() {
		ConstructionConfig config = ConstructionConfig.defaults(ConstructionConfig.Type.BRIDGE);
		assertEquals(ConstructionConfig.ReplaceMode.TERRAIN, config.replaceMode());
		var cells = ConstructionGeometry.cells(PLAYER, Direction.NORTH, config);
		assertEquals(120, cells.size());
		Set<BlockPos> positions = cells.stream().map(ConstructionGeometry.PlannedCell::pos).collect(Collectors.toSet());
		assertTrue(positions.contains(new BlockPos(-1, 64, -2)));
		assertTrue(positions.contains(new BlockPos(0, 63, -31)));
	}

	@Test
	void wallPresetIsFourHighAndThirtyLong() {
		ConstructionConfig config = ConstructionConfig.defaults(ConstructionConfig.Type.WALL);
		var cells = ConstructionGeometry.cells(PLAYER, Direction.NORTH, config);
		assertEquals(120, cells.size());
		assertEquals(120, ConstructionGeometry.estimatedScannedBlocks(config));
		var guides = ConstructionGeometry.previewGuideBoxes(PLAYER, Direction.NORTH, config);
		assertEquals(120, guides.size());
		assertEquals(69.0, guides.stream().mapToDouble(box -> box.maxY).max().orElseThrow());
	}

	@Test
	void flattenPresetSupportsTheRequestedFiftyByFiftyFootprintWithinSafetyLimit() {
		ConstructionConfig config = ConstructionConfig.defaults(ConstructionConfig.Type.FLATTEN);
		assertEquals(50, config.width());
		assertEquals(50, config.length());
		assertEquals(47_500, ConstructionGeometry.estimatedScannedBlocks(config));
		assertEquals(47_500, ConstructionGeometry.cells(PLAYER, Direction.EAST, config).size());
	}

	@Test
	void towerPadPresetMakesASevenBySevenFoundation() {
		ConstructionConfig config = ConstructionConfig.defaults(ConstructionConfig.Type.PLATFORM);
		var cells = ConstructionGeometry.cells(PLAYER, Direction.NORTH, config);
		assertEquals(49, cells.size());
		assertEquals(49, ConstructionGeometry.estimatedScannedBlocks(config));
	}

	@Test
	void towerStairsRiseOneBlockPerStepAndIncludeTheLanding() {
		ConstructionConfig config = ConstructionConfig.defaults(ConstructionConfig.Type.STAIRS);
		var cells = ConstructionGeometry.cells(PLAYER, Direction.NORTH, config);
		assertEquals(38, cells.size());
		assertEquals(32, cells.stream()
			.filter(cell -> cell.operation() == ConstructionGeometry.Operation.STAIR)
			.count());
		assertTrue(cells.stream().anyMatch(cell -> cell.pos().equals(new BlockPos(-1, 80, -17))));
		assertTrue(cells.stream().anyMatch(cell -> cell.pos().equals(new BlockPos(0, 80, -20))));
		assertEquals(38, ConstructionGeometry.previewGuideBoxes(PLAYER, Direction.NORTH, config).size());
	}

	@Test
	void killLanePresetBuildsTwoParallelWallsAroundTheGap() {
		ConstructionConfig config = ConstructionConfig.defaults(ConstructionConfig.Type.LANE);
		var cells = ConstructionGeometry.cells(PLAYER, Direction.NORTH, config);
		assertEquals(96, cells.size());
		assertEquals(96, ConstructionGeometry.estimatedScannedBlocks(config));
		assertTrue(cells.stream().anyMatch(cell -> cell.pos().equals(new BlockPos(-3, 65, -2))));
		assertTrue(cells.stream().anyMatch(cell -> cell.pos().equals(new BlockPos(2, 66, -25))));
	}

	@Test
	void hostileDimensionsAreClampedBeforeGeometryExpansion() {
		ConstructionConfig hostile = new ConstructionConfig(2, 999, 999,
			50_000, 50_000, 500, 500, 999, 999, 999, 999, true, false).normalized();
		assertEquals(64, hostile.width());
		assertEquals(64, hostile.length());
		assertEquals(24, hostile.forwardOffset());
		assertEquals(32, hostile.auxiliary());
		assertEquals(16, hostile.fillDepth());
	}
}
