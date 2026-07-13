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
	/** Total coins invested in this tower (buy price + upgrades); drives the sell refund. */
	protected int invested = 0;
	/** Kills credited to this tower; drives veterancy rank. */
	protected int veterancyKills = 0;

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

	/** Set the tier directly (used when placing a pre-upgraded tower). Clamped 1..MAX_TIER. */
	public void setTier(int newTier) {
		this.tier = Math.max(1, Math.min(MAX_TIER, newTier));
		markDirty();
	}

	public void setPlacer(UUID id) {
		this.placer = id;
		markDirty();
	}

	/** The UUID of the player who placed/last-upgraded this tower, or null. */
	@Nullable
	public UUID getPlacerUuid() {
		return placer;
	}

	public int getInvested() {
		return invested;
	}

	public void addInvested(int coins) {
		invested += coins;
		markDirty();
	}

	public void setInvested(int coins) {
		invested = coins;
		markDirty();
	}

	public double displayRange() {
		return range();
	}

	public int displayCooldownTicks() {
		return cooldownTicks();
	}

	public double displayDamageMultiplier() {
		return damageMultiplier();
	}

	// ---- veterancy (kill-earned rank) --------------------------------------
	/** Kills to earn one veterancy rank. */
	private static final int KILLS_PER_RANK = 20;
	/** COMPOUNDING damage factor per rank (~3.5%/rank, applied geometrically) — a slow gain
	 *  that keeps growing the longer a turret survives. */
	private static final double VETERANCY_COMPOUND = 1.035;
	/** Rank cap: 20 ranks — a long grind ({@code 20 * KILLS_PER_RANK} = 400 kills to max) that
	 *  compounds to ~2x damage, +3 range and ~20% faster fire. */
	public static final int MAX_VETERANCY = 20;

	/** Current veterancy rank 0..MAX_VETERANCY: one rank per {@link #KILLS_PER_RANK} kills. */
	public int getVeterancy() {
		return Math.min(MAX_VETERANCY, veterancyKills / KILLS_PER_RANK);
	}

	public int getKills() {
		return veterancyKills;
	}

	/** Kills still needed to reach the next rank (0 if maxed). */
	public int killsToNextVeterancy() {
		return getVeterancy() >= MAX_VETERANCY ? 0 : KILLS_PER_RANK - (veterancyKills % KILLS_PER_RANK);
	}

	/** Veterancy damage multiplier: COMPOUNDING ~3.5% per rank (1.0 at rank 0 → ~2x at the
	 *  20-rank cap) — a slow, long-term reward for a long-lived turret. */
	private double veterancyMult() {
		return Math.pow(VETERANCY_COMPOUND, getVeterancy());
	}

	/**
	 * Deal damage to a mob AND, if this tower's damage killed it, credit a veterancy kill.
	 * Every tower's fire()/DoT MUST route damage through here (instead of calling
	 * mob.damage directly) so kills are attributed to the right tower. Returns whether
	 * the damage was applied (mirrors LivingEntity#damage).
	 */
	public boolean damageAndCredit(ServerWorld world, net.minecraft.entity.LivingEntity mob,
			net.minecraft.entity.damage.DamageSource source, float amount) {
		boolean wasAlive = mob.isAlive();
		boolean applied = mob.damage(world, source, amount);
		if (wasAlive && !mob.isAlive()) {
			creditKill(world);
		}
		return applied;
	}

	/** Credit one veterancy kill to this tower (rank-up feedback fires if it advances a rank).
	 *  Used both by {@link #damageAndCredit} and by projectile towers whose bolt lands the kill. */
	public void creditKill(ServerWorld world) {
		int before = getVeterancy();
		veterancyKills++;
		markDirty();
		if (getVeterancy() > before) {
			onVeterancyRankUp(world);
		}
	}

	private void onVeterancyRankUp(ServerWorld world) {
		BlockPos p = getPos();
		world.playSound(null, p, net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP,
			net.minecraft.sound.SoundCategory.BLOCKS, 0.7f, 1.4f);
		world.spawnParticles(net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER,
			p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5, 18, 0.4, 0.4, 0.4, 0.05);
		ServerPlayerEntity owner = placerPlayer(world);
		if (owner != null) {
			owner.sendMessage(net.minecraft.text.Text.literal(
				kind().id().replace("_tower", "") + " tower reached veterancy " + getVeterancy() + "!")
				.formatted(net.minecraft.util.Formatting.GOLD), true);
		}
	}

	/** +3 blocks of range per tier above 1, plus +0.15 per veterancy rank (up to +3 at rank 20). */
	protected double range() {
		return baseRange() + (tier - 1) * 3.0 + 0.15 * getVeterancy();
	}

	/** Cooldown shrinks ~15% per tier (multiplicative, so it never reaches zero): a
	 *  tier-6 tower fires at ~44% of its base cadence. Veterancy shaves off ~1%/rank (up to
	 *  ~20% at the 20-rank cap). */
	protected int cooldownTicks() {
		double vet = 1.0 - 0.01 * getVeterancy();
		return Math.max(1, (int) Math.round(baseCooldown() * Math.pow(0.85, tier - 1) * vet));
	}

	/** Damage/effect multiplier: LINEAR +0.4 per tier — 1.0 / 1.4 / 1.8 / 2.2 / 2.6 / 3.0 for
	 *  tiers 1-6 (nerfed from the old x1.5 geometric so a maxed tower isn't overpowered).
	 *  Veterancy stacks a further up-to-~2x on top (at the 400-kill rank cap). */
	protected double damageMultiplier() {
		return (1.0 + 0.4 * (tier - 1)) * veterancyMult();
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
		this.invested = Math.max(0, view.getInt("invested", 0));
		this.veterancyKills = Math.max(0, view.getInt("vet_kills", 0));
	}

	@Override
	protected void writeData(WriteView view) {
		super.writeData(view);
		view.putInt("tier", tier);
		if (placer != null) {
			view.putString("placer", placer.toString());
		}
		view.putInt("invested", invested);
		view.putInt("vet_kills", veterancyKills);
	}
}
