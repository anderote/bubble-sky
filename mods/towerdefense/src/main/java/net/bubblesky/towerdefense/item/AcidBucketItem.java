package net.bubblesky.towerdefense.item;

import net.bubblesky.towerdefense.registry.ModBlocks;
import net.bubblesky.towerdefense.registry.ModFluids;
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
 * A bucket that places a full {@code towerdefense:acid} <em>fluid source</em> where the
 * player clicks (like a water bucket), then empties to a plain bucket (in survival).
 *
 * <p>Now that acid is a real vanilla-engine fluid, placing it just sets the acid block's
 * default (still/source, level 8) state and schedules a fluid tick so the engine begins
 * spreading it downhill/outward exactly like water. Empties to {@link Items#BUCKET} and
 * stays {@code maxCount 1}.
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
			// Place the still source, then kick off a fluid tick so it starts flowing.
			world.setBlockState(placePos, ModBlocks.ACID.getDefaultState());
			world.scheduleFluidTick(placePos, ModFluids.STILL_ACID, ModFluids.STILL_ACID.getTickRate(world));

			PlayerEntity player = context.getPlayer();
			if (player != null && !player.getAbilities().creativeMode) {
				context.getStack().decrement(1);
				player.giveItemStack(new ItemStack(Items.BUCKET));
			}
		}
		return ActionResult.SUCCESS;
	}
}
