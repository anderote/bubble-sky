package net.bubblesky.towerdefense.spell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity;
import net.bubblesky.towerdefense.colony.ColonistEntity;
import net.bubblesky.towerdefense.entity.TdAllyEntity;
import net.bubblesky.towerdefense.entity.TdSkeletonWarrior;
import net.bubblesky.towerdefense.progression.PlayerClass;
import net.bubblesky.towerdefense.progression.PlayerProgress;
import net.bubblesky.towerdefense.progression.ProgressLookup;
import net.bubblesky.towerdefense.progression.ProgressState;
import net.bubblesky.towerdefense.progression.StatModifiers;
import net.bubblesky.towerdefense.registry.ModBlocks;
import net.bubblesky.towerdefense.registry.ModEntities;
import net.bubblesky.towerdefense.tower.TowerKind;
import net.bubblesky.towerdefense.tower.TowerStructure;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
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
import net.minecraft.server.MinecraftServer;
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
 *   <li><b>Necromancer</b> — {@link #RAISE_DEAD}, {@link #SUMMON_SQUAD}, {@link #BONE_SPEAR}.</li>
 * </ul>
 *
 * <p>The former Warlord spells {@link #WAR_CRY} and {@link #CHARGE} are no longer in any
 * class loadout, but remain defined here (and their items registered) so old references and
 * saved item stacks keep resolving.
 *
 * <h2>Rank scaling (skill-tree hook)</h2>
 * {@link #rankOf(ServerPlayerEntity)} reports how many class points the caster has sunk into
 * THIS spell in their active class's {@link net.bubblesky.towerdefense.progression.ClassProgress}
 * allocation map. The class SKILL TREE that spends those points is a later phase; this method
 * is the upgrade hook it will feed. {@link #RAISE_DEAD}, {@link #BONE_SPEAR}, and (as a proof
 * of the pattern) {@link #FIREBALL} already read it to scale with rank.
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
	 * meets, then bursts for {@link #FIREBALL_DAMAGE} fire damage (scaled up by
	 * {@link #FIREBALL_RANK_DAMAGE_BONUS} per allocated {@link #rankOf rank}) to every enemy
	 * within {@link #FIREBALL_RADIUS} of the impact, setting them alight. The travel is drawn
	 * as a dense flame trail so it reads as a thrown bolt without needing a bespoke projectile
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
			// +15% damage per Fireball rank, PLUS +10% per Pyromancy passive rank.
			float damage = FIREBALL_DAMAGE * (1.0f + FIREBALL_RANK_DAMAGE_BONUS * rankOf(caster)
				+ PYROMANCY_DAMAGE_BONUS * casterPassiveRank(caster, "pyromancy"));
			damageEnemies(world, caster, center, FIREBALL_RADIUS, damage, 4.0f);
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
			// Rank + Frostbite passive both add +12% damage & +0.5s (10t) slow each.
			int steps = rankOf(caster) + casterPassiveRank(caster, "frostbite");
			float damage = NOVA_DAMAGE * (1.0f + NOVA_RANK_DAMAGE_BONUS * steps);
			int slowTicks = NOVA_SLOW_TICKS + NOVA_RANK_SLOW_TICKS * steps;
			for (HostileEntity mob : enemiesInRadius(world, center, NOVA_RADIUS)) {
				mob.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, slowTicks, 2));
				mob.damage(world, src, damage);
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
			// Rank: +1 jump and +10% damage per allocated Chain Lightning rank.
			int rank = rankOf(caster);
			int maxJumps = CHAIN_MAX_JUMPS + rank;
			float damage = CHAIN_DAMAGE * (1.0f + CHAIN_RANK_DAMAGE_BONUS * rank);
			for (int hop = 0; hop <= maxJumps && current != null; hop++) {
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
			int n = MULTISHOT_ARROWS + rankOf(caster); // +1 arrow per rank (Precision folds into bowMult)
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
			// Eagle Eye passive: +1 block of placement reach per rank.
			double reach = 24.0 + casterPassiveRank(caster, "eagle_eye");
			BlockPos pos = lookedAtBlock(caster, reach);
			// Trap rank scales the sprung damage (+15%) and root (+0.5s) per rank (see SpellManager).
			SpellManager.addTrap(world, pos, caster.getUuid(), rankOf(caster));
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
			int rank = rankOf(caster);
			int wolfCount = WOLF_COUNT + rank / 2; // +1 wolf every 2 ranks
			double wolfHpMult = 1.0 + WOLF_RANK_HP_BONUS * rank; // +10% wolf HP per rank
			for (int i = 0; i < wolfCount; i++) {
				BlockPos at = caster.getBlockPos().add(i - wolfCount / 2, 0, 0);
				WolfEntity wolf = EntityType.WOLF.spawn(world, at, SpawnReason.EVENT);
				if (wolf == null) {
					continue;
				}
				wolf.setPersistent();
				if (prey != null) {
					wolf.setTarget(prey);
				}
				// Wolf HP from rank; minion_mastery (necro) is a no-op for a Ranger caster.
				scaleMinion(caster, wolf, wolfHpMult, 1.0);
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
			// Deploy Turret rank raises the placed tower's tier (+1 per 2 ranks). The Engineer's
			// Overclock passive is folded in as EXTRA tier (+1 per 2 ranks) rather than touching
			// tower damage internals — a higher-tier turret hits harder, so it reads as "stronger
			// deployed turrets" while staying non-invasive. Capped at MAX_TIER. (Simplified —
			// see the class notes: Overclock maps its "+10%/rank damage" onto tier bumps.)
			int tier = Math.min(1 + rankOf(caster) / 2 + casterPassiveRank(caster, "overclock") / 2,
				AbstractTowerBlockEntity.MAX_TIER);
			TowerStructure.build(world, place, TowerKind.ARROW, caster.getUuid(), tier);
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
			// Repair rank + Tinkerer passive each add +1 heart (2 HP) of heal per rank; rank also
			// widens the pulse by +1 block per 2 ranks.
			int rank = rankOf(caster);
			int tinkerer = casterPassiveRank(caster, "tinkerer");
			float heal = REPAIR_AMOUNT + REPAIR_HEART_PER_RANK * (rank + tinkerer);
			double radius = REPAIR_RADIUS + rank / 2.0;
			caster.heal(heal);
			for (LivingEntity ally : friendliesInRadius(world, caster, center, radius)) {
				ally.heal(heal);
			}
			world.spawnParticles(ParticleTypes.HEART, center.x, center.y + 1.0, center.z,
				12, radius * 0.5, 0.6, radius * 0.5, 0.0);
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
			// Wall length grows +1 block per Wall-of-Acid rank AND +1 per Tinkerer passive rank.
			int width = ACID_WALL_WIDTH + rankOf(caster) + casterPassiveRank(caster, "tinkerer");
			int half = width / 2;
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

	// ================= NECROMANCER =================
	/**
	 * RAISE DEAD: reanimate the corpses of recently-slain enemies near the caster into melee
	 * {@link TdSkeletonWarrior} allies. Consumes up to {@code 1 + }{@link #rankOf} of the
	 * nearest un-consumed corpses (recorded by {@link SpellManager#recordCorpse} on every
	 * {@code td_enemy} death) within {@link #RAISE_DEAD_RADIUS}, raising one caster-owned
	 * warrior — ordered to ATTACK, persistent, and registered with {@link SpellManager} for a
	 * full {@link SpellManager#SKELETON_SUMMON_LIFETIME_TICKS} (5 minutes) — at each. If NO
	 * corpse is in range it still raises a single, slightly weaker warrior at the caster's feet
	 * so the spell never feels dead. Base one skeleton at rank 0, +1 per allocated rank.
	 */
	RAISE_DEAD("raise_dead", "Raise Dead", 18, 160) {
		@Override
		public void cast(ServerWorld world, ServerPlayerEntity caster, Vec3d aim) {
			int count = 1 + rankOf(caster);
			List<Vec3d> corpses = SpellManager.consumeCorpses(world, caster.getPos(), RAISE_DEAD_RADIUS, count);
			if (corpses.isEmpty()) {
				// Fallback: no corpses to work with — raise a single, weaker skeleton at the feet.
				TdSkeletonWarrior fallback = spawnSkeletonWarrior(world, caster, caster.getPos());
				if (fallback != null) {
					fallback.setHealth(fallback.getMaxHealth() * RAISE_DEAD_FALLBACK_HP_FRACTION);
				}
			} else {
				for (Vec3d pos : corpses) {
					spawnSkeletonWarrior(world, caster, pos);
				}
			}
			Vec3d c = caster.getPos();
			world.spawnParticles(ParticleTypes.SOUL, c.x, c.y + 0.5, c.z, 30, RAISE_DEAD_RADIUS * 0.3, 0.6, RAISE_DEAD_RADIUS * 0.3, 0.02);
			world.spawnParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y + 0.5, c.z, 20, RAISE_DEAD_RADIUS * 0.3, 0.4, RAISE_DEAD_RADIUS * 0.3, 0.01);
			world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.PLAYERS, 0.4f, 1.4f);
		}
	},

	/**
	 * BONE SPEAR: a piercing hitscan lance of bone along the caster's aim. Marches out to
	 * {@link #BONE_SPEAR_RANGE} blocks, damaging EVERY enemy within {@link #BONE_SPEAR_HALF_WIDTH}
	 * of the line exactly once for {@code BONE_SPEAR_BASE_DAMAGE + BONE_SPEAR_DAMAGE_PER_RANK *}
	 * {@link #rankOf} — a cheap, reliable, rank-scaling nuke. All damage is caster-credited so
	 * kills pay gold + XP. No projectile entity: it is a pure particle-drawn line.
	 */
	BONE_SPEAR("bone_spear", "Bone Spear", 10, 40) {
		@Override
		public void cast(ServerWorld world, ServerPlayerEntity caster, Vec3d aim) {
			Vec3d start = caster.getEyePos();
			Vec3d dir = aim.lengthSquared() < 1.0e-6 ? new Vec3d(0, 0, 1) : aim.normalize();
			// Bone Spear rank (+2 flat/rank) then the Amplify passive (+10%/rank) on top.
			float damage = (BONE_SPEAR_BASE_DAMAGE + BONE_SPEAR_DAMAGE_PER_RANK * rankOf(caster))
				* (1.0f + AMPLIFY_DAMAGE_BONUS * casterPassiveRank(caster, "amplify"));
			DamageSource src = casterDamage(world, caster);
			Set<HostileEntity> struck = new HashSet<>();
			int steps = (int) Math.ceil(BONE_SPEAR_RANGE);
			for (int i = 0; i <= steps; i++) {
				Vec3d p = start.add(dir.multiply(i));
				world.spawnParticles(ParticleTypes.SOUL, p.x, p.y, p.z, 1, 0.03, 0.03, 0.03, 0.0);
				world.spawnParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.0);
				for (HostileEntity mob : enemiesInRadius(world, p, BONE_SPEAR_HALF_WIDTH)) {
					if (struck.add(mob)) {
						mob.damage(world, src, damage);
					}
				}
			}
			castSound(world, caster, SoundEvents.ENTITY_SKELETON_HURT, 1.0f);
		}
	},

	/**
	 * Raise a squad of {@link #SKELETON_ARCHER_COUNT} beefy SKELETON ARCHERS
	 * ({@link ModEntities#ALLY_SKELETON}) at the caster's feet, ordered to ATTACK and
	 * owned by the caster (a Necromancer signature spell). Unlike the fleeting wolf summons, these undead bowmen are
	 * built to hold a line: they are registered with {@link SpellManager} for a full
	 * {@link SpellManager#SKELETON_SUMMON_LIFETIME_TICKS} ticks (5 minutes) before they
	 * crumble to dust. Each is a friendly {@link TdAllyEntity} under the hood — only its
	 * skin is skeletal — so the caster's own towers never mistake it for a wave enemy.
	 */
	SUMMON_SQUAD("summon_squad", "Summon Skeletons", 22, 220) {
		@Override
		public void cast(ServerWorld world, ServerPlayerEntity caster, Vec3d aim) {
			int rank = rankOf(caster);
			int count = SKELETON_ARCHER_COUNT + rank / 2; // +1 archer every 2 ranks
			double archerDmgMult = 1.0 + SQUAD_RANK_DAMAGE_BONUS * rank; // +10% archer damage per rank
			int lifetime = necroSummonLifetime(caster); // Unholy Vigor stretches the crumble timer
			for (int i = 0; i < count; i++) {
				TdAllyEntity ally = ModEntities.ALLY_SKELETON.spawn(world, caster.getBlockPos(), SpawnReason.EVENT);
				if (ally == null) {
					continue;
				}
				ally.addCommandTag(TdAllyEntity.ALLY_TAG);
				ally.setPersistent();
				ally.setOrder(TdAllyEntity.Order.ATTACK, null, caster.getUuid());
				// Squad rank buffs archer damage; minion_mastery adds HP+damage on top.
				scaleMinion(caster, ally, 1.0, archerDmgMult);
				SpellManager.addSummon(ally, lifetime);
			}
			castSound(world, caster, SoundEvents.ENTITY_SKELETON_AMBIENT, 1.0f);
		}
	},

	// ============ RETIRED (formerly Warlord; defined but no longer in any loadout) ============
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
	/** Fireball damage bonus per allocated rank (the rank-scaling proof): +15% each. */
	private static final float FIREBALL_RANK_DAMAGE_BONUS = 0.15f;
	/** Mage Pyromancy passive: +10% Fireball damage per rank. */
	private static final float PYROMANCY_DAMAGE_BONUS = 0.10f;

	// ---- rank / passive scaling constants for the remaining spells ----
	/** Frost Nova (rank + Frostbite passive): +12% damage per step. */
	private static final float NOVA_RANK_DAMAGE_BONUS = 0.12f;
	/** Frost Nova (rank + Frostbite passive): +0.5s (10 ticks) of slow per step. */
	private static final int NOVA_RANK_SLOW_TICKS = 10;
	/** Chain Lightning: +10% damage per rank (a jump is also added per rank in-cast). */
	private static final float CHAIN_RANK_DAMAGE_BONUS = 0.10f;
	/** Summon Wolves: +10% wolf max health per Summon Wolves rank. */
	private static final double WOLF_RANK_HP_BONUS = 0.10;
	/** Repair Pulse / Tinkerer: +1 heart (2 HP) of heal per rank. */
	private static final float REPAIR_HEART_PER_RANK = 2.0f;
	/** Summon Skeletons: +10% skeleton-archer damage per Summon Skeletons rank. */
	private static final double SQUAD_RANK_DAMAGE_BONUS = 0.10;
	/** Necromancer Amplify passive: +10% Bone Spear (and spell) damage per rank. */
	private static final float AMPLIFY_DAMAGE_BONUS = 0.10f;
	/** Necromancer Minion Mastery passive: +10% minion HP &amp; damage per rank. */
	private static final double MINION_MASTERY_BONUS = 0.10;
	/** Necromancer Unholy Vigor passive: +30s (600 ticks) minion lifetime per rank. */
	private static final int UNHOLY_VIGOR_TICKS_PER_RANK = 600;

	/** Radius (blocks) around the caster within which RAISE_DEAD reanimates enemy corpses. */
	private static final double RAISE_DEAD_RADIUS = 16.0;
	/** Health fraction of the lone fallback warrior raised when no corpse is in range (weaker). */
	private static final float RAISE_DEAD_FALLBACK_HP_FRACTION = 0.6f;

	/** Bone Spear reach (blocks) along the caster's aim. */
	private static final double BONE_SPEAR_RANGE = 18.0;
	/** How far off the aim line an enemy is still speared (blocks) — a narrow lance. */
	private static final double BONE_SPEAR_HALF_WIDTH = 1.5;
	/** Bone Spear base damage at rank 0. */
	private static final float BONE_SPEAR_BASE_DAMAGE = 7.0f;
	/** Additional Bone Spear damage per allocated rank. */
	private static final float BONE_SPEAR_DAMAGE_PER_RANK = 2.0f;

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

	/** How many skeleton archers the Necromancer's Summon Skeletons spell raises. */
	private static final int SKELETON_ARCHER_COUNT = 3;

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

	/**
	 * The caster's allocated RANK in THIS spell: how many class points they have sunk into
	 * this spell's {@link #id()} in their ACTIVE class's
	 * {@link net.bubblesky.towerdefense.progression.ClassProgress} allocation map. Returns
	 * {@code 0} for an unclassed caster, a caster with no points in this spell, or anything
	 * that can't be resolved server-side. This is the upgrade hook the later skill-tree phase
	 * feeds; spells read it to scale their effect with investment.
	 */
	protected int rankOf(ServerPlayerEntity caster) {
		MinecraftServer server = caster.getServer();
		if (server == null) {
			return 0;
		}
		PlayerProgress progress = ProgressState.get(server).forPlayer(caster.getUuid());
		PlayerClass active = progress.getActiveClass();
		if (active == null) {
			return 0;
		}
		return progress.classProgress(active).points(this.id());
	}

	/**
	 * Spawn one caster-owned {@link TdSkeletonWarrior} at {@code at}: tagged as an ally,
	 * made persistent, ordered to ATTACK, and registered with {@link SpellManager} for the
	 * 5-minute {@link SpellManager#SKELETON_SUMMON_LIFETIME_TICKS} lifetime. Returns the
	 * spawned warrior (or {@code null} if the spawn failed) so callers can tweak it (e.g. the
	 * weaker fallback warrior).
	 */
	private static TdSkeletonWarrior spawnSkeletonWarrior(ServerWorld world, ServerPlayerEntity caster, Vec3d at) {
		BlockPos pos = BlockPos.ofFloored(at.x, at.y, at.z);
		TdSkeletonWarrior ally = ModEntities.ALLY_SKELETON_WARRIOR.spawn(world, pos, SpawnReason.EVENT);
		if (ally == null) {
			return null;
		}
		ally.addCommandTag(TdAllyEntity.ALLY_TAG);
		ally.setPersistent();
		ally.setOrder(TdAllyEntity.Order.ATTACK, null, caster.getUuid());
		// Minion Mastery scales the raised warrior's HP + damage; Unholy Vigor lengthens its life.
		scaleMinion(caster, ally, 1.0, 1.0);
		SpellManager.addSummon(ally, necroSummonLifetime(caster));
		return ally;
	}

	// ================= passive helpers (read the active class's tree) =================
	/**
	 * The rank {@code caster} has in a PASSIVE skill of their ACTIVE class (0 when unclassed or
	 * unallocated, or off the server). Thin wrapper over
	 * {@link StatModifiers#passiveRank(PlayerProgress, String)} that resolves the caster's record.
	 */
	static int casterPassiveRank(ServerPlayerEntity caster, String passiveId) {
		MinecraftServer server = caster.getServer();
		if (server == null) {
			return 0;
		}
		PlayerProgress progress = ProgressState.get(server).forPlayer(caster.getUuid());
		return StatModifiers.passiveRank(progress, passiveId);
	}

	/**
	 * Buff a freshly-spawned {@code minion} for the caster: multiply its MAX_HEALTH by
	 * {@code extraHpMult} and ATTACK_DAMAGE by {@code extraDmgMult} (per-spell rank scaling), then
	 * fold in the Necromancer {@code minion_mastery} passive (+10% HP &amp; damage per rank) and
	 * top the minion off to its new max. A non-Necromancer caster reads a mastery rank of 0, so
	 * only the per-spell multipliers apply. Missing attributes are skipped safely.
	 */
	static void scaleMinion(ServerPlayerEntity caster, LivingEntity minion, double extraHpMult, double extraDmgMult) {
		int mastery = casterPassiveRank(caster, "minion_mastery");
		double masteryMult = 1.0 + MINION_MASTERY_BONUS * mastery;
		scaleBaseValue(minion, EntityAttributes.MAX_HEALTH, extraHpMult * masteryMult);
		scaleBaseValue(minion, EntityAttributes.ATTACK_DAMAGE, extraDmgMult * masteryMult);
		minion.setHealth(minion.getMaxHealth());
	}

	/** Multiply an entity's attribute base value by {@code mult} in place (no-op when 1.0 / absent). */
	private static void scaleBaseValue(LivingEntity entity, RegistryEntry<EntityAttribute> attr, double mult) {
		if (mult == 1.0) {
			return;
		}
		EntityAttributeInstance inst = entity.getAttributeInstance(attr);
		if (inst != null) {
			inst.setBaseValue(inst.getBaseValue() * mult);
		}
	}

	/**
	 * The lifetime (ticks) a Necromancer summon should live: the base 5-minute
	 * {@link SpellManager#SKELETON_SUMMON_LIFETIME_TICKS} plus {@value #UNHOLY_VIGOR_TICKS_PER_RANK}
	 * ticks (30s) per rank of the Unholy Vigor passive.
	 */
	static int necroSummonLifetime(ServerPlayerEntity caster) {
		return SpellManager.SKELETON_SUMMON_LIFETIME_TICKS
			+ UNHOLY_VIGOR_TICKS_PER_RANK * casterPassiveRank(caster, "unholy_vigor");
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
