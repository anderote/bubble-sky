package net.bubblesky.towerdefense.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * The combat projectile fired by the ARROW and BALL towers: a <em>real</em> arrow
 * (so it visually reads as an arrow, not a snowball like the frost/cannon towers)
 * that deals its owner-credited damage plus a knockback shove on the enemy it hits,
 * then <strong>vanishes almost immediately</strong> so the arena never fills with
 * lingering, pickup-able arrows.
 *
 * <p>Cloned from {@link TowerArrowEntity}'s structure, but purely ephemeral and
 * combat-oriented rather than a "shoot-to-place" builder. Its whole life cycle is
 * built around <em>disappearing fast</em>:
 * <ul>
 *   <li><b>Renders as a vanilla arrow</b> — {@link #getDefaultItemStack()} is an
 *       {@code ARROW}, drawn by {@code TowerBoltEntityRenderer}.</li>
 *   <li><b>Never sticks / never picks up</b> — {@code pickupType} is
 *       {@link PickupPermission#DISALLOWED} and {@link #onBlockHit} calls
 *       {@link #discard()} instead of embedding in the ground.</li>
 *   <li><b>Deals damage on entity hit</b> — {@link #onEntityHit} lets the vanilla
 *       arrow logic apply the owner-credited hit (so tower kills pay coins), applies
 *       a knockback shove, then {@link #discard()}s.</li>
 *   <li><b>Self-destructs on a miss</b> — {@link #tick()} discards the bolt once it
 *       has lived {@link #MAX_LIFE} ticks, so even an arrow that flies off into the
 *       air is gone in under a second.</li>
 * </ul>
 *
 * <p>Nothing meaningful is persisted (it lives well under a second); the inherited
 * damage value is the only interesting state and is written by the superclass.
 */
public class TowerBoltEntity extends PersistentProjectileEntity {

	/**
	 * Lifespan cap in ticks (20 ticks = 1 second). A fired bolt that hits nothing is
	 * force-discarded after this, so you SEE the arrow fly out and then it is gone —
	 * no accumulation on the ground.
	 */
	private static final int MAX_LIFE = 16;

	/**
	 * Horizontal shove applied to a struck living target, in blocks/tick of impulse —
	 * roughly equivalent to a Punch&nbsp;I enchantment (0.6). Knockback is pushed along
	 * the bolt's flight direction so enemies get shoved back the way the arrow came.
	 */
	private static final double KNOCKBACK_STRENGTH = 0.6;

	/** Ticks this bolt has been alive; drives the {@link #MAX_LIFE} self-destruct. */
	private int lifeTicks;

	/** Position of the tower that fired this bolt, so a kill credits that tower's veterancy. */
	@org.jetbrains.annotations.Nullable
	private net.minecraft.util.math.BlockPos towerPos;

	/** Record which tower fired this bolt (for veterancy kill credit). */
	public void setTowerPos(net.minecraft.util.math.BlockPos pos) {
		this.towerPos = pos;
	}

	/** Registry-factory constructor (used by {@code EntityType} + client spawns). */
	public TowerBoltEntity(EntityType<? extends TowerBoltEntity> type, World world) {
		super(type, world);
		this.pickupType = PickupPermission.DISALLOWED;
	}

	/**
	 * Muzzle constructor: spawns the bolt at an arbitrary world position (the tower's
	 * muzzle cx/cy/cz) rather than from a living shooter's eye. The owner is credited
	 * separately via {@link #setOwner} so tower kills still pay coins.
	 */
	public TowerBoltEntity(EntityType<? extends TowerBoltEntity> type, World world,
			double x, double y, double z) {
		super(type, x, y, z, world, new ItemStack(Items.ARROW), null);
		this.pickupType = PickupPermission.DISALLOWED;
	}

	@Override
	protected ItemStack getDefaultItemStack() {
		return new ItemStack(Items.ARROW);
	}

	@Override
	protected void onBlockHit(BlockHitResult blockHitResult) {
		// Never embed in the ground and never leave a pickup — a block hit just ends it.
		this.discard();
	}

	@Override
	protected void onEntityHit(EntityHitResult entityHitResult) {
		// Let the vanilla arrow logic apply the owner-credited damage (arrow damage
		// source with our owner as attacker), so a tower kill still credits coins.
		boolean wasAlive = entityHitResult.getEntity() instanceof LivingEntity le && le.isAlive();
		// Stamp the enemy so the WarlordDirector telemetry attributes a bolt kill to a tower
		// (see TdEnemyEntity#lastTowerHitAge); set before super applies the (lethal) hit.
		if (entityHitResult.getEntity() instanceof TdEnemyEntity te) {
			te.lastTowerHitAge = te.age;
		}
		super.onEntityHit(entityHitResult);
		// If our bolt just killed the target, credit the firing tower's veterancy.
		if (wasAlive && towerPos != null
				&& entityHitResult.getEntity() instanceof LivingEntity killed && !killed.isAlive()
				&& this.getWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld
				&& serverWorld.getBlockEntity(towerPos)
					instanceof net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity tower) {
			tower.creditKill(serverWorld);
		}
		// Punch-style shove: push the struck living entity along the flight direction.
		if (entityHitResult.getEntity() instanceof LivingEntity living) {
			Vec3d flat = this.getVelocity().multiply(1.0, 0.0, 1.0);
			if (flat.lengthSquared() > 1.0e-6) {
				Vec3d dir = flat.normalize();
				// takeKnockback shoves opposite to the passed (x, z), so negate the
				// flight vector to push the target the way the arrow was travelling.
				living.takeKnockback(KNOCKBACK_STRENGTH, -dir.x, -dir.z);
			}
		}
		// Ephemeral: gone the instant it lands a hit, so nothing ever piles up.
		this.discard();
	}

	@Override
	public void tick() {
		super.tick();
		// Self-destruct on a miss so a stray bolt disappears almost instantly.
		if (++lifeTicks >= MAX_LIFE) {
			this.discard();
		}
	}

	@Override
	protected void writeCustomData(WriteView view) {
		super.writeCustomData(view);
		view.putInt("LifeTicks", lifeTicks);
	}

	@Override
	protected void readCustomData(ReadView view) {
		super.readCustomData(view);
		this.lifeTicks = view.getInt("LifeTicks", 0);
	}
}
