package net.bubblesky.towerdefense.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bubblesky.towerdefense.TowerDefenseMod;
import net.bubblesky.towerdefense.entity.TdArcherEnemy;
import net.bubblesky.towerdefense.entity.TdEnemyEntity;
import net.bubblesky.towerdefense.progression.ProgressLookup;
import net.bubblesky.towerdefense.registry.ModEntities;
import net.bubblesky.towerdefense.registry.ModItems;
import net.bubblesky.towerdefense.state.TdArenaState;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;

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
	/** Long-marathon hp/damage curve — LINEAR term (per wave beyond the first). */
	private static final double MARATHON_LINEAR = 0.10;
	/** Long-marathon hp/damage curve — mild-POWER term coefficient. */
	private static final double MARATHON_POWER_COEF = 0.02;
	/** Long-marathon hp/damage curve — mild-POWER term exponent (< quadratic). */
	private static final double MARATHON_POWER_EXP = 1.5;
	/** Per-wave geometric growth for speed (gentle; still bounded by {@link #SPEED_CAP}). */
	private static final double SPEED_GROWTH = 1.05;

	// ---- wall-breaking -----------------------------------------------------
	/** Shared units of dig-damage needed to shatter one wall block (many enemies
	 *  bashing the SAME block pool their damage, so a horde breaks through faster). */
	private static final int WALL_BREAK_THRESHOLD = 50;
	/** While wall-blocked, a normal enemy re-checks the pathfinder only every N ticks
	 *  (it still swings + digs every tick) so a stuck horde can't hammer pathfinding. */
	private static final int WALL_REPATH_INTERVAL = 10;
	/** Dig units a normal enemy applies per tick to the block ahead. */
	private static final int WALL_DIG_RATE = 1;
	/** A siege breaker digs this many times faster than a normal enemy. */
	private static final int SIEGE_DIG_RATE = 3;
	/** Play the block's hit sound + crack particles roughly every this many dig units. */
	private static final int WALL_FX_STRIDE = 8;
	/** Shared per-block dig-damage accumulator, keyed by {@link BlockPos#asLong()}, so
	 *  every enemy bashing one wall block contributes to the same pool. Cleared when a
	 *  wave ends / the game is lost / the arena is released so it can't grow unbounded. */
	private static final Map<Long, Integer> WALL_DAMAGE = new HashMap<>();

	// ---- horde / drip-spawn ------------------------------------------------
	/** Concurrent live-enemy cap: deep waves can queue 150+ enemies, so spawning pauses
	 *  whenever this many wave enemies are already on the field, resuming as they die.
	 *  Protects server TPS while still delivering the full horde over the wave. */
	private static final int DRIP_CAP = 70;

	/** Ticks between staggered enemy spawns within a wave. */
	private static final int SPAWN_INTERVAL = 15;
	/** Short retry delay after a failed spawn (spot momentarily invalid). */
	private static final int SPAWN_RETRY_TICKS = 5;
	/** After this many consecutive failed attempts for one enemy, log + skip it so a
	 *  permanently-bad spawn spot can't hang the wave forever. */
	private static final int MAX_SPAWN_FAILURES = 40;
	/** Blocks above the terrain surface to drop a spawned enemy (clears grass/snow). */
	private static final int SURFACE_SPAWN_OFFSET = 1;
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
	 *   <li>waves 6+:  + barbarian (rugged heavy melee), growing more common</li>
	 *   <li>waves 8+:  + undead_soldier, heavy_knight, with the heavy mix growing
	 *       as waves climb</li>
	 *   <li>waves 10+: + barbarian_sapper (the straight-line siege breaker) so walls
	 *       start getting threatened as the game deepens</li>
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
		// Tier 2.5 — barbarians: a rugged heavy that gets more common as the run goes.
		if (wave >= 6) {
			pool.add(ModEntities.BARBARIAN);
			if (wave >= 9) {
				pool.add(ModEntities.BARBARIAN);
			}
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
		// Tier 4 — sappers: the straight-line wall-breakers, threatening deep-game walls.
		if (wave >= 10) {
			pool.add(ModEntities.BARBARIAN_SAPPER);
			if (wave >= 20) {
				pool.add(ModEntities.BARBARIAN_SAPPER);
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
	/** Enemy count for a given wave: {@code 9 + floor(wave * 1.5)} — wave 1 opens with a
	 *  real skirmish of 10 and grows into big hordes. (The full long-marathon curve and
	 *  concurrent-enemy drip cap are tuned separately.) */
	public static int enemyCount(int wave) {
		return 9 + (int) Math.floor(wave * 1.5);
	}

	/**
	 * Per-wave multiplier applied to each enemy's own base hp / arrival damage — the
	 * "long marathon" curve. A gentle <em>linear + mild-power</em> blend
	 * ({@code 1 + 0.10*(w-1) + 0.02*(w-1)^1.5}) replaces the old geometric
	 * {@code 1.15^(w-1)} (which exploded to ~10^6x and made enemies unkillable by ~wave
	 * 30). Hordes still get huge, but individual enemies stay killable, so a skilled,
	 * well-built defence can realistically push toward wave 100. Anchor values:
	 * <ul>
	 *   <li>wave 10 ≈ 2.4x</li>
	 *   <li>wave 20 ≈ 4.6x</li>
	 *   <li>wave 50 ≈ 12.8x</li>
	 *   <li>wave 100 ≈ 30.6x</li>
	 * </ul>
	 */
	private static double waveScale(int wave) {
		double w = wave - 1;
		return 1.0 + MARATHON_LINEAR * w + MARATHON_POWER_COEF * Math.pow(w, MARATHON_POWER_EXP);
	}

	/**
	 * Number of Warlord bosses a given boss wave fields — the "epic army" scaling:
	 * {@code max(1, round((wave-5)/8))}. Roughly one Warlord through the mid-teens,
	 * then a growing squad: wave 25 ≈ 3, wave 50 ≈ 6, wave 100 ≈ 12. Each still gets
	 * the {@link #BOSS_TAG}, big HP, scale, and death bounty.
	 */
	private static int bossCount(int wave) {
		return (int) Math.max(1L, Math.round((wave - 5) / 8.0));
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
		if (st.base == null || st.spawnPoints.isEmpty()) {
			return Text.literal("Set the Idol with /td idol and at least one spawn with /td spawn.")
				.formatted(Formatting.RED);
		}
		if (st.gameOver) {
			return Text.literal("The Idol was destroyed. Use /td reset to start over.").formatted(Formatting.RED);
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
		// A boss wave fields a SCALING squad of Warlords PLUS an escorting horde of the
		// normal roster, so a milestone feels like an army, not a lone boss. The bosses
		// are drawn first (bossesRemaining), then the horde, all against one quota.
		int bosses = boss ? bossCount(st.currentWave) : 0;
		st.bossesRemaining = bosses;
		st.enemiesRemaining = enemyCount(st.currentWave) + bosses;
		st.spawnCooldown = 0;
		st.intermissionCooldown = 0;
		st.spawnFailures = 0;
		st.markDirty();
		if (boss) {
			broadcast(server, Text.literal("BOSS WAVE " + st.currentWave + " — "
				+ bosses + (bosses == 1 ? " Warlord marches" : " Warlords march")
				+ " on the Idol with an army at their backs!")
				.formatted(Formatting.DARK_PURPLE, Formatting.BOLD));
		} else {
			broadcast(server, Text.literal("Wave " + st.currentWave + " incoming — "
				+ st.enemiesRemaining + " enemies!").formatted(Formatting.GOLD));
		}
		ServerWorld world = st.getArenaWorld(server);
		if (world != null) {
			// Keep the base + spawn-gate chunks loaded for the whole match so enemies
			// always spawn (heightmap/chunk available) and keep ticking/pathing even if
			// every player walks off. Idempotent, so re-forcing each wave is harmless.
			setArenaForced(world, st, true);
			if (!boss) {
				// Boss-wave feedback fires from spawnBoss (so it lands at the gate).
				TdFeedback.waveStart(world, st);
			}
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
		} else if (st.enemiesRemaining > 0 && countEnemies(world, st) >= DRIP_CAP) {
			// Drip-spawn cap: the field is full. Pause spawning (without draining the
			// quota) and re-check shortly, so deep 150+-enemy waves stream in over time
			// instead of all at once — protecting server TPS while still delivering the
			// full horde. The wave only reaches ACTIVE once the whole quota has spawned.
			st.spawnCooldown = SPAWN_RETRY_TICKS;
		} else if (st.enemiesRemaining > 0) {
			// Only drain the counter when a mob ACTUALLY spawned. A failed spawn (bad
			// spot / momentarily unloaded chunk) retries shortly instead of silently
			// emptying the wave. A safety cap skips a permanently-bad enemy so the wave
			// can't hang forever.
			boolean spawned = spawnEnemy(world, st);
			if (spawned) {
				st.enemiesRemaining--;
				st.spawnFailures = 0;
				st.spawnCooldown = SPAWN_INTERVAL;
			} else {
				st.spawnFailures++;
				if (st.spawnFailures >= MAX_SPAWN_FAILURES) {
					TowerDefenseMod.LOGGER.warn(
						"[towerdefense] wave {} spawn failed {} times; skipping one enemy",
						st.currentWave, st.spawnFailures);
					st.enemiesRemaining--;
					st.spawnFailures = 0;
					st.spawnCooldown = SPAWN_INTERVAL;
				} else {
					st.spawnCooldown = SPAWN_RETRY_TICKS;
				}
			}
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
			// Wave cleared. Reset the shared wall-damage pool so it can't grow unbounded
			// across a long run (any part-broken walls "heal" between waves).
			WALL_DAMAGE.clear();
			st.wavesSurvived = st.currentWave;
			int reward = waveReward(st.currentWave);
			payNearbyPlayers(world, st, reward);
			broadcast(server, Text.literal("Wave " + st.currentWave + " cleared! +"
				+ reward + " coins. Idol HP " + st.baseHp + "/" + st.baseMaxHp)
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
	/** Spawn one wave enemy. Returns {@code true} only if a mob actually appeared. */
	private static boolean spawnEnemy(ServerWorld world, TdArenaState st) {
		// On a boss wave the Warlord squad spawns first; once it's exhausted the rest of
		// the quota is the normal escorting horde.
		if (st.bossesRemaining > 0) {
			boolean ok = spawnBoss(world, st);
			if (ok) {
				st.bossesRemaining--;
			}
			return ok;
		}
		BlockPos sp = surfaceSpawn(world, st.spawnPoints.get(world.random.nextInt(st.spawnPoints.size())));
		List<EntityType<? extends TdEnemyEntity>> pool = rosterFor(st.currentWave);
		EntityType<? extends TdEnemyEntity> type = pool.get(world.random.nextInt(pool.size()));
		TdEnemyEntity mob = type.spawn(world, sp, SpawnReason.EVENT);
		if (mob == null) {
			return false;
		}
		mob.addCommandTag(ENEMY_TAG);
		mob.setPersistent();
		// Strip default AI (wander / hunt-player / attack) — we drive it ourselves.
		mob.clearGoalsAndTasks();
		mob.setTarget(null);
		// ...but let them fight hired allied soldiers they meet on the march. The
		// manager still steers them at the Idol; a soldier in the way only diverts them
		// briefly, then the march resumes when their navigation goes idle.
		mob.addAllyCombatGoals();

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
		return true;
	}

	/**
	 * Spawn one Warlord of a boss wave's squad: a scaled-up {@code heavy_knight} —
	 * huge HP, a big arrival punch, and a larger physical scale — tagged with
	 * {@link #BOSS_TAG} so the HUD tracks it on the purple boss bar and the death hook
	 * pays the bonus bounty. The {@link TdFeedback#bossSpawn} fanfare fires only for the
	 * FIRST Warlord of the wave (so a large squad doesn't spam the title/roar).
	 */
	private static boolean spawnBoss(ServerWorld world, TdArenaState st) {
		BlockPos sp = surfaceSpawn(world, st.spawnPoints.get(world.random.nextInt(st.spawnPoints.size())));
		TdEnemyEntity boss = ModEntities.HEAVY_KNIGHT.spawn(world, sp, SpawnReason.EVENT);
		if (boss == null) {
			return false;
		}
		boss.addCommandTag(ENEMY_TAG);
		boss.addCommandTag(BOSS_TAG);
		boss.setPersistent();
		boss.clearGoalsAndTasks();
		boss.setTarget(null);
		// The Warlord cleaves through any hired soldiers barring its path to the Idol.
		boss.addAllyCombatGoals();
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
		// Only the first Warlord of the squad triggers the roar + title fanfare.
		if (st.bossesRemaining == bossCount(wave)) {
			TdFeedback.bossSpawn(world, st, sp, wave);
		}
		return true;
	}

	/**
	 * Snap a spawn point to the terrain SURFACE at its (x,z) so enemies never spawn
	 * buried inside a hill (which makes {@code type.spawn} return {@code null}). Uses
	 * the world heightmap; the enemy lands a block above ground so it isn't stuck in
	 * a block. The chunk is force-loaded for the match, so the heightmap is valid.
	 */
	private static BlockPos surfaceSpawn(ServerWorld world, BlockPos sp) {
		int surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, sp.getX(), sp.getZ());
		return new BlockPos(sp.getX(), surfaceY + SURFACE_SPAWN_OFFSET, sp.getZ());
	}

	/**
	 * Force-load (or release) the chunks holding the base and every spawn gate, so a
	 * running match keeps spawning + ticking enemies regardless of where players are.
	 * Called with {@code true} when a wave begins and {@code false} on reset/loss.
	 */
	private static void setArenaForced(ServerWorld world, TdArenaState st, boolean forced) {
		if (st.base != null) {
			ChunkPos c = new ChunkPos(st.base);
			world.setChunkForced(c.x, c.z, forced);
		}
		for (BlockPos sp : st.spawnPoints) {
			ChunkPos c = new ChunkPos(sp);
			world.setChunkForced(c.x, c.z, forced);
		}
	}

	/** Release the arena's force-loaded chunks (call before clearing arena state). */
	public static void releaseArenaChunks(MinecraftServer server, TdArenaState st) {
		WALL_DAMAGE.clear();
		ServerWorld world = st.getArenaWorld(server);
		if (world != null) {
			setArenaForced(world, st, false);
		}
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
				steerOrDig(world, mob, st);
			}
		}
	}

	/**
	 * Steer a marching enemy at the Idol — and, when a wall bars the way, smash through
	 * it. Only runs while the mob's navigation is idle: a mob busy fighting a hired
	 * allied soldier (via its {@code MeleeAttackGoal}) keeps fighting uninterrupted.
	 *
	 * <p>Two behaviours:
	 * <ul>
	 *   <li><b>Normal enemies</b> path to the Idol as usual and only dig when there is
	 *       NO path at all. A {@code findPathTo(base)} that {@link Path#reachesTarget()
	 *       reaches} the base restarts the march and clears {@code blockedFromBase}; a
	 *       failing check flags the mob blocked and digs the block dead ahead. The
	 *       pathfinder re-check is throttled (every {@link #WALL_REPATH_INTERVAL} ticks
	 *       while blocked) so a stuck horde can't hammer pathfinding, but the dig itself
	 *       runs every tick.</li>
	 *   <li><b>Siege breakers</b> ({@link TdEnemyEntity#isSiegeBreaker()}) ignore paths
	 *       entirely and bore STRAIGHT at the Idol: they always dig the solid block
	 *       directly ahead (even when a way around exists) and dig faster, marching
	 *       straight when nothing solid blocks them — carving a corridor to the base.</li>
	 * </ul>
	 */
	private static void steerOrDig(ServerWorld world, MobEntity mob, TdArenaState st) {
		if (st.base == null) {
			return;
		}
		EntityNavigation nav = mob.getNavigation();
		if (!nav.isIdle()) {
			// Busy fighting an allied soldier — don't interrupt the melee.
			return;
		}
		TdEnemyEntity te = mob instanceof TdEnemyEntity ? (TdEnemyEntity) mob : null;

		// Siege breaker: bore straight toward the Idol, tunnelling through whatever's
		// directly ahead; when nothing solid blocks it, march straight (no pathing).
		if (te != null && te.isSiegeBreaker()) {
			if (!digTowardBase(world, mob, st, SIEGE_DIG_RATE)) {
				mob.getMoveControl().moveTo(st.base.getX() + 0.5, st.base.getY() + 0.5,
					st.base.getZ() + 0.5, NAV_SPEED);
			}
			return;
		}

		// Non-TD mob (shouldn't happen) — just steer.
		if (te == null) {
			steerToBase(mob, st);
			return;
		}

		// Normal enemy: re-check the pathfinder on a throttle while blocked; otherwise
		// (re)start the march the moment a route exists.
		if (te.repathCooldown > 0) {
			te.repathCooldown--;
		}
		if (!te.blockedFromBase || te.repathCooldown <= 0) {
			te.repathCooldown = WALL_REPATH_INTERVAL;
			Path path = nav.findPathTo(st.base, 1);
			boolean canReach = path != null && path.reachesTarget();
			if (canReach) {
				nav.startMovingAlong(path, NAV_SPEED);
				te.blockedFromBase = false;
				return;
			}
			te.blockedFromBase = true;
		}
		if (te.blockedFromBase) {
			digTowardBase(world, mob, st, WALL_DIG_RATE);
		}
	}

	/**
	 * Dig the wall block DEAD AHEAD toward the Idol. The target is chosen on the
	 * dominant horizontal axis — step ±1 on whichever of dx/dz to the base is larger —
	 * preferring the head-level block, then the feet-level block. Dig-damage for a block
	 * accumulates in the shared {@link #WALL_DAMAGE} pool (keyed by position) so MANY
	 * enemies bashing one block break it faster; at {@link #WALL_BREAK_THRESHOLD} shared
	 * units the block is removed (no drops), the break sound plays, and the attacker's
	 * blocked/repath state is reset so it re-paths through the fresh gap. Unbreakable
	 * blocks, air and fluids are skipped.
	 *
	 * @param rate dig units contributed this tick (normal {@value #WALL_DIG_RATE},
	 *             siege {@value #SIEGE_DIG_RATE})
	 * @return {@code true} if a diggable block was found ahead and processed this tick
	 */
	private static boolean digTowardBase(ServerWorld world, MobEntity mob, TdArenaState st, int rate) {
		if (st.base == null) {
			return false;
		}
		double dx = (st.base.getX() + 0.5) - mob.getX();
		double dz = (st.base.getZ() + 0.5) - mob.getZ();
		int sx = 0;
		int sz = 0;
		if (Math.abs(dx) >= Math.abs(dz)) {
			sx = dx >= 0 ? 1 : -1;
		} else {
			sz = dz >= 0 ? 1 : -1;
		}
		BlockPos feet = mob.getBlockPos();
		// Prefer the head-level block ahead, then the feet-level block ahead.
		BlockPos target = firstDiggable(world, feet.add(sx, 1, sz), feet.add(sx, 0, sz));
		if (target == null) {
			return false;
		}

		// Swing at the wall + face it.
		mob.getLookControl().lookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
		mob.swingHand(Hand.MAIN_HAND);

		BlockState state = world.getBlockState(target);
		long key = target.asLong();
		int before = WALL_DAMAGE.getOrDefault(key, 0);
		int after = before + rate;
		// Crack particles + hit sound roughly every WALL_FX_STRIDE units of shared damage.
		if (before / WALL_FX_STRIDE != after / WALL_FX_STRIDE) {
			world.playSound(null, target, state.getSoundGroup().getHitSound(),
				SoundCategory.HOSTILE, 0.5f, 0.9f);
			world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state),
				target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
				6, 0.3, 0.3, 0.3, 0.0);
		}
		if (after >= WALL_BREAK_THRESHOLD) {
			world.breakBlock(target, false);
			world.playSound(null, target, state.getSoundGroup().getBreakSound(),
				SoundCategory.HOSTILE, 0.8f, 1.0f);
			WALL_DAMAGE.remove(key);
			if (mob instanceof TdEnemyEntity te) {
				te.blockedFromBase = false;
				te.repathCooldown = 0;
			}
		} else {
			WALL_DAMAGE.put(key, after);
		}
		return true;
	}

	/**
	 * Return the first of the given candidate positions that is a breakable solid block
	 * (not air, not a fluid, and not unbreakable {@code hardness < 0}), or {@code null}
	 * if none qualify.
	 */
	private static BlockPos firstDiggable(ServerWorld world, BlockPos... candidates) {
		for (BlockPos pos : candidates) {
			BlockState state = world.getBlockState(pos);
			if (state.isAir() || !state.getFluidState().isEmpty()) {
				continue;
			}
			if (state.getHardness(world, pos) < 0) {
				continue;
			}
			return pos;
		}
		return null;
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
			// RPG Fortune: scale each player's coin payout by their coin multiplier
			// (1.0 = base, +8% per point), so a lucky player earns more from the same wave.
			int paid = Math.max(1, (int) Math.round(coins * ProgressLookup.coinMult(player)));
			ItemEntity drop = new ItemEntity(world, player.getX(), player.getBodyY(0.5), player.getZ(),
				new ItemStack(ModItems.COIN, paid));
			drop.setToDefaultPickupDelay();
			world.spawnEntity(drop);
		}
	}

	private static void loseGame(MinecraftServer server, ServerWorld world, TdArenaState st) {
		st.gameOver = true;
		st.phase = TdArenaState.Phase.IDLE;
		st.baseHp = 0;
		// Drop the shared wall-damage pool so it can't leak across matches.
		WALL_DAMAGE.clear();
		// Clean up any enemies still on the field.
		for (Entity e : enemies(world, st)) {
			e.discard();
		}
		// Stop pinning the arena's chunks now the match is over.
		setArenaForced(world, st, false);
		st.markDirty();
		broadcast(server, Text.literal("The Idol was destroyed! Survived " + st.wavesSurvived
			+ " wave" + (st.wavesSurvived == 1 ? "" : "s") + ". Use /td restart to play again.")
			.formatted(Formatting.DARK_RED));
		TdFeedback.gameOver(world, st, st.wavesSurvived);
	}

	private static void broadcast(MinecraftServer server, Text message) {
		server.getPlayerManager().broadcast(message, false);
	}
}
