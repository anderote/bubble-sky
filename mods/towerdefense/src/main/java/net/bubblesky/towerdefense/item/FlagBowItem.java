package net.bubblesky.towerdefense.item;

import net.bubblesky.towerdefense.entity.FlagArrowEntity;
import net.bubblesky.towerdefense.registry.ModEntities;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

/**
 * The Flag Bow: draws and fires like a vanilla bow, but needs <b>no ammo</b> — every
 * release looses a {@link FlagArrowEntity}, which plants a named Layout flag wherever
 * it lands (shared with the Layout Wand via {@code FlagMarker}). Draw time scales the
 * launch speed exactly like a vanilla bow.
 */
public class FlagBowItem extends BowItem {
	/** Minimum draw before a shot registers (matches vanilla's 0.1 gate). */
	private static final float MIN_PULL = 0.1f;
	/** Peak launch speed multiplier at full draw (vanilla bow uses 3.0). */
	private static final float MAX_SPEED = 3.0f;
	/** Small spread so shots feel bow-like without being oppressive. */
	private static final float DIVERGENCE = 1.0f;

	public FlagBowItem(Settings settings) {
		super(settings);
	}

	/**
	 * Always start drawing — the Flag Bow has infinite ammo, so unlike a vanilla bow
	 * it never fails the "no arrows" check.
	 */
	@Override
	public ActionResult use(World world, PlayerEntity user, Hand hand) {
		user.setCurrentHand(hand);
		return ActionResult.CONSUME;
	}

	/**
	 * On release, spawn a single flag arrow aimed along the player's look, with a
	 * launch speed proportional to how long the bow was drawn.
	 */
	@Override
	public boolean onStoppedUsing(ItemStack stack, World world, LivingEntity user, int remainingUseTicks) {
		if (!(user instanceof PlayerEntity player)) {
			return false;
		}
		int drawTicks = this.getMaxUseTime(stack, user) - remainingUseTicks;
		float pull = getPullProgress(drawTicks);
		if (pull < MIN_PULL) {
			return false;
		}
		if (world instanceof ServerWorld serverWorld) {
			FlagArrowEntity arrow = new FlagArrowEntity(ModEntities.FLAG_ARROW, player, serverWorld);
			arrow.setVelocity(player, player.getPitch(), player.getYaw(), 0.0f, pull * MAX_SPEED, DIVERGENCE);
			if (pull == 1.0f) {
				arrow.setCritical(true);
			}
			serverWorld.spawnEntity(arrow);
		}
		world.playSound(null, player.getX(), player.getY(), player.getZ(),
			SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.PLAYERS, 1.0f,
			1.0f / (world.getRandom().nextFloat() * 0.4f + 1.2f) + pull * 0.5f);
		return true;
	}
}
