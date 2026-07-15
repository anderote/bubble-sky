package net.bubblesky.towerdefense.spell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.bubblesky.towerdefense.colony.ColonistEntity;
import net.bubblesky.towerdefense.entity.TdAllyEntity;
import net.bubblesky.towerdefense.progression.ProgressLookup;
import net.bubblesky.towerdefense.registry.ModBlocks;
import net.bubblesky.towerdefense.registry.ModEntities;
import net.bubblesky.towerdefense.tower.TowerKind;
import net.bubblesky.towerdefense.tower.TowerStructure;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * The data-driven registry of every castable SPELL. Each constant bundles a stable
 * {@link #id() id}, a display name, a {@link #manaCost() mana cost}, a
 * {@link #cooldownTicks() cooldown}, and — via the abstract {@link #cast} hook — the
 * server-side effect itself. A {@link SpellItem} holds exactly one {@code SpellType}
 * (chosen at registration, mirroring how {@code TowerArrowItem} carries a
 * {@code TowerKind}) and delegates its right-click to {@link #cast} after spending mana
 * and starting the item cooldown.
 *
 * <h2>Twelve spells, three per class</h2>
 * The set mirrors the four class loadouts (see {@code ClassLoadout} / the design's class
 * table):
 * <ul>
 *   <li><b>Mage</b> — {@link #FIREBALL}, {@link #FROST_NOVA}, {@link #CHAIN_LIGHTNING}.</li>
 *   <li><b>Ranger</b> — {@link #MULTISHOT}, {@link #TRAP}, {@link #SUMMON_WOLF}.</li>
 *   <li><b>Engineer</b> — {@link #DEPLOY_TURRET}, {@link #REPAIR_PULSE}, {@link #WALL_OF_ACID}.</li>
 *   <li><b>Warlord</b> — {@link #WAR_CRY}, {@link #SUMMON_SQUAD}, {@link #CHARGE}.</li>
 * </ul>
 *
 * <h2>Damage attribution</h2>
 * Every point of spell damage is dealt through {@link #casterDamage} — the caster's own
 * {@code playerAttack} {@link DamageSource} — so the existing kill hooks (coin drops in
 * {@code TowerDefenseMod}, global + active-class XP in {@code ProgressEvents}) credit the
 * caster exactly as a melee/bow kill would. Autonomous summons (wolves, squads) fight on
 * their own and are the one exception (their kills are un-attributed, like hired allies).
 *
 * <h2>Enemy selection</h2>
 * Spells target {@link HostileEntity} — the common supertype of the whole TD enemy roster
 * ({@code TdEnemyEntity}) — matching how the towers pick targets. Friendly summons
 * ({@code TdAllyEntity}) and colonists are {@code PathAwareEntity}/creatures, so they are
 * never caught by offensive AoE.
 *
 * <p>The ordinal order is not persisted (spell items key on the enum constant chosen at
 * registration and on {@link #id()} strings), but keep it stable for readability.
 */
public enum SpellType {

	// ================= MAGE =================
	/**
	 * A hitscan fireball: streaks along the caster's aim to the first block/surface it
	 * meets, then bursts for {@link #FIREBALL_DAMAGE} fire damage to every enemy within
	 * {@link #FIREBALL_RADIUS} of the impact, setting them alight. The travel is drawn as a
	 * dense flame trail so it reads as a thrown bolt without needing a bespoke projectile
	 * entity.
	 */
	FIREBALL("fireball", "Fireball", 12, 40) {
		@Override
		public void cast(ServerWorld world, ServerPlayerEntity caster, Vec3d aim) {
			Vec3d start = caster.getEyePos();
			Vec3d center = impactPoint(caster, FIREBALL_RANGE);
			flameTrail(world, start, center, ParticleTypes.FLAME);
			world.spawnParticles(ParticleTypes.EXPLOSION, center.x, center.y, center.z, 3, 0.4, 0.4, 0.4, 0.0);
			world.spawnParticles(ParticleTypes.FLAME, center.x, center.y, center.z,
				40, FIREBALL_RADIUS * 0.5, FIREBALL_RADIUS * 0.5, FIREBALL_RADIUS * 0.5, 0.02);
			damageEnemies(world, caster, center, FIREBALL_RADIUS, FIREBALL_DAMAGE, 4.0f);
			world.playSound(null, center.x, center.y, center.z, SoundEvents.ENTITY_BLAZE_SHOOT,
				SoundCategory.PLAYERS, 0.9f, 0.8f);
		}
	},

	/**
	 * A point-blank frost blast centred on the caster: every enemy within
	 * {@link #NOVA_RADIUS} takes {@link #NOVA_DAMAGE} and is chilled with Slowness for
	 * {@link #NOVA_SLOW_TICKS} ticks. Purely defensive crowd-control to buy space.
	 */
	FROST_NOVA("frost_nova", "Frost Nova", 14, 60) {
		@Override
		public void cast(ServerWorld world, ServerPlayerEntity caster, Vec3d aim) {
			Vec3d center = caster.getPos();
			world.spawnParticles(ParticleTypes.SNOWFLAKE, center.x, center.y + 1.0, center.z,
				60, NOVA_RADIUS * 0.5, 0.6, NOVA_RADIUS * 0.5, 0.05);
			DamageSource src = casterDamage(world, caster);
			for (HostileEntity mob : enemiesInRadius(world, center, NOVA_RADIUS)) {
				mob.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, NOVA_SLOW_TICKS, 2));
				mob.damage(world, src, NOVA_DAMAGE);
			}
			world.playSound(null, center.x, center.y, center.z, SoundEvents.BLOCK_GLASS_BREAK,
				SoundCategory.PLAYERS, 0.9f, 1.6f);
		}
	},

	/**
	 * A lightning arc: strikes the nearest enemy within {@link #CHAIN_RANGE}, then jumps to
	 * up to {@link #CHAIN_MAX_JUMPS} further enemies (each within {@link #CHAIN_HOP} of the
	 * previous), dealing {@link #CHAIN_DAMAGE} that decays by {@link #CHAIN_FALLOFF} per
	 * hop. Each hit gets a cosmetic bolt so the chain reads at a glance.
	 */
	CHAIN_LIGHTNING("chain_lightning", "Chain Lightning", 16, 70) {
		@Override
		public void cast(ServerWorld world, ServerPlayerEntity caster, Vec3d aim) {
			HostileEntity target = nearestEnemy(world, caster.getPos(), CHAIN_RANGE, null);
			if (target == null) {
				world.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
					SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.5f, 1.8f);
				return;
			}
			DamageSource src = casterDamage(world, caster);
			List<HostileEntity> visited = new ArrayList<>();
			HostileEntity current = target;
			float damage = CHAIN_DAMAGE;
			for (int hop = 0; hop <= CHAIN_MAX_JUMPS && current != null; hop++) {
				cosmeticBolt(world, caster, current.getX(), current.getBodyY(0.0), current.getZ());
				current.damage(world, src, damage);
				visited.add(current);
				damage *= CHAIN_FALLOFF;
				current = nearestEnemyExcluding(world, current.getPos(), CHAIN_HOP, visited);
			}
			world.playSound(null, target.getX(), target.getY(), target.getZ(),
				SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.6f, 1.5f);
		}
	},

	// ================= RANGER =================
	/**
	 * Loose a fan of {@link #MULTISHOT_ARROWS} caster-owned combat arrows spread across
	 * {@link #MULTISHOT_SPREAD} degrees of yaw around the aim. Each arrow's damage tracks
	 * the caster's Marksmanship bow multiplier, so kills pay coins/XP just like a bow shot.
	 */
	MULTISHOT("multishot", "Multishot", 10, 30) {
		@Override
		public void cast(ServerWorld world, ServerPlayerEntity caster, Vec3d aim) {
			double bowMult = ProgressLookup.bowMult(caster);
			int n = MULTISHOT_ARROWS;
			float half = MULTISHOT_SPREAD * 0.5f;
			for (int i = 0; i < n; i++) {
				float yawOffset = n == 1 ? 0f : (-half + (MULTISHOT_SPREAD * i) / (n - 1));
				ArrowEntity arrow = new ArrowEntity(world, caster, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
				arrow.setVelocity(caster, caster.getPitch(), caster.getYaw() + yawOffset, 0.0f, 3.0f, 1.0f);
				arrow.setDamage(2.0 * bowMult);
				arrow.pickupType = PersistentProjectileEntity.PickupPermission.CREATIVE_ONLY;
				world.spawnEntity(arrow);
			}
			castSound(world, caster, SoundEvents.ENTITY_ARROW_SHOOT, 1.0f);
		}
	},

	/**
	 * Plant a hidden SNARE at the block the caster is looking at (via {@link SpellManager}).
	 * The first enemy to wander within {@link SpellManager#TRAP_TRIGGER_RADIUS} of it is
	 * rooted (heavy Slowness) and takes {@link SpellManager#TRAP_DAMAGE} damage attributed
	 * to the caster; the trap then springs and is consumed. Untriggered traps expire after
	 * {@link SpellManager#TRAP_LIFETIME_TICKS} ticks.
	 */
	TRAP("trap", "Snare Trap", 8, 80) {
		@Override
		public void cast(ServerWorld world, ServerPlayerEntity caster, Vec3d aim) {
			BlockPos pos = lookedAtBlock(caster, 24.0);
			SpellManager.addTrap(world, pos, caster.getUuid());
			world.spawnParticles(ParticleTypes.CRIT, pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5,
				10, 0.3, 0.1, 0.3, 0.0);
			world.playSound(null, pos, SoundEvents.BLOCK_TRIPWIRE_CLICK_ON, SoundCategory.PLAYERS, 0.6f, 1.2f);
		}
	},

	/**
	 * Summon {@link #WOLF_COUNT} temporary wolves at the caster's feet, set upon the nearest
	 * enemy and left to hunt. They are registered with {@link SpellManager} and vanish after
	 * {@link SpellManager#SUMMON_LIFETIME_TICKS} ticks so the battlefield never fills up.
	 */
	SUMMON_WOLF("summon_wolf", "Summon Wolves", 14, 140) {
		@Override
		public void cast(ServerWorld world, ServerPlayerEntity caster, Vec3d aim) {
			HostileEntity prey = nearestEnemy(world, caster.getPos(), 24.0, null);
			for (int i = 0; i < WOLF_COUNT; i++) {
				BlockPos at = caster.getBlockPos().add(i - WOLF_COUNT / 2, 0, 0);
				WolfEntity wolf = EntityType.WOLF.spawn(world, at, SpawnReason.EVENT);
				if (wolf == null) {
					continue;
				}
				wolf.setPersistent();
				if (prey != null) {
					wolf.setTarget(prey);
				}
				SpellManager.addSummon(wolf);
			}
			castSound(world, caster, SoundEvents.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f);
		}
	},

	// ================= ENGINEER =================
	/**
	 * Deploy a caster-owned Arrow tower on the surface the caster is looking at (placed in
	 * the empty cell against the targeted face, like a player-built tower). Owner is stamped
	 * so its kills still pay the caster; nothing else is deducted beyond the mana.
	 */
	DEPLOY_TURRET("deploy_turret", "Deploy Turret", 25, 200) {
		@Override
		public void cast(ServerWorld world, ServerPlayerEntity caster, Vec3d aim) {
			BlockPos place = placementTarget(caster, 12.0);
			if (place == null) {
				actionbar(caster, "No surface to deploy on.");
				return;
			}
			TowerStructure.build(world, place, TowerKind.ARROW, caster.getUuid());
			world.spawnParticles(ParticleTypes.CLOUD, place.getX() + 0.5, place.getY() + 0.5, place.getZ() + 0.5,
				20, 0.3, 0.3, 0.3, 0.02);
			world.playSound(null, place, SoundEvents.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.8f, 1.4f);
		}
	},

	/**
	 * A restorative pulse: heals the caster and every friendly (other players,
	 * {@link TdAllyEntity} summons/hires, and {@link ColonistEntity} colonists) within
	 * {@link #REPAIR_RADIUS} by {@link #REPAIR_AMOUNT} hearts of health.
	 */
	REPAIR_PULSE("repair_pulse", "Repair Pulse", 14, 100) {
		@Override
		public void cast(ServerWorld world, ServerPlayerEntity caster, Vec3d aim) {
			Vec3d center = caster.getPos();
			caster.heal(REPAIR_AMOUNT);
			for (LivingEntity ally : friendliesInRadius(world, caster, center, REPAIR_RADIUS)) {
				ally.heal(REPAIR_AMOUNT);
			}
			world.spawnParticles(ParticleTypes.HEART, center.x, center.y + 1.0, center.z,
				12, REPAIR_RADIUS * 0.5, 0.6, REPAIR_RADIUS * 0.5, 0.0);
			castSound(world, caster, SoundEvents.ENTITY_PLAYER_LEVELUP, 0.7f);
		}
	},

	/**
	 * Lay a short WALL of acid across the aim direction, a few blocks in front of the caster:
	 * {@link #ACID_WALL_WIDTH} source blocks of {@link ModBlocks#ACID} spanning the line
	 * perpendicular to the look. The fluid then spreads/corrodes exactly like any acid pool.
	 */
	WALL_OF_ACID("wall_of_acid", "Wall of Acid", 18, 120) {
		@Override
		public void cast(ServerWorld world, ServerPlayerEntity caster, Vec3d aim) {
			Vec3d flat = new Vec3d(aim.x, 0, aim.z).normalize();
			if (flat.lengthSquared() < 1.0e-4) {
				flat = new Vec3d(0, 0, 1);
			}
			Vec3d perp = new Vec3d(-flat.z, 0, flat.x);
			Vec3d origin = caster.getPos().add(flat.multiply(2.0));
			int half = ACID_WALL_WIDTH / 2;
			int placed = 0;
			for (int i = -half; i <= half; i++) {
				Vec3d spot = origin.add(perp.multiply(i));
				BlockPos base = BlockPos.ofFloored(spot.x, caster.getY(), spot.z);
				BlockPos ground = surfaceFor(world, base);
				if (world.getBlockState(ground).isReplaceable()) {
					world.setBlockState(ground, ModBlocks.ACID.getDefaultState());
					placed++;
				}
			}
			if (placed > 0) {
				world.playSound(null, caster.getBlockPos(), SoundEvents.BLOCK_SLIME_BLOCK_PLACE,
					SoundCategory.PLAYERS, 0.9f, 0.9f);
			}
		}
	},

	// ================= WARLORD =================
	/**
	 * A rallying cry: grants the caster and every friendly within {@link #WARCRY_RADIUS}
	 * Strength + Speed for {@link #WARCRY_DURATION_TICKS} ticks.
	 */
	WAR_CRY("war_cry", "War Cry", 12, 150) {
		@Override
		public void cast(ServerWorld world, ServerPlayerEntity caster, Vec3d aim) {
			Vec3d center = caster.getPos();
			buff(caster);
			for (LivingEntity ally : friendliesInRadius(world, caster, center, WARCRY_RADIUS)) {
				buff(ally);
			}
			world.spawnParticles(ParticleTypes.ANGRY_VILLAGER, center.x, center.y + 1.5, center.z,
				20, WARCRY_RADIUS * 0.5, 0.6, WARCRY_RADIUS * 0.5, 0.0);
			castSound(world, caster, SoundEvents.ENTITY_RAVAGER_ROAR, 1.0f);
		}

		private void buff(LivingEntity e) {
			e.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, WARCRY_DURATION_TICKS, 1));
			e.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, WARCRY_DURATION_TICKS, 0));
		}
	},

	/**
	 * Rally a temporary SQUAD of {@link #SQUAD_FOOTMEN} allied footmen and
	 * {@link #SQUAD_ARCHERS} allied archer, ordered to ATTACK and owned by the caster. They
	 * are registered with {@link SpellManager} and disband after
	 * {@link SpellManager#SUMMON_LIFETIME_TICKS} ticks.
	 */
	SUMMON_SQUAD("summon_squad", "Summon Squad", 22, 220) {
		@Override
		public void cast(ServerWorld world, ServerPlayerEntity caster, Vec3d aim) {
			for (int i = 0; i < SQUAD_FOOTMEN; i++) {
				spawnAlly(world, caster, ModEntities.ALLY_FOOTMAN);
			}
			for (int i = 0; i < SQUAD_ARCHERS; i++) {
				spawnAlly(world, caster, ModEntities.ALLY_ARCHER);
			}
			castSound(world, caster, SoundEvents.ENTITY_RAVAGER_ROAR, 0.8f);
		}

		private void spawnAlly(ServerWorld world, ServerPlayerEntity caster, EntityType<? extends TdAllyEntity> type) {
			TdAllyEntity ally = type.spawn(world, caster.getBlockPos(), SpawnReason.EVENT);
			if (ally == null) {
				return;
			}
			ally.addCommandTag(TdAllyEntity.ALLY_TAG);
			ally.setPersistent();
			ally.setOrder(TdAllyEntity.Order.ATTACK, null, caster.getUuid());
			SpellManager.addSummon(ally);
		}
	},

	/**
	 * A heroic CHARGE: launches the caster forward along the aim at {@link #CHARGE_SPEED} and
	 * smashes every enemy within {@link #CHARGE_RADIUS} for {@link #CHARGE_DAMAGE}, knocking
	 * them back. A gap-closing melee opener.
	 */
	CHARGE("charge", "Charge", 14, 60) {
		@Override
		public void cast(ServerWorld world, ServerPlayerEntity caster, Vec3d aim) {
			Vec3d flat = new Vec3d(aim.x, 0, aim.z).normalize();
			if (flat.lengthSquared() < 1.0e-4) {
				flat = new Vec3d(0, 0, 1);
			}
			Vec3d dash = flat.multiply(CHARGE_SPEED).add(0, 0.35, 0);
			caster.setVelocity(dash);
			caster.velocityModified = true;
			DamageSource src = casterDamage(world, caster);
			for (HostileEntity mob : enemiesInRadius(world, caster.getPos(), CHARGE_RADIUS)) {
				mob.damage(world, src, CHARGE_DAMAGE);
				mob.takeKnockback(1.2, caster.getX() - mob.getX(), caster.getZ() - mob.getZ());
			}
			world.spawnParticles(ParticleTypes.SWEEP_ATTACK, caster.getX(), caster.getY() + 1.0, caster.getZ(),
				6, 0.6, 0.4, 0.6, 0.0);
			castSound(world, caster, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f);
		}
	};

	// ================= per-spell tuning constants =================
	private static final double FIREBALL_RANGE = 28.0;
	private static final double FIREBALL_RADIUS = 3.0;
	private static final float FIREBALL_DAMAGE = 8.0f;

	private static final double NOVA_RADIUS = 5.0;
	private static final float NOVA_DAMAGE = 4.0f;
	private static final int NOVA_SLOW_TICKS = 100;

	private static final double CHAIN_RANGE = 20.0;
	private static final double CHAIN_HOP = 6.0;
	private static final int CHAIN_MAX_JUMPS = 3;
	private static final float CHAIN_DAMAGE = 7.0f;
	private static final float CHAIN_FALLOFF = 0.75f;

	private static final int MULTISHOT_ARROWS = 5;
	private static final float MULTISHOT_SPREAD = 35.0f;

	private static final int WOLF_COUNT = 2;

	private static final double REPAIR_RADIUS = 8.0;
	private static final float REPAIR_AMOUNT = 8.0f;

	private static final int ACID_WALL_WIDTH = 5;

	private static final double WARCRY_RADIUS = 10.0;
	private static final int WARCRY_DURATION_TICKS = 160;

	private static final int SQUAD_FOOTMEN = 2;
	private static final int SQUAD_ARCHERS = 1;

	private static final double CHARGE_SPEED = 1.6;
	private static final double CHARGE_RADIUS = 3.5;
	private static final float CHARGE_DAMAGE = 6.0f;

	// ================= instance fields =================
	private final String id;
	private final String displayName;
	private final int manaCost;
	private final int cooldownTicks;

	SpellType(String id, String displayName, int manaCost, int cooldownTicks) {
		this.id = id;
		this.displayName = displayName;
		this.manaCost = manaCost;
		this.cooldownTicks = cooldownTicks;
	}

	/** The spell's effect. Server-thread only; {@code aim} is the caster's unit look vector. */
	public abstract void cast(ServerWorld world, ServerPlayerEntity caster, Vec3d aim);

	/** Stable lowercase id (e.g. {@code "fireball"}) used for the item name + lang key. */
	public String id() {
		return id;
	}

	/** Human-facing display name (e.g. {@code "Fireball"}). */
	public String displayName() {
		return displayName;
	}

	/** Mana this spell costs to cast. */
	public int manaCost() {
		return manaCost;
	}

	/** Cooldown, in ticks, applied to the spell item after a successful cast. */
	public int cooldownTicks() {
		return cooldownTicks;
	}

	/** Resolve a spell by its {@link #id()} (case-insensitive), or {@code null} if unknown. */
	public static SpellType fromId(String id) {
		if (id == null) {
			return null;
		}
		String needle = id.toLowerCase(Locale.ROOT);
		for (SpellType s : values()) {
			if (s.id.equals(needle)) {
				return s;
			}
		}
		return null;
	}

	// ================= shared cast helpers =================
	/**
	 * The caster-owned {@link DamageSource} every offensive spell routes damage through, so
	 * the existing coin/XP kill hooks credit the caster (attacker resolves to the player).
	 */
	static DamageSource casterDamage(ServerWorld world, ServerPlayerEntity caster) {
		return world.getDamageSources().playerAttack(caster);
	}

	/** All live enemies (any {@link HostileEntity}) whose centre lies within {@code radius} of {@code center}. */
	static List<HostileEntity> enemiesInRadius(ServerWorld world, Vec3d center, double radius) {
		Box box = new Box(center.x - radius, center.y - radius, center.z - radius,
			center.x + radius, center.y + radius, center.z + radius);
		List<HostileEntity> found = new ArrayList<>();
		double r2 = radius * radius;
		for (HostileEntity mob : world.getNonSpectatingEntities(HostileEntity.class, box)) {
			if (mob.isAlive() && mob.squaredDistanceTo(center.x, center.y, center.z) <= r2) {
				found.add(mob);
			}
		}
		return found;
	}

	/**
	 * Damage every enemy within {@code radius} of {@code center} for {@code damage},
	 * optionally setting them on fire for {@code fireSeconds}. All damage is caster-credited.
	 */
	static void damageEnemies(ServerWorld world, ServerPlayerEntity caster, Vec3d center,
			double radius, float damage, float fireSeconds) {
		DamageSource src = casterDamage(world, caster);
		for (HostileEntity mob : enemiesInRadius(world, center, radius)) {
			if (fireSeconds > 0) {
				mob.setOnFireFor(fireSeconds);
			}
			mob.damage(world, src, damage);
		}
	}

	/** Nearest enemy to {@code center} within {@code radius}, optionally excluding one entity. */
	static HostileEntity nearestEnemy(ServerWorld world, Vec3d center, double radius, HostileEntity exclude) {
		HostileEntity best = null;
		double bestSq = Double.MAX_VALUE;
		for (HostileEntity mob : enemiesInRadius(world, center, radius)) {
			if (mob == exclude) {
				continue;
			}
			double d2 = mob.squaredDistanceTo(center.x, center.y, center.z);
			if (d2 < bestSq) {
				bestSq = d2;
				best = mob;
			}
		}
		return best;
	}

	/** Nearest enemy within {@code radius} that is not already in {@code visited} (for chaining). */
	private static HostileEntity nearestEnemyExcluding(ServerWorld world, Vec3d center, double radius,
			List<HostileEntity> visited) {
		HostileEntity best = null;
		double bestSq = Double.MAX_VALUE;
		for (HostileEntity mob : enemiesInRadius(world, center, radius)) {
			if (visited.contains(mob)) {
				continue;
			}
			double d2 = mob.squaredDistanceTo(center.x, center.y, center.z);
			if (d2 < bestSq) {
				bestSq = d2;
				best = mob;
			}
		}
		return best;
	}

	/**
	 * Every friendly {@link LivingEntity} within {@code radius} of {@code center} that a
	 * support spell should touch: other players, {@link TdAllyEntity} allies/summons, and
	 * {@link ColonistEntity} colonists (the casting player is handled by the caller).
	 */
	static List<LivingEntity> friendliesInRadius(ServerWorld world, ServerPlayerEntity caster,
			Vec3d center, double radius) {
		Box box = new Box(center.x - radius, center.y - radius, center.z - radius,
			center.x + radius, center.y + radius, center.z + radius);
		List<LivingEntity> found = new ArrayList<>();
		double r2 = radius * radius;
		for (LivingEntity e : world.getNonSpectatingEntities(LivingEntity.class, box)) {
			if (!e.isAlive() || e == caster) {
				continue;
			}
			boolean friendly = e instanceof PlayerEntity || e instanceof TdAllyEntity || e instanceof ColonistEntity;
			if (friendly && e.squaredDistanceTo(center.x, center.y, center.z) <= r2) {
				found.add(e);
			}
		}
		return found;
	}

	/** The world point the caster's aim first meets a surface (or the reach endpoint on a miss). */
	private static Vec3d impactPoint(ServerPlayerEntity caster, double range) {
		HitResult hit = caster.raycast(range, 1.0f, false);
		return hit.getPos();
	}

	/** The block position the caster is looking at (the hit block, or the reach endpoint on a miss). */
	private static BlockPos lookedAtBlock(ServerPlayerEntity caster, double range) {
		HitResult hit = caster.raycast(range, 1.0f, false);
		if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
			return bhr.getBlockPos();
		}
		Vec3d p = hit.getPos();
		return BlockPos.ofFloored(p.x, p.y, p.z);
	}

	/**
	 * The empty cell a deployed tower should occupy: the block against the targeted face
	 * (so it sits on the ground / sticks to a wall), or {@code null} if the caster is aiming
	 * at open air.
	 */
	private static BlockPos placementTarget(ServerPlayerEntity caster, double range) {
		HitResult hit = caster.raycast(range, 1.0f, false);
		if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
			return bhr.getBlockPos().offset(bhr.getSide());
		}
		return null;
	}

	/** Walk down from {@code base} to the first non-replaceable block, returning the cell just above it. */
	private static BlockPos surfaceFor(ServerWorld world, BlockPos base) {
		BlockPos p = base;
		for (int i = 0; i < 4; i++) {
			BlockPos below = p.down();
			if (!world.getBlockState(below).isReplaceable()) {
				return p;
			}
			p = below;
		}
		return p;
	}

	/** Draw a straight line of particles from {@code from} to {@code to} to fake a projectile streak. */
	private static void flameTrail(ServerWorld world, Vec3d from, Vec3d to, net.minecraft.particle.ParticleEffect particle) {
		Vec3d delta = to.subtract(from);
		double dist = delta.length();
		int steps = (int) Math.min(40, Math.max(4, dist * 2));
		for (int i = 0; i <= steps; i++) {
			double t = i / (double) steps;
			Vec3d p = from.add(delta.multiply(t));
			world.spawnParticles(particle, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/** Spawn a purely-cosmetic (no fire/damage) lightning bolt for the chain-lightning arc. */
	private static void cosmeticBolt(ServerWorld world, ServerPlayerEntity caster, double x, double y, double z) {
		LightningEntity bolt = new LightningEntity(EntityType.LIGHTNING_BOLT, world);
		bolt.setPosition(x, y, z);
		bolt.setCosmetic(true);
		bolt.setChanneler(caster);
		world.spawnEntity(bolt);
		world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, x, y + 1.0, z, 12, 0.3, 0.6, 0.3, 0.2);
	}

	/** Play a spell's cast sound at the caster. */
	private static void castSound(ServerWorld world, ServerPlayerEntity caster, net.minecraft.sound.SoundEvent sound, float volume) {
		world.playSound(null, caster.getX(), caster.getY(), caster.getZ(), sound, SoundCategory.PLAYERS, volume, 1.0f);
	}

	/** Send a red action-bar note to the caster (used for "can't place here" style feedback). */
	private static void actionbar(ServerPlayerEntity caster, String message) {
		caster.sendMessage(net.minecraft.text.Text.literal(message).formatted(net.minecraft.util.Formatting.RED), true);
	}
}
