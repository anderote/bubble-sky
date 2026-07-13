package net.bubblesky.towerdefense.entity;

import java.util.List;
import java.util.UUID;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.entity.EntityData;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Shared base for the HIREABLE ALLIED roster — friendly biped mobs the player buys
 * with coins to fight the enemy waves. They are the mirror image of
 * {@link TdEnemyEntity}: instead of marching at the base, they hunt and kill the
 * wave's {@link TdEnemyEntity} mobs.
 *
 * <p>Behaviour is driven by an {@link Order} stored on the entity (persisted in
 * NBT so a squad survives a reload):
 * <ul>
 *   <li>{@code HOLD}  — leash to {@link #getAnchor()}, fight enemies that come within
 *       {@link #ENGAGE_HOLD} of it, then return.</li>
 *   <li>{@code ATTACK} — advance on the nearest enemy anywhere in the arena (falling
 *       back to marching at the spawn gates) and fight.</li>
 *   <li>{@code FOLLOW} — trail {@link #getOwner()} (the hiring player), fighting
 *       enemies that stray near.</li>
 *   <li>{@code MOVE}   — path to {@link #getAnchor()} then settle into {@code HOLD}
 *       there.</li>
 * </ul>
 *
 * <p>Target acquisition ({@link AllyTargetGoal}) and order-driven movement
 * ({@link AllyOrderGoal}) are custom goals; the actual approach-and-swing / shoot is
 * left to vanilla {@code MeleeAttackGoal} / {@code ProjectileAttackGoal} added by the
 * concrete {@link TdFootman} / {@link TdAllyArcher} subclasses. Allies are tagged
 * {@link #ALLY_TAG} (so the {@code WaveManager} never counts them as wave enemies)
 * and made persistent on spawn.
 */
public abstract class TdAllyEntity extends PathAwareEntity {
	/** Scoreboard/command tag marking an entity as a friendly TD ally. */
	public static final String ALLY_TAG = "td_ally";

	/** How far from its HOLD anchor an ally will still engage an enemy (blocks). */
	public static final double ENGAGE_HOLD = 12.0;
	/** How far from its HOLD anchor an ally will chase a target before breaking off. */
	public static final double LEASH_HOLD = 18.0;
	/** Enemy detection radius while FOLLOW/MOVE (blocks around the ally itself). */
	public static final double ENGAGE_NEAR = 12.0;
	/** Enemy detection radius while ATTACK (blocks) — effectively the whole arena. */
	public static final double ENGAGE_ATTACK = 48.0;

	/** The four simple orders an ally obeys. */
	public enum Order { HOLD, ATTACK, FOLLOW, MOVE }

	private Order order = Order.FOLLOW;
	/** Anchor point for HOLD / MOVE (block coords packed as world position). */
	@Nullable
	private Vec3d anchor;
	/** The player who hired / commands this ally (for FOLLOW). */
	@Nullable
	private UUID owner;

	protected TdAllyEntity(EntityType<? extends TdAllyEntity> type, World world) {
		super(type, world);
	}

	@Override
	protected void initGoals() {
		this.goalSelector.add(0, new SwimGoal(this));
		// 2 = combat (added by subclass), 3 = order-driven movement fallback.
		this.goalSelector.add(3, new AllyOrderGoal(this));
		this.goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		this.goalSelector.add(8, new LookAroundGoal(this));

		this.targetSelector.add(2, new AllyTargetGoal(this));
	}

	// ---- loadout / uniforms ------------------------------------------------
	/**
	 * Kit the ally out on spawn: give it its role's armour + weapon, lock the gear
	 * so it never drops as loot, and hand it a small standing-army armour bonus so a
	 * squad can actually weather a wave. Runs on {@code spawn()} (the hire path) but
	 * not on NBT reload, so the gear rolled here persists across saves.
	 */
	@Override
	public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty,
			SpawnReason spawnReason, @Nullable EntityData entityData) {
		EntityData data = super.initialize(world, difficulty, spawnReason, entityData);
		equipLoadout();
		// Uniforms are standard-issue kit, not battlefield loot — never drop them.
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			this.setEquipmentDropChance(slot, 0.0f);
		}
		// A modest flat bonus on top of the equipped plate so allies survive waves.
		EntityAttributeInstance armor = this.getAttributeInstance(EntityAttributes.ARMOR);
		if (armor != null) {
			armor.setBaseValue(armor.getBaseValue() + 2.0);
		}
		return data;
	}

	/** Equip this ally's themed armour + weapon. Overridden per role. */
	protected void equipLoadout() {
	}

	/** A leather armour piece tinted with the house colour via the DYED_COLOR component. */
	protected static ItemStack dyed(Item item, int rgb) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(rgb));
		return stack;
	}

	// ---- order state -------------------------------------------------------
	public Order getOrder() {
		return order;
	}

	@Nullable
	public Vec3d getAnchor() {
		return anchor;
	}

	@Nullable
	public UUID getOwner() {
		return owner;
	}

	public void setOwner(@Nullable UUID owner) {
		this.owner = owner;
	}

	/** Set the order plus its anchor/owner context in one shot. */
	public void setOrder(Order order, @Nullable Vec3d anchor, @Nullable UUID owner) {
		this.order = order;
		this.anchor = anchor;
		if (owner != null) {
			this.owner = owner;
		}
		// Drop any current target so the new order re-acquires under its own rules.
		this.setTarget(null);
		this.getNavigation().stop();
	}

	/** Resolve the owning player if they are loaded in this world, else null. */
	@Nullable
	public PlayerEntity resolveOwner() {
		if (owner == null) {
			return null;
		}
		return this.getWorld().getPlayerByUuid(owner);
	}

	// ---- targeting helpers (shared with the ally goals) --------------------
	/** True if this ally is currently allowed to engage {@code enemy} under its order. */
	public boolean canEngage(LivingEntity enemy) {
		if (enemy == null || !enemy.isAlive()) {
			return false;
		}
		switch (order) {
			case HOLD -> {
				if (anchor == null) {
					return enemy.squaredDistanceTo(this) <= ENGAGE_NEAR * ENGAGE_NEAR;
				}
				double d2 = enemy.squaredDistanceTo(anchor.x, anchor.y, anchor.z);
				return d2 <= LEASH_HOLD * LEASH_HOLD;
			}
			case ATTACK -> {
				return enemy.squaredDistanceTo(this) <= ENGAGE_ATTACK * ENGAGE_ATTACK;
			}
			case FOLLOW, MOVE -> {
				return enemy.squaredDistanceTo(this) <= ENGAGE_NEAR * ENGAGE_NEAR;
			}
			default -> {
				return false;
			}
		}
	}

	/** Find the nearest engageable enemy under the current order, or null. */
	@Nullable
	public TdEnemyEntity findEnemyTarget() {
		double radius = switch (order) {
			case ATTACK -> ENGAGE_ATTACK;
			case HOLD -> LEASH_HOLD + 4.0;
			default -> ENGAGE_NEAR;
		};
		// Centre the scan on the anchor for HOLD (so a leashed ally still spots
		// intruders near its post), otherwise on the ally itself.
		double cx = this.getX();
		double cy = this.getY();
		double cz = this.getZ();
		if (order == Order.HOLD && anchor != null) {
			cx = anchor.x;
			cy = anchor.y;
			cz = anchor.z;
		}
		Box box = new Box(cx - radius, cy - radius, cz - radius,
			cx + radius, cy + radius, cz + radius);
		List<TdEnemyEntity> found = this.getWorld().getEntitiesByClass(
			TdEnemyEntity.class, box, e -> e.isAlive() && canEngage(e));
		TdEnemyEntity best = null;
		double bestSq = Double.MAX_VALUE;
		for (TdEnemyEntity e : found) {
			double d2 = e.squaredDistanceTo(this);
			if (d2 < bestSq) {
				bestSq = d2;
				best = e;
			}
		}
		return best;
	}

	// ---- persistence -------------------------------------------------------
	@Override
	protected void writeCustomData(WriteView view) {
		super.writeCustomData(view);
		view.putString("TdOrder", order.name());
		if (anchor != null) {
			view.putInt("TdAnchorX", (int) Math.floor(anchor.x));
			view.putInt("TdAnchorY", (int) Math.floor(anchor.y));
			view.putInt("TdAnchorZ", (int) Math.floor(anchor.z));
		}
		if (owner != null) {
			view.putString("TdOwner", owner.toString());
		}
	}

	@Override
	protected void readCustomData(ReadView view) {
		super.readCustomData(view);
		this.order = parseOrder(view.getString("TdOrder", "FOLLOW"));
		if (view.getOptionalInt("TdAnchorX").isPresent()) {
			this.anchor = new Vec3d(
				view.getInt("TdAnchorX", 0) + 0.5,
				view.getInt("TdAnchorY", 0),
				view.getInt("TdAnchorZ", 0) + 0.5);
		} else {
			this.anchor = null;
		}
		view.getOptionalString("TdOwner").ifPresent(s -> {
			try {
				this.owner = UUID.fromString(s);
			} catch (IllegalArgumentException ignored) {
				this.owner = null;
			}
		});
	}

	private static Order parseOrder(String name) {
		try {
			return Order.valueOf(name);
		} catch (IllegalArgumentException e) {
			return Order.FOLLOW;
		}
	}
}
