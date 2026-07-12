package net.bubblesky.towerdefense.entity;

import net.bubblesky.towerdefense.layout.FlagMarker;
import net.bubblesky.towerdefense.layout.LayoutStore;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The projectile fired by the {@code FlagBowItem}: a flying arrow that, wherever it
 * strikes a block, plants a named Layout flag there (marker + label + a record in the
 * shared {@link LayoutStore}) via {@link FlagMarker#plantFlag} — exactly what the
 * Layout Wand does on a shift-right-click — then vanishes.
 *
 * <p>It never drops a pickup arrow (pickup stays {@code DISALLOWED}) and does not
 * damage entities: an entity hit simply discards the arrow harmlessly.
 */
public class FlagArrowEntity extends PersistentProjectileEntity {

	/** Registry-factory constructor (used by {@code EntityType} + client spawns). */
	public FlagArrowEntity(EntityType<? extends FlagArrowEntity> type, World world) {
		super(type, world);
	}

	/** Fired-by-player constructor: positions the arrow at the shooter's eye. */
	public FlagArrowEntity(EntityType<? extends FlagArrowEntity> type, LivingEntity owner, World world) {
		super(type, owner, world, new ItemStack(Items.ARROW), null);
	}

	@Override
	protected ItemStack getDefaultItemStack() {
		return new ItemStack(Items.ARROW);
	}

	@Override
	protected void onBlockHit(BlockHitResult blockHitResult) {
		if (this.getWorld() instanceof ServerWorld world) {
			// Plant on the empty cell adjacent to the struck face — same rule the
			// wand uses (clicked block + side), so a flag lands ON TOP of ground.
			BlockPos pos = blockHitResult.getBlockPos().offset(blockHitResult.getSide());
			String letter = FlagMarker.initialOf(ownerName());
			String name = LayoutStore.nextFlagName(letter);
			FlagMarker.plantFlag(world, pos, name, FlagMarker.dimensionId(world));
			messageShooter(Text.literal("Flag ")
				.append(Text.literal(name).formatted(Formatting.AQUA))
				.append(Text.literal(String.format(" planted at (%d, %d, %d)",
					pos.getX(), pos.getY(), pos.getZ()))));
		}
		this.discard();
	}

	@Override
	protected void onEntityHit(EntityHitResult entityHitResult) {
		// Flag arrows are non-combat: ignore the hit and disappear.
		this.discard();
	}

	/** Name of the shooter (for auto-naming the flag), or null if not a player. */
	private String ownerName() {
		Entity owner = this.getOwner();
		return owner != null ? owner.getName().getString() : null;
	}

	private void messageShooter(Text text) {
		if (this.getOwner() instanceof PlayerEntity player) {
			player.sendMessage(text, false);
		}
	}
}
