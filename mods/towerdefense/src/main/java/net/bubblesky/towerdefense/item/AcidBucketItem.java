package net.bubblesky.towerdefense.item;

import net.bubblesky.towerdefense.block.AcidBlock;
import net.bubblesky.towerdefense.registry.ModBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * A bucket that places a full-charge {@code towerdefense:acid} source where the
 * player clicks, then empties to a plain bucket (in survival). Mirrors vanilla
 * bucket-emptying flavor without wiring up a full fluid.
 */
public class AcidBucketItem extends Item {
	public AcidBucketItem(Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		World world = context.getWorld();
		BlockPos clicked = context.getBlockPos();
		BlockState clickedState = world.getBlockState(clicked);

		// Place into the clicked block if it's replaceable, else onto its face.
		BlockPos placePos = clickedState.isReplaceable() ? clicked : clicked.offset(context.getSide());
		BlockState target = world.getBlockState(placePos);
		if (!target.isAir() && !target.isReplaceable()) {
			return ActionResult.PASS;
		}

		if (!world.isClient) {
			world.setBlockState(placePos, ModBlocks.ACID.getDefaultState()
				.with(AcidBlock.CHARGE, AcidBlock.START_CHARGE));
			world.scheduleBlockTick(placePos, ModBlocks.ACID, AcidBlock.TICK_DELAY);

			PlayerEntity player = context.getPlayer();
			if (player != null && !player.getAbilities().creativeMode) {
				context.getStack().decrement(1);
				player.giveItemStack(new ItemStack(Items.BUCKET));
			}
		}
		return ActionResult.SUCCESS;
	}
}
