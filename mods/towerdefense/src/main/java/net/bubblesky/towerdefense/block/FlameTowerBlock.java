package net.bubblesky.towerdefense.block;

import com.mojang.serialization.MapCodec;
import net.bubblesky.towerdefense.blockentity.FlameTowerBlockEntity;
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
 * The flamethrower tower block; holds a {@link FlameTowerBlockEntity}.
 *
 * <p>Unlike the other towers (which use the shared {@code AbstractTowerBlockEntity::tick}
 * ticker), this block wires up {@link FlameTowerBlockEntity#serverTick}, a CUSTOM ticker
 * that runs both the shared targeting/firing pass AND the lingering burning-ground pass
 * ({@code tickFlames}) every tick — the burning ground must keep working between shots,
 * not only on the fire cooldown.
 */
public class FlameTowerBlock extends BlockWithEntity {
	public static final MapCodec<FlameTowerBlock> CODEC = createCodec(FlameTowerBlock::new);

	public FlameTowerBlock(Settings settings) {
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
		return new FlameTowerBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state,
			BlockEntityType<T> type) {
		if (world.isClient) {
			return null;
		}
		// Custom ticker: shared tick() + tickFlames() so the burning ground runs every tick.
		return validateTicker(type, ModBlockEntities.FLAME_TOWER, FlameTowerBlockEntity::serverTick);
	}
}
