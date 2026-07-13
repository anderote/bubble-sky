package net.bubblesky.towerdefense.item;

import java.util.UUID;
import net.bubblesky.towerdefense.tower.TowerKind;
import net.bubblesky.towerdefense.tower.TowerStructure;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * The placeable "tower block" the shop hands out ({@code /td buy}). Placing it puts down
 * the single-block tower at the targeted cell (ground or against a wall) via
 * {@link TowerStructure#build}, which stamps the placing player as the tower's owner (so
 * its kills pay them coins) and registers it so {@code /td reset} tears it down. Vanilla
 * single-block placement is otherwise overridden — we never call {@code super.useOnBlock}.
 */
public class TowerBlockItem extends BlockItem {
	private final TowerKind kind;

	public TowerBlockItem(Block block, Settings settings, TowerKind kind) {
		super(block, settings);
		this.kind = kind;
	}

	/** The tower kind this item raises when placed. */
	public TowerKind kind() {
		return kind;
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		World world = context.getWorld();
		// The clicked cell if replaceable (tall grass/snow), else the cell against the face.
		BlockPos clicked = context.getBlockPos();
		BlockPos target = world.getBlockState(clicked).isReplaceable()
			? clicked : clicked.offset(context.getSide());
		if (!world.getBlockState(target).isReplaceable()) {
			return ActionResult.FAIL;
		}
		if (!(world instanceof ServerWorld serverWorld)) {
			// Client: report success so the arm swings; the server does the real work.
			return ActionResult.SUCCESS;
		}

		PlayerEntity player = context.getPlayer();
		UUID placer = player != null ? player.getUuid() : null;

		// Place the single-block tower at the targeted cell (ground or against a wall);
		// build() stamps the placer and registers it with the arena state.
		TowerStructure.build(serverWorld, target, kind, placer);

		if (player == null || !player.getAbilities().creativeMode) {
			context.getStack().decrement(1);
		}
		BlockState placedSound = kind.block().getDefaultState();
		serverWorld.playSound(null, target, placedSound.getSoundGroup().getPlaceSound(),
			SoundCategory.BLOCKS, 1.0f, 1.0f);
		return ActionResult.SUCCESS;
	}
}
