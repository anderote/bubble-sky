package net.bubblesky.towerdefense.entity;

import net.bubblesky.towerdefense.tower.TowerKind;
import net.bubblesky.towerdefense.tower.TowerStructure;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The "shoot-to-place" projectile: fired from the TD bow when a {@code TowerArrowItem}
 * is loaded. Flies like a normal arrow, and wherever it strikes a block it places the
 * single-block tower against the struck face via {@link TowerStructure} (ground or wall),
 * crediting the shooter as the tower's placer, then vanishes.
 *
 * <p>Non-combat: it never drops a pickup arrow (pickup stays {@code DISALLOWED}) and an
 * entity hit simply discards it harmlessly. The {@link TowerKind} is persisted so an
 * in-flight arrow survives a save/reload.
 */
public class TowerArrowEntity extends PersistentProjectileEntity {

	private TowerKind kind = TowerKind.ARROW;

	/** Registry-factory constructor (used by {@code EntityType} + client spawns). */
	public TowerArrowEntity(EntityType<? extends TowerArrowEntity> type, World world) {
		super(type, world);
	}

	/** Fired-by-player constructor: positions the arrow at the shooter's eye. */
	public TowerArrowEntity(EntityType<? extends TowerArrowEntity> type, LivingEntity owner, World world, TowerKind kind) {
		super(type, owner, world, new ItemStack(Items.ARROW), null);
		this.kind = kind;
	}

	public void setKind(TowerKind kind) {
		this.kind = kind;
	}

	@Override
	protected ItemStack getDefaultItemStack() {
		return new ItemStack(Items.ARROW);
	}

	@Override
	protected void onBlockHit(BlockHitResult blockHitResult) {
		if (this.getWorld() instanceof ServerWorld world) {
			// Place the single-block tower on the empty cell adjacent to the struck face,
			// so it embeds against the block the arrow hit — ground or a vertical wall.
			BlockPos base = blockHitResult.getBlockPos().offset(blockHitResult.getSide());
			BlockPos core = TowerStructure.build(world, base, kind, ownerUuid());
			messageShooter(Text.literal(prettyName())
				.formatted(Formatting.GREEN)
				.append(Text.literal(String.format(" raised at (%d, %d, %d)",
					core.getX(), core.getY(), core.getZ())).formatted(Formatting.GRAY)));
		}
		this.discard();
	}

	@Override
	protected void onEntityHit(EntityHitResult entityHitResult) {
		// Tower arrows are non-combat: ignore the hit and disappear (no tower built).
		this.discard();
	}

	@Override
	protected void writeCustomData(WriteView view) {
		super.writeCustomData(view);
		view.putInt("TowerKind", kind.ordinal());
	}

	@Override
	protected void readCustomData(ReadView view) {
		super.readCustomData(view);
		this.kind = TowerKind.fromOrdinal(view.getInt("TowerKind", 0));
	}

	private java.util.UUID ownerUuid() {
		Entity owner = this.getOwner();
		return owner != null ? owner.getUuid() : null;
	}

	private String prettyName() {
		String n = kind.id().replace('_', ' ');
		return Character.toUpperCase(n.charAt(0)) + n.substring(1);
	}

	private void messageShooter(Text text) {
		if (this.getOwner() instanceof PlayerEntity player) {
			player.sendMessage(text, false);
		}
	}
}
