package net.bubblesky.towerdefense.game;

import java.util.ArrayList;
import java.util.List;
import net.bubblesky.towerdefense.entity.TdArcherEnemy;
import net.bubblesky.towerdefense.entity.TdEnemyEntity;
import net.bubblesky.towerdefense.registry.ModEntities;
import net.bubblesky.towerdefense.registry.ModItems;
import net.bubblesky.towerdefense.state.TdArenaState;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * The endless, progressive wave manager — the heart of the game loop.
 *
 * <p>Hooked to {@link ServerTickEvents#END_SERVER_TICK}, it drives a small state
 * machine ({@code IDLE -> SPAWNING -> ACTIVE -> INTERMISSION -> SPAWNING ...})
 * stored in {@link TdArenaState}. Each wave scales in enemy count and per-enemy
 * hp / damage / speed. When a wave is cleared, nearby players are paid in coins
 * and, after a short intermission, the next (harder) wave auto-starts. Forever.
 *
 * <p>Enemies are the custom {@link ModEntities} ROSTER — original biped mobs
 * ({@link TdEnemyEntity}) composed by TIER from the wave number (see
 * {@link #rosterFor(int)}). On spawn they are tagged with the {@link #ENEMY_TAG}
 * scoreboard command tag (which persists in entity NBT across reloads), marked
 * persistent, and have their default AI goals cleared so they neither wander nor
 * hunt players. Instead this manager steers each one straight at the base via its
 * {@link EntityNavigation}; on arrival it damages the base and is removed. The
 * lone exception is the {@link TdArcherEnemy}, which gets its ranged goal re-added
 * so it can shoot players while marching.
 */
public final class WaveManager {
	/** Scoreboard/command tag marking an entity as a TD wave enemy. */
	public static final String ENEMY_TAG = "td_enemy";
	/** Additional tag on the single boss spawned every {@link #BOSS_WAVE_INTERVAL}th wave. */
	public static final String BOSS_TAG = "td_boss";

	// ---- boss tuning -------------------------------------------------------
	/** Every Nth wave is a boss wave (5, 10, 15, ...). */
	private static final int BOSS_WAVE_INTERVAL = 5;
	/** Extra HP multiplier stacked on top of the normal per-wave scaling. */
	private static final double BOSS_HP_MULT = 8.0;
	/** The boss's (unscaled) base arrival attack — the per-wave scale is applied on arrival. */
	private static final double BOSS_ATTACK_BASE = 12.0;
	/** Visual/hitbox scale attribute for the boss (a towering bruiser). */
	private static final double BOSS_SCALE = 2.2;
	/** Boss march speed factor (a touch slower than a same-wave heavy). */
	private static final double BOSS_SPEED_FACTOR = 0.9;

	// ---- scaling / tuning --------------------------------------------------
	/** Hard cap on the movement-speed attribute so late waves stay playable. */
	private static final double SPEED_CAP = 0.5;
	/** Per-wave geometric growth for hp / base-damage (applied to each enemy's
	 *  own base stat, so the roster's relative differences are preserved). */
	private static final double HP_GROWTH = 1.15;
	/** Per-wave geometric growth for speed (gentler than hp). */
	private static final double SPEED_GROWTH = 1.05;

	/** Ticks between staggered enemy spawns within a wave. */
	private static final int SPAWN_INTERVAL = 15;
	/** Length of the between-waves intermission (ticks; 20t = 1s). */
	private static final int INTERMISSION_TICKS = 100;
	/** Navigation speed multiplier used when steering enemies at the base. */
	private static final double NAV_SPEED = 1.2;
	/** Squared distance (blocks^2) at which an enemy "reaches" the base. */
	private static final double ARRIVAL_DIST_SQ = 4.0;
	/** Radius around the base within which players are paid the wave bounty. */
	private static final double REWARD_RADIUS = 64.0;

	private WaveManager() {
	}

	// ---- roster composition (by tier) --------------------------------------
	/**
	 * The pool of enemy types a given wave draws from, composed by tier:
	 * <ul>
	 *   <li>waves 1-3: goblin_skirmisher, footman</li>
	 *   <li>waves 4-7: + archer, man_at_arms</li>
	 *   <li>waves 8+:  + undead_soldier, heavy_knight, with the heavy mix growing
	 *       as waves climb</li>
	 * </ul>
	 * Duplicate entries act as spawn weights, so the goblin swarm stays common
	 * while heavies get rarer-but-scarier billing.
	 */
	static List<EntityType<? extends TdEnemyEntity>> rosterFor(int wave) {
		List<EntityType<? extends TdEnemyEntity>> pool = new ArrayList<>();
		// Tier 1 — always present, the bread-and-butter swarm.
		pool.add(ModEntities.GOBLIN_SKIRMISHER);
		pool.add(ModEntities.GOBLIN_SKIRMISHER);
		pool.add(ModEntities.FOOTMAN);
		// Tier 2 — ranged pressure + sturdier melee.
		if (wave >= 4) {
			pool.add(ModEntities.ARCHER);
			pool.add(ModEntities.MAN_AT_ARMS);
		}
		// Tier 3 — the heavies, weighted up the further the run goes.
		if (wave >= 8) {
			pool.add(ModEntities.UNDEAD_SOLDIER);
			pool.add(ModEntities.HEAVY_KNIGHT);
			int extra = Math.min(4, (wave - 8) / 2);
			for (int i = 0; i < extra; i++) {
				pool.add(ModEntities.HEAVY_KNIGHT);
				pool.add(ModEntities.UNDEAD_SOLDIER);
				pool.add(ModEntities.MAN_AT_ARMS);
			}
		}
		return pool;
	}

	/** Register the per-tick game-loop driver + boss-defeat payout. Call once from mod init. */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(WaveManager::onEndTick);
		// Bonus coin payout + fanfare when a boss is slain (by anyone/anything).
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (!entity.getCommandTags().contains(BOSS_TAG)) {
				return;
			}
			if (!(entity.getWorld() instanceof ServerWorld world)) {
				return;
			}
			TdArenaState st = TdArenaState.get(world.getServer());
			if (st.base == null) {
				return;
			}
			int bonus = bossReward(st.currentWave);
			payNearbyPlayers(world, st, bonus);
			broadcast(world.getServer(), Text.literal("The Warlord of wave " + st.currentWave
				+ " has fallen! +" + bonus + " bonus coins.").formatted(Formatting.LIGHT_PURPLE));
			TdFeedback.bossDefeated(world, st, bonus);
		});
	}

	/** True if a given wave number is a boss wave. */
	public static boolean isBossWave(int wave) {
		return wave > 0 && wave % BOSS_WAVE_INTERVAL == 0;
	}

	/** Bonus coin payout for defeating the boss of a given wave. */
	private static int bossReward(int wave) {
		return 40 + wave * 10;
	}

	// ---- scaling helpers ---------------------------------------------------
	/** Enemy count for a given wave: {@code 1 + floor(wave * 1.5)}. */
	public static int enemyCount(int wave) {
		return 1 + (int) Math.floor(wave * 1.5);
	}

	/** Per-wave multiplier applied to each enemy's own base hp / arrival damage. */
	private static double waveScale(int wave) {
		return Math.pow(HP_GROWTH, wave - 1);
	}

	/** Per-wave multiplier applied to each enemy's own base movement speed. */
	private static double speedScale(int wave) {
		return Math.pow(SPEED_GROWTH, wave - 1);
	}

	/** Coin bounty paid to each nearby player when a wave is cleared. */
	private static int waveReward(int wave) {
		return 5 + wave * 3;
	}

	// ---- command entry point -----------------------------------------------
	/**
	 * Start the next wave now (used by {@code /td wave}). Returns a human-facing
	 * status message; does not interrupt a wave already in progress.
	 */
	public static Text startNextWave(MinecraftServer server) {
		TdArenaState st = TdArenaState.get(server);
		if (st.base == null) {
			return Text.literal("No base set. Use /td base first.").formatted(Formatting.RED);
		}
		if (st.spawnPoints.isEmpty()) {
			return Text.literal("No spawn points. Use /td spawn first.").formatted(Formatting.RED);
		}
		if (st.gameOver) {
			return Text.literal("Base was destroyed. Use /td reset to start over.").formatted(Formatting.RED);
		}
		if (st.phase == TdArenaState.Phase.SPAWNING || st.phase == TdArenaState.Phase.ACTIVE) {
			return Text.literal("Wave " + st.currentWave + " is still in progress.").formatted(Formatting.YELLOW);
		}
		beginWave(server, st);
		return Text.literal("Wave " + st.currentWave + " starting: "
			+ st.enemiesRemaining + " enemies incoming!").formatted(Formatting.GOLD);
	}

	private static void beginWave(MinecraftServer server, TdArenaState st) {
		st.currentWave += 1;
		st.phase = TdArenaState.Phase.SPAWNING;
		boolean boss = isBossWave(st.currentWave);
		// A boss wave spawns a single, towering warlord instead of the roster.
		st.enemiesRemaining = boss ? 1 : enemyCount(st.currentWave);
		st.spawnCooldown = 0;
		st.intermissionCooldown = 0;
		st.markDirty();
		if (boss) {
			broadcast(server, Text.literal("BOSS WAVE " + st.currentWave
				+ " — a Warlord marches on the base!").formatted(Formatting.DARK_PURPLE, Formatting.BOLD));
		} else {
			broadcast(server, Text.literal("Wave " + st.currentWave + " incoming — "
				+ st.enemiesRemaining + " enemies!").formatted(Formatting.GOLD));
		}
		ServerWorld world = st.getArenaWorld(server);
		if (world != null && !boss) {
			// Boss-wave feedback fires from spawnBoss (so it lands at the gate).
			TdFeedback.waveStart(world, st);
		}
	}

	// ---- tick driver -------------------------------------------------------
	private static void onEndTick(MinecraftServer server) {
		TdArenaState st = TdArenaState.get(server);
		if (st.base == null || st.gameOver) {
			return;
		}
		ServerWorld world = st.getArenaWorld(server);
		if (world == null) {
			return;
		}

		switch (st.phase) {
			case SPAWNING -> tickSpawning(world, st);
			case ACTIVE -> tickActive(server, world, st);
			case INTERMISSION -> tickIntermission(server, st);
			case IDLE -> {
				// Awaiting /td wave.
			}
		}

		// A base can fall mid-wave; resolve the loss after movement/arrivals.
		if (st.baseHp <= 0 && !st.gameOver) {
			loseGame(server, world, st);
		}
	}

	private static void tickSpawning(ServerWorld world, TdArenaState st) {
		steerAndResolveArrivals(world, st);
		if (st.spawnCooldown > 0) {
			st.spawnCooldown--;
		} else if (st.enemiesRemaining > 0) {
			spawnEnemy(world, st);
			st.enemiesRemaining--;
			st.spawnCooldown = SPAWN_INTERVAL;
			st.markDirty();
		}
		if (st.enemiesRemaining <= 0) {
			st.phase = TdArenaState.Phase.ACTIVE;
			st.markDirty();
		}
	}

	private static void tickActive(MinecraftServer server, ServerWorld world, TdArenaState st) {
		steerAndResolveArrivals(world, st);
		if (countEnemies(world, st) <= 0 && st.baseHp > 0) {
			// Wave cleared.
			st.wavesSurvived = st.currentWave;
			int reward = waveReward(st.currentWave);
			payNearbyPlayers(world, st, reward);
			broadcast(server, Text.literal("Wave " + st.currentWave + " cleared! +"
				+ reward + " coins. Base HP " + st.baseHp + "/" + st.baseMaxHp)
				.formatted(Formatting.GREEN));
			TdFeedback.waveClear(world, st);
			st.phase = TdArenaState.Phase.INTERMISSION;
			st.intermissionCooldown = INTERMISSION_TICKS;
			st.markDirty();
		}
	}

	private static void tickIntermission(MinecraftServer server, TdArenaState st) {
		if (st.intermissionCooldown > 0) {
			st.intermissionCooldown--;
			st.markDirty();
		} else {
			beginWave(server, st);
		}
	}

	// ---- enemy lifecycle ---------------------------------------------------
	private static void spawnEnemy(ServerWorld world, TdArenaState st) {
		if (isBossWave(st.currentWave)) {
			spawnBoss(world, st);
			return;
		}
		BlockPos sp = st.spawnPoints.get(world.random.nextInt(st.spawnPoints.size()));
		List<EntityType<? extends TdEnemyEntity>> pool = rosterFor(st.currentWave);
		EntityType<? extends TdEnemyEntity> type = pool.get(world.random.nextInt(pool.size()));
		TdEnemyEntity mob = type.spawn(world, sp, SpawnReason.EVENT);
		if (mob == null) {
			return;
		}
		mob.addCommandTag(ENEMY_TAG);
		mob.setPersistent();
		// Strip default AI (wander / hunt-player / attack) — we drive it ourselves.
		mob.clearGoalsAndTasks();
		mob.setTarget(null);

		int wave = st.currentWave;
		// Scale each enemy's OWN base stats by the wave factor, preserving the
		// roster's relative differences (a wave-10 goblin is still frailer than a
		// wave-10 knight).
		double scale = waveScale(wave);
		double baseHp = mob.getMaxHealth();
		setAttribute(mob, EntityAttributes.MAX_HEALTH, baseHp * scale);
		mob.setHealth(mob.getMaxHealth());
		double baseSpeed = mob.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);
		setAttribute(mob, EntityAttributes.MOVEMENT_SPEED,
			Math.min(SPEED_CAP, baseSpeed * speedScale(wave)));

		// The archer still marches to base, but re-arm its ranged goal so it shoots
		// players who wander into range (clearGoalsAndTasks stripped it above).
		if (mob instanceof TdArcherEnemy archer) {
			archer.addWaveCombatGoals();
		}

		steerToBase(mob, st);
	}

	/**
	 * Spawn the boss for a boss wave: a single scaled-up {@code heavy_knight}
	 * ("Warlord") — huge HP, a big arrival punch, and a larger physical scale — tagged
	 * with {@link #BOSS_TAG} so the HUD gives it its own purple health bar and the
	 * death hook pays the bonus bounty. Its own {@link TdFeedback#bossSpawn} fanfare
	 * fires at the gate it emerges from.
	 */
	private static void spawnBoss(ServerWorld world, TdArenaState st) {
		BlockPos sp = st.spawnPoints.get(world.random.nextInt(st.spawnPoints.size()));
		TdEnemyEntity boss = ModEntities.HEAVY_KNIGHT.spawn(world, sp, SpawnReason.EVENT);
		if (boss == null) {
			return;
		}
		boss.addCommandTag(ENEMY_TAG);
		boss.addCommandTag(BOSS_TAG);
		boss.setPersistent();
		boss.clearGoalsAndTasks();
		boss.setTarget(null);
		boss.setCustomName(Text.literal("Warlord — Wave " + st.currentWave)
			.formatted(Formatting.DARK_PURPLE, Formatting.BOLD));
		boss.setCustomNameVisible(true);

		int wave = st.currentWave;
		double scale = waveScale(wave);
		double hp = boss.getMaxHealth() * scale * BOSS_HP_MULT;
		setAttribute(boss, EntityAttributes.MAX_HEALTH, hp);
		boss.setHealth(boss.getMaxHealth());
		setAttribute(boss, EntityAttributes.ATTACK_DAMAGE, BOSS_ATTACK_BASE);
		setAttribute(boss, EntityAttributes.SCALE, BOSS_SCALE);
		double baseSpeed = boss.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);
		setAttribute(boss, EntityAttributes.MOVEMENT_SPEED,
			Math.min(SPEED_CAP, baseSpeed * speedScale(wave) * BOSS_SPEED_FACTOR));

		steerToBase(boss, st);
		TdFeedback.bossSpawn(world, st, sp, wave);
	}

	/**
	 * Scan the arena for TD enemies: those that reached the base deal their
	 * scaled damage and are removed; the rest are (re)pointed at the base.
	 */
	private static void steerAndResolveArrivals(ServerWorld world, TdArenaState st) {
		if (st.base == null) {
			return;
		}
		double bx = st.base.getX() + 0.5;
		double by = st.base.getY() + 0.5;
		double bz = st.base.getZ() + 0.5;
		double scale = waveScale(Math.max(1, st.currentWave));
		for (Entity e : enemies(world, st)) {
			if (e.squaredDistanceTo(bx, by, bz) <= ARRIVAL_DIST_SQ) {
				// Each enemy deals its OWN attack damage (scaled by the wave), so a
				// heavy knight punches the base far harder than a goblin.
				double atk = e instanceof MobEntity m
					? m.getAttributeValue(EntityAttributes.ATTACK_DAMAGE) : 1.0;
				st.baseHp -= (int) Math.ceil(atk * scale);
				st.markDirty();
				TdFeedback.baseHit(world, st);
				e.discard();
			} else if (e instanceof MobEntity mob) {
				EntityNavigation nav = mob.getNavigation();
				if (nav.isIdle()) {
					steerToBase(mob, st);
				}
			}
		}
	}

	private static void steerToBase(MobEntity mob, TdArenaState st) {
		if (st.base == null) {
			return;
		}
		mob.getNavigation().startMovingTo(st.base.getX() + 0.5, st.base.getY() + 0.5,
			st.base.getZ() + 0.5, NAV_SPEED);
	}

	private static int countEnemies(ServerWorld world, TdArenaState st) {
		return enemies(world, st).size();
	}

	private static List<Entity> enemies(ServerWorld world, TdArenaState st) {
		return world.getOtherEntities(null, arenaBox(st),
			e -> e.isAlive() && e.getCommandTags().contains(ENEMY_TAG));
	}

	/** A generous bounding box enclosing the base and all spawn points. */
	private static Box arenaBox(TdArenaState st) {
		double minX = st.base.getX();
		double minY = st.base.getY();
		double minZ = st.base.getZ();
		double maxX = minX;
		double maxY = minY;
		double maxZ = minZ;
		for (BlockPos sp : st.spawnPoints) {
			minX = Math.min(minX, sp.getX());
			minY = Math.min(minY, sp.getY());
			minZ = Math.min(minZ, sp.getZ());
			maxX = Math.max(maxX, sp.getX());
			maxY = Math.max(maxY, sp.getY());
			maxZ = Math.max(maxZ, sp.getZ());
		}
		return new Box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1).expand(48.0);
	}

	private static void setAttribute(MobEntity mob,
			net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attr,
			double value) {
		EntityAttributeInstance inst = mob.getAttributeInstance(attr);
		if (inst != null) {
			inst.setBaseValue(value);
		}
	}

	// ---- rewards / lose ----------------------------------------------------
	private static void payNearbyPlayers(ServerWorld world, TdArenaState st, int coins) {
		double bx = st.base.getX() + 0.5;
		double by = st.base.getY() + 0.5;
		double bz = st.base.getZ() + 0.5;
		double radiusSq = REWARD_RADIUS * REWARD_RADIUS;
		for (ServerPlayerEntity player : world.getPlayers()) {
			if (player.squaredDistanceTo(bx, by, bz) > radiusSq) {
				continue;
			}
			ItemEntity drop = new ItemEntity(world, player.getX(), player.getBodyY(0.5), player.getZ(),
				new ItemStack(ModItems.COIN, coins));
			drop.setToDefaultPickupDelay();
			world.spawnEntity(drop);
		}
	}

	private static void loseGame(MinecraftServer server, ServerWorld world, TdArenaState st) {
		st.gameOver = true;
		st.phase = TdArenaState.Phase.IDLE;
		st.baseHp = 0;
		// Clean up any enemies still on the field.
		for (Entity e : enemies(world, st)) {
			e.discard();
		}
		st.markDirty();
		broadcast(server, Text.literal("Base destroyed! Survived " + st.wavesSurvived
			+ " wave" + (st.wavesSurvived == 1 ? "" : "s") + ". Use /td restart to play again.")
			.formatted(Formatting.DARK_RED));
		TdFeedback.gameOver(world, st, st.wavesSurvived);
	}

	private static void broadcast(MinecraftServer server, Text message) {
		server.getPlayerManager().broadcast(message, false);
	}
}
