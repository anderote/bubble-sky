package net.bubblesky.towerdefense.blockentity;

import java.util.List;
import java.util.UUID;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Shared brains for every tower type. Subclasses supply their base range /
 * cooldown / fire behavior; this class owns the two economy features common to
 * all towers:
 *
 * <ul>
 *   <li><b>Tier (1-3)</b> — persisted upgrade level. Higher tiers extend range,
 *       shorten cooldown and multiply damage (see {@link #range()},
 *       {@link #cooldownTicks()}, {@link #damageMultiplier()}).</li>
 *   <li><b>Placer UUID</b> — the player who built the tower. Towers set this as
 *       the OWNER of the projectiles/damage they deal, so tower kills resolve to
 *       a player attacker and pay out coins via the existing kill hook.</li>
 * </ul>
 *
 * <p>1.21.6 persistence uses {@code readData(ReadView)}/{@code writeData(WriteView)};
 * NBT getters take an explicit default ({@code getInt(key, def)}).
 */
public abstract class AbstractTowerBlockEntity extends BlockEntity {
	public static final int MAX_TIER = 6;

	/** Upgrade level, 1..MAX_TIER. */
	protected int tier = 1;
	/** Player who placed this tower (owns its shots for coin credit). */
	@Nullable
	protected UUID placer = null;
	/** Ticks until this tower may fire again. */
	protected int cooldown = 0;

	protected AbstractTowerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	// ---- per-type tuning (subclass supplies base values) -------------------
	/** Which tower kind this is — drives the stick-structure palette on teardown. */
	public abstract net.bubblesky.towerdefense.tower.TowerKind kind();

	/** Base search/fire radius (blocks) at tier 1. */
	protected abstract double baseRange();

	/** Base ticks between shots at tier 1. */
	protected abstract int baseCooldown();

	/** Do the actual shooting at the chosen target. */
	protected abstract void fire(ServerWorld world, double cx, double cy, double cz, HostileEntity target);

	// ---- tier scaling ------------------------------------------------------
	public int getTier() {
		return tier;
	}

	/** Bump the tier by one. Returns false if already maxed. */
	public boolean upgrade() {
		if (tier >= MAX_TIER) {
			return false;
		}
		tier++;
		markDirty();
		return true;
	}

	public void setPlacer(UUID id) {
		this.placer = id;
		markDirty();
	}

	/** +3 blocks of range per tier above 1. */
	protected double range() {
		return baseRange() + (tier - 1) * 3.0;
	}

	/** Cooldown shrinks ~15% per tier (multiplicative, so it never reaches zero): a
	 *  tier-6 tower fires at ~44% of its base cadence. */
	protected int cooldownTicks() {
		return Math.max(1, (int) Math.round(baseCooldown() * Math.pow(0.85, tier - 1)));
	}

	/** Damage/effect multiplier: geometric x1.5 per tier — 1.0 / 1.5 / 2.25 / 3.38 / 5.06 /
	 *  7.59 for tiers 1-6, so a fully upgraded tower is severely powerful. */
	protected double damageMultiplier() {
		return Math.pow(1.5, tier - 1);
	}

	/** Resolve the placer to an online player, or null (offline / unset). */
	@Nullable
	protected ServerPlayerEntity placerPlayer(ServerWorld world) {
		if (placer == null) {
			return null;
		}
		return world.getServer().getPlayerManager().getPlayer(placer);
	}

	// ---- shared ticking / targeting ----------------------------------------
	/**
	 * Generic server ticker shared by every tower block. Wire it up in each
	 * block's {@code getTicker} via {@code validateTicker(type, TYPE, AbstractTowerBlockEntity::tick)}.
	 */
	public static <T extends AbstractTowerBlockEntity> void tick(World world, BlockPos pos, BlockState state, T be) {
		if (!(world instanceof ServerWorld serverWorld)) {
			return;
		}
		if (be.cooldown > 0) {
			be.cooldown--;
			return;
		}
		double cx = pos.getX() + 0.5;
		double cy = pos.getY() + 0.5;
		double cz = pos.getZ() + 0.5;
		HostileEntity target = be.findNearestHostile(serverWorld, pos, cx, cy, cz);
		if (target == null) {
			return;
		}
		// Muzzle offset: spawn the projectile just OUTSIDE the tower's orb on the side
		// facing the target, so steep/downward shots clear the tower's own blocks (the
		// pole + ball) instead of colliding with them and getting stuck.
		double dx = target.getX() - cx;
		double dy = target.getBodyY(0.5) - cy;
		double dz = target.getZ() - cz;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (dist > 1.0e-3) {
			double muzzle = be.kind().ballRadius() + 0.9;
			cx += dx / dist * muzzle;
			cy += dy / dist * muzzle;
			cz += dz / dist * muzzle;
		}
		be.fire(serverWorld, cx, cy, cz, target);
		be.cooldown = be.cooldownTicks();
	}

	@Nullable
	protected HostileEntity findNearestHostile(ServerWorld world, BlockPos pos, double cx, double cy, double cz) {
		double range = range();
		Box box = new Box(pos).expand(range);
		List<HostileEntity> mobs = world.getNonSpectatingEntities(HostileEntity.class, box);
		HostileEntity nearest = null;
		double bestSq = range * range;
		for (HostileEntity mob : mobs) {
			if (!mob.isAlive()) {
				continue;
			}
			double distSq = mob.squaredDistanceTo(cx, cy, cz);
			if (distSq <= bestSq) {
				bestSq = distSq;
				nearest = mob;
			}
		}
		return nearest;
	}

	// ---- persistence -------------------------------------------------------
	@Override
	protected void readData(ReadView view) {
		super.readData(view);
		this.tier = Math.min(MAX_TIER, Math.max(1, view.getInt("tier", 1)));
		String uuid = view.getString("placer", "");
		if (!uuid.isEmpty()) {
			try {
				this.placer = UUID.fromString(uuid);
			} catch (IllegalArgumentException e) {
				this.placer = null;
			}
		}
	}

	@Override
	protected void writeData(WriteView view) {
		super.writeData(view);
		view.putInt("tier", tier);
		if (placer != null) {
			view.putString("placer", placer.toString());
		}
	}
}
