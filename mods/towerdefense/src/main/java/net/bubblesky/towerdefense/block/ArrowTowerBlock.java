package net.bubblesky.towerdefense.block;

import com.mojang.serialization.MapCodec;
import net.bubblesky.towerdefense.blockentity.ArrowTowerBlockEntity;
import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * The arrow tower block. Holds an {@link ArrowTowerBlockEntity} that ticks on
 * the server and auto-fires arrows at nearby hostile mobs.
 */
public class ArrowTowerBlock extends BlockWithEntity {
	public static final MapCodec<ArrowTowerBlock> CODEC = createCodec(ArrowTowerBlock::new);

	public ArrowTowerBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}

	@Override
	protected BlockRenderType getRenderType(BlockState state) {
		// BlockWithEntity subclasses must opt back into normal model rendering.
		return BlockRenderType.MODEL;
	}

	@Nullable
	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new ArrowTowerBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state,
			BlockEntityType<T> type) {
		if (world.isClient) {
			return null;
		}
		return validateTicker(type, ModBlockEntities.ARROW_TOWER, ArrowTowerBlockEntity::tick);
	}
}
