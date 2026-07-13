package net.bubblesky.towerdefense.item;

import net.bubblesky.towerdefense.entity.FlagArrowEntity;
import net.bubblesky.towerdefense.entity.TowerArrowEntity;
import net.bubblesky.towerdefense.progression.ProgressLookup;
import net.bubblesky.towerdefense.registry.ModEntities;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.world.World;

/**
 * The unified Tower Defense bow — the single bow every player carries. One item, three
 * behaviours, all decided at release from how the player is drawing:
 *
 * <ul>
 *   <li><b>Normal draw + release</b> → fires a real combat {@link ArrowEntity}
 *       (consuming one arrow, owned by the shooter so kills pay coins).</li>
 *   <li><b>A "tower arrow" ({@link TowerArrowItem}) is loaded</b> → normal release
 *       instead fires a {@link TowerArrowEntity} that builds that tower where it lands
 *       (the "buy → shoot to place" flow); the tower arrow is consumed.</li>
 *   <li><b>Sneak (shift) + release</b> → plants a Layout flag via an ammo-less
 *       {@link FlagArrowEntity} (folds in the retired Flag Bow).</li>
 * </ul>
 *
 * <p>Like the old Flag Bow it always starts drawing (so sneak-to-flag never needs ammo);
 * the combat branch is the only one that needs an arrow in the pack.
 */
public class TdBowItem extends BowItem {
	/** Minimum draw before a shot registers (matches vanilla's 0.1 gate). */
	private static final float MIN_PULL = 0.1f;
	/** Peak launch speed multiplier at full draw (vanilla bow uses 3.0). */
	private static final float MAX_SPEED = 3.0f;
	/** Small spread so shots feel bow-like without being oppressive. */
	private static final float DIVERGENCE = 1.0f;
	/** Vanilla base damage of a bow-fired arrow — the anchor the Marksmanship multiplier scales. */
	private static final double BASE_ARROW_DAMAGE = 2.0;

	public TdBowItem(Settings settings) {
		super(settings);
	}

	/** Always start drawing — infinite draw so sneak-to-flag works without ammo. */
	@Override
	public ActionResult use(World world, PlayerEntity user, Hand hand) {
		user.setCurrentHand(hand);
		return ActionResult.CONSUME;
	}

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

		// Sneaking always plants a flag (ammo-less), no matter what's loaded.
		if (player.isSneaking()) {
			if (world instanceof ServerWorld serverWorld) {
				FlagArrowEntity arrow = new FlagArrowEntity(ModEntities.FLAG_ARROW, player, serverWorld);
				launch(arrow, player, pull);
				serverWorld.spawnEntity(arrow);
			}
			playShootSound(world, player, pull);
			return true;
		}

		// Non-sneak: a loaded tower arrow takes priority (shoot-to-place a tower).
		int towerSlot = findTowerArrowSlot(player);
		if (towerSlot >= 0) {
			TowerArrowItem towerArrow = (TowerArrowItem) player.getInventory().getStack(towerSlot).getItem();
			if (world instanceof ServerWorld serverWorld) {
				TowerArrowEntity arrow = new TowerArrowEntity(ModEntities.TOWER_ARROW, player, serverWorld, towerArrow.kind());
				launch(arrow, player, pull);
				serverWorld.spawnEntity(arrow);
			}
			if (!player.getAbilities().creativeMode) {
				player.getInventory().getStack(towerSlot).decrement(1);
			}
			playShootSound(world, player, pull);
			return true;
		}

		// Otherwise, normal combat: needs a real arrow (unless creative).
		boolean creative = player.getAbilities().creativeMode;
		int arrowSlot = creative ? -1 : findArrowSlot(player);
		if (!creative && arrowSlot < 0) {
			return false; // no ammo — nothing happens
		}
		if (world instanceof ServerWorld serverWorld) {
			ArrowEntity arrow = new ArrowEntity(serverWorld, player, new ItemStack(Items.ARROW), stack);
			arrow.setVelocity(player, player.getPitch(), player.getYaw(), 0.0f, pull * MAX_SPEED, DIVERGENCE);
			// RPG Marksmanship: scale the fired arrow's damage by the shooter's bow multiplier
			// (1.0 = vanilla base, +6% per point). Set from the vanilla base so mult=1.0 is a
			// no-op / no regression.
			arrow.setDamage(BASE_ARROW_DAMAGE * ProgressLookup.bowMult(player));
			if (pull == 1.0f) {
				arrow.setCritical(true);
			}
			arrow.pickupType = creative
				? PersistentProjectileEntity.PickupPermission.CREATIVE_ONLY
				: PersistentProjectileEntity.PickupPermission.ALLOWED;
			serverWorld.spawnEntity(arrow);
		}
		if (!creative && arrowSlot >= 0) {
			player.getInventory().getStack(arrowSlot).decrement(1);
		}
		playShootSound(world, player, pull);
		return true;
	}

	/** Aim a non-combat projectile (flag/tower) along the player's look with bow-like speed. */
	private static void launch(PersistentProjectileEntity arrow, PlayerEntity player, float pull) {
		arrow.setVelocity(player, player.getPitch(), player.getYaw(), 0.0f, pull * MAX_SPEED, DIVERGENCE);
		if (pull == 1.0f) {
			arrow.setCritical(true);
		}
	}

	private static void playShootSound(World world, PlayerEntity player, float pull) {
		world.playSound(null, player.getX(), player.getY(), player.getZ(),
			SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.PLAYERS, 1.0f,
			1.0f / (world.getRandom().nextFloat() * 0.4f + 1.2f) + pull * 0.5f);
	}

	/** First inventory slot holding a tower arrow, or -1. */
	private static int findTowerArrowSlot(PlayerEntity player) {
		DefaultedList<ItemStack> stacks = player.getInventory().getMainStacks();
		for (int i = 0; i < stacks.size(); i++) {
			if (stacks.get(i).getItem() instanceof TowerArrowItem) {
				return i;
			}
		}
		return -1;
	}

	/** First inventory slot holding a plain arrow, or -1. */
	private static int findArrowSlot(PlayerEntity player) {
		DefaultedList<ItemStack> stacks = player.getInventory().getMainStacks();
		for (int i = 0; i < stacks.size(); i++) {
			if (stacks.get(i).isOf(Items.ARROW)) {
				return i;
			}
		}
		return -1;
	}
}
