package net.bubblesky.towerdefense.blockentity;

import net.bubblesky.towerdefense.game.WaveManager;
import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;

/** Support tower: reduces accumulated wall-break damage near itself. */
public class MasonTowerBlockEntity extends AbstractTowerBlockEntity {
	private static final double BASE_RANGE = 18.0;
	private static final int BASE_COOLDOWN = 45;
	private static final int REPAIR_UNITS = 18;

	public MasonTowerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.MASON_TOWER, pos, state);
	}

	@Override
	public net.bubblesky.towerdefense.tower.TowerKind kind() {
		return net.bubblesky.towerdefense.tower.TowerKind.MASON;
	}

	@Override
	protected double baseRange() {
		return BASE_RANGE;
	}

	@Override
	protected int baseCooldown() {
		return BASE_COOLDOWN;
	}

	@Override
	protected void fire(ServerWorld world, double cx, double cy, double cz, HostileEntity target) {
		int repaired = WaveManager.reinforceWallsNear(pos, range(), REPAIR_UNITS * getTier());
		if (repaired <= 0) {
			return;
		}
		world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, world.getBlockState(pos)),
			cx, cy + 0.4, cz, 12 + 4 * getTier(), 0.5, 0.5, 0.5, 0.02);
		world.playSound(null, cx, cy, cz, SoundEvents.BLOCK_ANVIL_USE, SoundCategory.BLOCKS, 0.35f, 1.6f);
	}
}
