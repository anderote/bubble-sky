package net.bubblesky.towerdefense.block;

import com.mojang.serialization.MapCodec;
import net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity;
import net.bubblesky.towerdefense.blockentity.BallTowerBlockEntity;
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
 * The tower-ball block: a single-block mini arrow turret that sticks to any face
 * (including vertical walls). Holds a {@link BallTowerBlockEntity} that ticks on the
 * server and auto-fires short-range arrows at nearby hostile mobs.
 */
public class BallTowerBlock extends BlockWithEntity {
	public static final MapCodec<BallTowerBlock> CODEC = createCodec(BallTowerBlock::new);

	public BallTowerBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}

	@Override
	protected BlockRenderType getRenderType(BlockState state) {
		return BlockRenderType.MODEL;
	}

	@Nullable
	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new BallTowerBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state,
			BlockEntityType<T> type) {
		if (world.isClient) {
			return null;
		}
		return validateTicker(type, ModBlockEntities.BALL_TOWER, AbstractTowerBlockEntity::tick);
	}
}
