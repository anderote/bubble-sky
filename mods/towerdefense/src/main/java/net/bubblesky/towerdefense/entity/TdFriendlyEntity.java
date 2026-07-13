package net.bubblesky.towerdefense.entity;

import java.util.UUID;
import net.bubblesky.towerdefense.registry.ModEntities;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.ProjectileAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.WanderAroundPointOfInterestGoal;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/** A persistent hired defender that fights TD enemies and patrols near its rally point. */
public class TdFriendlyEntity extends PathAwareEntity implements RangedAttackMob {
	private static final int RALLY_RADIUS = 12;
	@Nullable private UUID owner;
	private BlockPos rallyPoint;

	public TdFriendlyEntity(EntityType<? extends TdFriendlyEntity> type, World world) {
		super(type, world);
		this.rallyPoint = BlockPos.ORIGIN;
	}

	@Override
	protected void initGoals() {
		if (isRanged()) {
			this.goalSelector.add(2, new ProjectileAttackGoal(this, 1.0, 30, 15.0f));
		} else {
			this.goalSelector.add(2, new MeleeAttackGoal(this, 1.1, true));
		}
		this.goalSelector.add(6, new WanderAroundPointOfInterestGoal(this, 0.8, false));
		this.goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		this.goalSelector.add(8, new LookAroundGoal(this));
		this.targetSelector.add(1, new RevengeGoal(this));
		this.targetSelector.add(2, new ActiveTargetGoal<>(this, TdEnemyEntity.class, true));
	}

	public void assign(UUID owner, BlockPos rallyPoint) {
		this.owner = owner;
		this.rallyPoint = rallyPoint.toImmutable();
		this.setPositionTarget(this.rallyPoint, RALLY_RADIUS);
		this.setPersistent();
		this.setCustomNameVisible(true);
	}

	@Nullable
	public UUID getOwnerUuid() {
		return owner;
	}

	private boolean isRanged() {
		return getType() == ModEntities.HIRED_ARCHER || getType() == ModEntities.HIRED_WIZARD;
	}

	@Override
	public void shootAt(LivingEntity target, float pullProgress) {
		if (!(getWorld() instanceof ServerWorld world)) return;
		PlayerEntity playerOwner = owner == null ? null : world.getServer().getPlayerManager().getPlayer(owner);
		if (getType() == ModEntities.HIRED_WIZARD) {
			float damage = 8.0f;
			target.damage(world, playerOwner != null
				? world.getDamageSources().playerAttack(playerOwner)
				: world.getDamageSources().magic(), damage);
			world.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_EVOKER_CAST_SPELL,
				SoundCategory.PLAYERS, 0.8f, 1.3f);
			return;
		}
		ArrowEntity arrow = new ArrowEntity(world, this, new ItemStack(Items.ARROW), ItemStack.EMPTY);
		if (playerOwner != null) arrow.setOwner(playerOwner);
		double dx = target.getX() - getX();
		double dy = target.getBodyY(0.4) - arrow.getY();
		double dz = target.getZ() - getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		arrow.setVelocity(dx, dy + horizontal * 0.2, dz, 1.6f, 5.0f);
		arrow.setDamage(5.0);
		world.spawnEntity(arrow);
		world.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_ARROW_SHOOT,
			SoundCategory.PLAYERS, 0.8f, 1.1f);
	}

	@Override
	public void readData(ReadView view) {
		super.readData(view);
		String rawOwner = view.getString("td_owner", "");
		try { owner = rawOwner.isEmpty() ? null : UUID.fromString(rawOwner); }
		catch (IllegalArgumentException ignored) { owner = null; }
		rallyPoint = BlockPos.fromLong(view.getLong("td_rally", BlockPos.ORIGIN.asLong()));
		setPositionTarget(rallyPoint, RALLY_RADIUS);
	}

	@Override
	public void writeData(WriteView view) {
		super.writeData(view);
		if (owner != null) view.putString("td_owner", owner.toString());
		view.putLong("td_rally", rallyPoint.asLong());
	}
}
