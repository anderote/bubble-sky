package net.bubblesky.towerdefense.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
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
	/** Scoreboard team the wave enemies join so their Glowing outline (and Xaero's minimap
	 *  radar) reads bright RED — making the bad guys clearly visible on-screen and on the map. */
	private static final String ENEMY_TEAM = "td_enemies";
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
	/** Armor multiplier stacked on the boss's per-wave armor so a Warlord shrugs off far
	 *  more chip damage than a same-wave heavy (see {@link #armorFor(int)}). */
	private static final double BOSS_ARMOR_MULT = 1.5;

	// ---- scaling / tuning --------------------------------------------------
	/** Every enemy shuffles slowly like a zombie — a fixed march speed regardless of the
	 *  mob's own base speed, so goblins and knights alike lumber in at the same slow pace. */
	private static final double ZOMBIE_MARCH_SPEED = 0.20;
	/** Hard cap on the (zombie-slow) march speed so even deep waves never sprint. */
	private static final double SPEED_CAP = 0.26;
	/** Large FOLLOW_RANGE so an enemy can PATHFIND to the Idol from far across the map
	 *  (the pathfinder's search radius is ≈ this + 8). Targeting is capped separately to
	 *  ~8 blocks by a distance predicate in {@link TdEnemyEntity#addAllyCombatGoals()}, so
	 *  a big follow range does NOT make them hunt — it only extends how far they can path
	 *  (fixes enemies that couldn't reach the Idol from far and tunnelled toward it). */
	private static final double PATHFIND_RANGE = 64.0;
	/** Long-marathon hp/damage curve — LINEAR term (per wave beyond the first). Bumped a
	 *  notch (was 0.10) so hp/damage climbs faster while staying sub-quadratic and killable. */
	private static final double MARATHON_LINEAR = 0.14;
	/** Long-marathon hp/damage curve — mild-POWER term coefficient. Bumped a notch (was
	 *  0.02) for a steeper mid/late ramp, still nowhere near the old runaway geometric curve. */
	private static final double MARATHON_POWER_COEF = 0.03;
	/** Long-marathon hp/damage curve — mild-POWER term exponent (< quadratic). */
	private static final double MARATHON_POWER_EXP = 1.5;
	/** Per-wave geometric growth for speed (gentle; still bounded by {@link #SPEED_CAP}). */
	private static final double SPEED_GROWTH = 1.05;

	// ---- spread / armor / cadence scaling ----------------------------------
	/** Hard cap on the horizontal spread radius (blocks) enemies emerge across around a
	 *  spawn gate — see {@link #spreadRadius(int)}. Keeps the front wide but bounded well
	 *  within the force-loaded gate chunks (their 3x3 neighbourhood is pinned each wave). */
	private static final int SPREAD_CAP = 10;
	/** Per-wave ARMOR granted to every spawned enemy — see {@link #armorFor(int)}. */
	private static final double ARMOR_PER_WAVE = 0.6;
	/** Hard cap on per-enemy ARMOR so late enemies are tanky but never damage-immune. */
	private static final double ARMOR_CAP = 20.0;
	/** Floor on the shrinking per-enemy spawn cadence — see {@link #spawnInterval(int)}. */
	private static final int SPAWN_INTERVAL_MIN = 3;

	// ---- adaptive escalation buffs -----------------------------------------
	/**
	 * How the Warlord's adaptive escalation factor {@code E} (a session rubber-band held in
	 * {@link WarlordDirector#escalation()}, always {@code >= 1.0}) toughens EVERY spawned enemy
	 * when the defence is dominating. These are pure multipliers/bonuses layered on top of the
	 * normal per-wave scaling, applied identically whether the enemy came from the default
	 * roster or a Warlord-planned composition:
	 * <ul>
	 *   <li>MAX_HEALTH is multiplied by {@code E} outright;</li>
	 *   <li>{@link #ESCALATION_ARMOR_PER_E} extra armor per {@code +1.0} of {@code E} is added on
	 *       top of {@link #armorFor(int)}, the bonus itself capped at
	 *       {@link #ESCALATION_ARMOR_CAP};</li>
	 *   <li>the mob is physically enlarged via {@code generic.scale} to
	 *       {@code min(1 + (E-1)*}{@link #ESCALATION_SCALE_PER_E}{@code , }{@link
	 *       #ESCALATION_SCALE_MAX}{@code )} so tougher waves LOOK bigger.</li>
	 * </ul>
	 * At the baseline {@code E == 1.0} every one of these is a no-op (×1 HP, +0 armor, ×1 size),
	 * so a non-escalated wave spawns byte-for-byte as before.
	 */
	private static final double ESCALATION_ARMOR_PER_E = 4.0;
	/** Hard cap on the BONUS armor the escalation adds (on top of the per-wave {@link #armorFor}). */
	private static final double ESCALATION_ARMOR_CAP = 30.0;
	/** Physical-size growth per {@code +1.0} of {@code E} (via {@code generic.scale}). */
	private static final double ESCALATION_SCALE_PER_E = 0.3;
	/** Hard cap on the escalation size multiplier so enemies grow imposing but not absurd. */
	private static final double ESCALATION_SCALE_MAX = 2.0;

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

	// ---- Warlord director (#17a) -------------------------------------------
	/** The non-boss spawn supply for the CURRENT wave when an Enemy AI Warlord plan is
	 *  active: a pre-expanded, shuffled queue of enemy types drawn from the plan's
	 *  composition. Empty whenever no plan governs the wave — in which case spawning falls
	 *  back to the unchanged random-roster path and the wave is byte-for-byte as before.
	 *  Server-thread-only (like {@link #WALL_DAMAGE}). */
	private static final Deque<EntityType<? extends TdEnemyEntity>> PLANNED_QUEUE = new ArrayDeque<>();
	/** The active plan's spawn emphasis for the CURRENT wave (biases WHICH gate a spawn
	 *  emerges from), or {@code null} when no plan governs the wave (uniform gate choice —
	 *  identical to the default path). Server-thread-only. */
	private static WarlordDirector.SpawnEmphasis activeEmphasis = null;

	// ---- enemy outline toggle ---------------------------------------------
	/** Server-authoritative, session-scoped GLOBAL toggle for the red enemy OUTLINE (the
	 *  {@code GLOWING} glow applied by {@link #markEnemyVisible}). Shared by ALL players (there
	 *  is no per-player state): a client keybind sends a {@code ToggleEnemyGlowPayload} which
	 *  flips this flag and re-syncs every live enemy. Defaults to {@code true} (outlines ON).
	 *  Resets to the default whenever the JVM/server restarts (it is not persisted). Read/written
	 *  only on the server thread, like {@link #WALL_DAMAGE}. When {@code false}, {@link
	 *  #markEnemyVisible} skips the glow on spawn (new spawns honour the current flag), while the
	 *  RED {@link #ENEMY_TEAM} assignment — which colours the outline and the minimap radar — is
	 *  always applied regardless (the outline only ever shows while an enemy is glowing). */
	private static boolean enemyGlowEnabled = true;

	// ---- horde / drip-spawn ------------------------------------------------
	/** Concurrent live-enemy cap: deep waves can queue 150+ enemies, so spawning pauses
	 *  whenever this many wave enemies are already on the field, resuming as they die.
	 *  Protects server TPS while still delivering the full horde over the wave. Nudged up
	 *  (was 70) to keep the now-larger late-wave hordes streaming in briskly. */
	private static final int DRIP_CAP = 80;

	/** Ticks between staggered enemy spawns within a wave. */
	private static final int SPAWN_INTERVAL = 15;
	/** Short retry delay after a failed spawn (spot momentarily invalid). */
	private static final int SPAWN_RETRY_TICKS = 5;
	/** After this many consecutive failed attempts for one enemy, log + skip it so a
	 *  permanently-bad spawn spot can't hang the wave forever. */
	private static final int MAX_SPAWN_FAILURES = 40;
	/** Blocks above the terrain surface to drop a spawned enemy (clears grass/snow). */
	private static final int SURFACE_SPAWN_OFFSET = 1;
	/** A gate stored this many blocks (or more) BELOW the heightmap surface counts as an
	 *  UNDERGROUND gate (a tunnel/cave entrance placed via {@code /td spawn}). The margin
	 *  absorbs heightmap noise from grass, snow layers, and 1-2 block terrain bumps so a
	 *  normal surface gate never accidentally trips the tunnel path. */
	private static final int UNDERGROUND_GATE_DEPTH = 3;
	/** When resolving an underground spawn cell, walk DOWN at most this many blocks from the
	 *  gate's stored Y to find the tunnel floor (covers a gate marker set while the player
	 *  hovered slightly above the floor, or a floor dug a couple blocks deeper since). */
	private static final int TUNNEL_FLOOR_SEARCH = 3;
	/** Length of the between-waves intermission (ticks; 20t = 1s). */
	private static final int INTERMISSION_TICKS = 100;
	/** The intermission grows this many ticks per wave (1s), for more breathing room later. */
	private static final int INTERMISSION_GROWTH = 20;
	/** Cap on the growing between-waves intermission (600t = 30s). */
	private static final int INTERMISSION_CAP = 600;
	/** Ticks an enemy may go with NO progress toward the Idol before it's culled as stuck
	 *  (lost underground / wedged in terrain), so a wave can't hang on a lost mob. 1200t = 60s. */
	private static final int STUCK_TIMEOUT = 1200;
	/** Navigation speed multiplier used when steering enemies at the base. */
	private static final double NAV_SPEED = 1.2;
	/** Squared distance (blocks^2) at which an enemy "reaches" the base. */
	private static final double ARRIVAL_DIST_SQ = 4.0;

	private WaveManager() {
	}

	// ---- roster composition (by tier) --------------------------------------
	/**
	 * The pool of enemy types a given wave draws from, composed by tier:
	 * <ul>
	 *   <li>waves 1-3: goblin_skirmisher, footman</li>
	 *   <li>waves 4-7: + archer, man_at_arms, direwolf (fast pack rusher)</li>
	 *   <li>waves 6+:  + barbarian (rugged heavy melee) + gargoyle (wall-bypassing flyer),
	 *       growing more common</li>
	 *   <li>waves 8+:  + undead_soldier, heavy_knight, juggernaut (armoured tank), with the
	 *       heavy mix growing as waves climb</li>
	 *   <li>waves 10+: + barbarian_sapper (the straight-line siege breaker) + hexer (support
	 *       caster) so walls get threatened and the horde starts buffing itself as the game
	 *       deepens</li>
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
		// Tier 2 — sturdier melee. (Archers removed: the enemy army is all-melee now.)
		if (wave >= 4) {
			pool.add(ModEntities.MAN_AT_ARMS);
			pool.add(ModEntities.MAN_AT_ARMS);
			// Direwolves: a cheap, fast pack rusher that swarms in numbers — two copies so the
			// beasts are a common swarm-filler once they arrive.
			pool.add(ModEntities.DIREWOLF);
			pool.add(ModEntities.DIREWOLF);
		}
		// Tier 2.5 — barbarians (rugged heavy) + gargoyles (wall-bypassing flyers), both more
		// common as the run goes.
		if (wave >= 6) {
			pool.add(ModEntities.BARBARIAN);
			pool.add(ModEntities.GARGOYLE);
			if (wave >= 9) {
				pool.add(ModEntities.BARBARIAN);
				pool.add(ModEntities.GARGOYLE);
			}
		}
		// Tier 3 — the heavies, weighted up the further the run goes.
		if (wave >= 8) {
			pool.add(ModEntities.UNDEAD_SOLDIER);
			pool.add(ModEntities.HEAVY_KNIGHT);
			// Juggernauts: armoured tanks that soak tower fire — rarer billing than the swarm.
			pool.add(ModEntities.JUGGERNAUT);
			int extra = Math.min(4, (wave - 8) / 2);
			for (int i = 0; i < extra; i++) {
				pool.add(ModEntities.HEAVY_KNIGHT);
				pool.add(ModEntities.UNDEAD_SOLDIER);
				pool.add(ModEntities.MAN_AT_ARMS);
			}
		}
		// Tier 4 — sappers (straight-line wall-breakers) + hexers (support casters that buff the
		// horde), threatening deep-game walls and snowballing the pack.
		if (wave >= 10) {
			pool.add(ModEntities.BARBARIAN_SAPPER);
			pool.add(ModEntities.HEXER);
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
	/** Enemy count for a given wave: {@code 10 + round(wave * 2.5)} — wave 1 opens with a
	 *  real skirmish of ~13 and ramps into big hordes far sooner than the old
	 *  {@code 9 + floor(wave*1.5)} (wave 5 ≈ 23, wave 10 ≈ 35, wave 25 ≈ 73, wave 50 ≈ 135).
	 *  (The full long-marathon curve and concurrent-enemy drip cap are tuned separately.) */
	public static int enemyCount(int wave) {
		return 10 + (int) Math.round(wave * 2.5);
	}

	/**
	 * Per-wave multiplier applied to each enemy's own base hp / arrival damage — the
	 * "long marathon" curve. A gentle <em>linear + mild-power</em> blend
	 * ({@code 1 + 0.10*(w-1) + 0.02*(w-1)^1.5}) replaces the old geometric
	 * {@code 1.15^(w-1)} (which exploded to ~10^6x and made enemies unkillable by ~wave
	 * 30). Hordes still get huge, but individual enemies stay killable, so a skilled,
	 * well-built defence can realistically push toward wave 100. The linear + power
	 * coefficients were each bumped a notch ({@link #MARATHON_LINEAR} 0.10→0.14,
	 * {@link #MARATHON_POWER_COEF} 0.02→0.03) for a steeper — but still sub-quadratic and
	 * beatable — ramp. Anchor values:
	 * <ul>
	 *   <li>wave 5 ≈ 1.8x</li>
	 *   <li>wave 10 ≈ 3.1x</li>
	 *   <li>wave 25 ≈ 7.9x</li>
	 *   <li>wave 50 ≈ 18.2x</li>
	 *   <li>wave 100 ≈ 44.4x</li>
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
	static int bossCount(int wave) {
		return (int) Math.max(1L, Math.round((wave - 5) / 8.0));
	}

	/** Per-wave multiplier applied to each enemy's own base movement speed. */
	private static double speedScale(int wave) {
		return Math.pow(SPEED_GROWTH, wave - 1);
	}

	/**
	 * Horizontal spread radius (blocks) enemies emerge across around each spawn gate,
	 * growing with the wave: {@code min(SPREAD_CAP, floor(wave/2))}. At wave 1 this is 0
	 * (enemies emerge tight on the gate tile); by mid/late waves it widens so a horde
	 * pours out across a broad front around the gate rather than stacking on one block.
	 * Bounded by {@link #SPREAD_CAP} so the spread always stays within the gate's
	 * force-loaded chunk neighbourhood. Anchors: wave 1 → 0, 5 → 2, 10 → 5, 25 → 10, 50 → 10.
	 */
	private static int spreadRadius(int wave) {
		return Math.min(SPREAD_CAP, wave / 2);
	}

	/**
	 * Per-enemy ARMOR granted on spawn, growing with the wave so late enemies are visibly
	 * tankier: {@code min(ARMOR_CAP, wave * ARMOR_PER_WAVE)}. Capped at {@link #ARMOR_CAP}
	 * so enemies stay killable (never damage-immune). Bosses stack {@link #BOSS_ARMOR_MULT}
	 * on top of this. Anchors: wave 1 → 0.6, 5 → 3.0, 10 → 6.0, 25 → 15.0, 50 → 20.0 (capped).
	 */
	private static double armorFor(int wave) {
		return Math.min(ARMOR_CAP, wave * ARMOR_PER_WAVE);
	}

	/**
	 * BONUS armor the adaptive escalation adds on top of {@link #armorFor(int)} — scaled by how
	 * far the factor {@code e} sits above baseline ({@code (e-1) * }{@link
	 * #ESCALATION_ARMOR_PER_E}) and capped at {@link #ESCALATION_ARMOR_CAP}. Returns 0 at the
	 * baseline {@code e <= 1.0} (never negative), so escalation only ever ADDS armor.
	 */
	private static double escalationArmorBonus(double e) {
		return Math.min(ESCALATION_ARMOR_CAP, Math.max(0.0, (e - 1.0) * ESCALATION_ARMOR_PER_E));
	}

	/**
	 * The physical {@code generic.scale} multiplier the adaptive escalation applies so a tougher
	 * wave LOOKS bigger: {@code min(1 + (e-1)*}{@link #ESCALATION_SCALE_PER_E}{@code , }{@link
	 * #ESCALATION_SCALE_MAX}{@code )}. Returns 1.0 (no visual change) at the baseline
	 * {@code e <= 1.0}.
	 */
	private static double escalationSize(double e) {
		return Math.min(ESCALATION_SCALE_MAX, 1.0 + Math.max(0.0, e - 1.0) * ESCALATION_SCALE_PER_E);
	}

	/**
	 * Per-enemy spawn cadence (ticks between staggered spawns), shrinking with the wave so
	 * hordes pour in quicker as the run deepens: {@code max(SPAWN_INTERVAL_MIN, SPAWN_INTERVAL
	 * - floor(wave/2))}. Floored at {@link #SPAWN_INTERVAL_MIN} so even deep waves don't spawn
	 * every tick. Anchors: wave 1 → 15, 5 → 13, 10 → 10, 25 → 3, 50 → 3.
	 */
	private static int spawnInterval(int wave) {
		return Math.max(SPAWN_INTERVAL_MIN, SPAWN_INTERVAL - wave / 2);
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
		// No "wave in progress" guard: /td wave ALWAYS launches the next wave — skipping the
		// intermission pause, or stacking a fresh wave on top of a live one (a rush).
		boolean stacking = st.phase == TdArenaState.Phase.SPAWNING || st.phase == TdArenaState.Phase.ACTIVE;
		beginWave(server, st);
		return Text.literal("Wave " + st.currentWave + (stacking ? " force-started (stacking onto the live wave)! " : " starting: ")
			+ st.enemiesRemaining + " enemies incoming!").formatted(Formatting.GOLD);
	}

	/** Between-waves pause (ticks), growing with the wave number for more breathing room in
	 *  later waves — from {@link #INTERMISSION_TICKS} at wave 1 up to {@link #INTERMISSION_CAP}. */
	private static int intermissionTicks(int wave) {
		return Math.min(INTERMISSION_CAP, INTERMISSION_TICKS + Math.max(0, wave - 1) * INTERMISSION_GROWTH);
	}

	private static void beginWave(MinecraftServer server, TdArenaState st) {
		st.currentWave += 1;
		st.phase = TdArenaState.Phase.SPAWNING;
		boolean boss = isBossWave(st.currentWave);
		// A boss wave fields a SCALING squad of Warlords PLUS an escorting horde of the
		// normal roster, so a milestone feels like an army, not a lone boss. The bosses
		// are drawn first (bossesRemaining), then the horde, all against one quota.
		int bosses = boss ? bossCount(st.currentWave) : 0;
		// Enemy AI Warlord (#17a): if an EXTERNAL agent submitted a validated plan for this
		// wave, build the wave from its composition + spawn emphasis (within the clamped
		// budget); otherwise take the unchanged default path. Boss-wave squads are preserved
		// (a plan may only ADD bosses on top of the default milestone squad, never remove them).
		applyWarlordPlan(st, boss, bosses);
		st.spawnCooldown = 0;
		st.intermissionCooldown = 0;
		st.spawnFailures = 0;
		st.markDirty();
		if (boss) {
			int bossShown = st.bossesRemaining;
			broadcast(server, Text.literal("BOSS WAVE " + st.currentWave + " — "
				+ bossShown + (bossShown == 1 ? " Warlord marches" : " Warlords march")
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
			// Begin Warlord telemetry for this wave (spawned/leaked/killed-by/duration +
			// pressure metrics). The Idol's current HP is captured as the baseline so the
			// wave's idolDamage can be derived when it finalises.
			WarlordDirector.get().onWaveStart(st.currentWave, world.getTime(), st.baseHp);
			if (!boss) {
				// Boss-wave feedback fires from spawnBoss (so it lands at the gate).
				TdFeedback.waveStart(world, st);
			}
		} else {
			WarlordDirector.get().onWaveStart(st.currentWave, 0L, st.baseHp);
		}
	}

	/**
	 * Consume any validated {@link WarlordDirector.WavePlan} for the wave being started and
	 * apply it — populating the non-boss {@link #PLANNED_QUEUE}, arming the
	 * {@link #activeEmphasis}, and setting {@code bossesRemaining}/{@code enemiesRemaining}.
	 * With NO plan present this is a pure passthrough that reproduces the exact default
	 * counts, so the wave plays byte-for-byte as before (graceful fallback).
	 *
	 * @param defaultBosses the boss squad the wave would field by default (0 on a normal wave)
	 */
	private static void applyWarlordPlan(TdArenaState st, boolean boss, int defaultBosses) {
		PLANNED_QUEUE.clear();
		activeEmphasis = null;
		WarlordDirector.WavePlan plan = WarlordDirector.get().takePlan(st.currentWave);
		if (plan == null) {
			// Default path — unchanged.
			st.bossesRemaining = defaultBosses;
			st.enemiesRemaining = enemyCount(st.currentWave) + defaultBosses;
			return;
		}
		activeEmphasis = plan.emphasis();
		// Expand the composition (minus the special "boss" entry) into a shuffled supply
		// queue; the shuffle uses its OWN Random so it never perturbs the world RNG stream.
		List<EntityType<? extends TdEnemyEntity>> expanded = new ArrayList<>();
		for (Map.Entry<String, Integer> e : plan.composition().entrySet()) {
			if (WarlordDirector.BOSS_ID.equals(e.getKey())) {
				continue;
			}
			EntityType<? extends TdEnemyEntity> type = WarlordDirector.typeFor(e.getKey());
			if (type == null) {
				continue;
			}
			for (int i = 0; i < e.getValue(); i++) {
				expanded.add(type);
			}
		}
		// The plan may ADD bosses on top of the default milestone squad, never remove them.
		int bosses = Math.max(defaultBosses, WarlordDirector.plannedBosses(plan));
		// Anti-trivialize guard: a plan that clamps to NO units at all (e.g. all-unknown ids)
		// falls back to the default wave rather than fielding an empty (free) wave.
		if (expanded.isEmpty() && bosses == 0) {
			activeEmphasis = null;
			st.bossesRemaining = defaultBosses;
			st.enemiesRemaining = enemyCount(st.currentWave) + defaultBosses;
			return;
		}
		Collections.shuffle(expanded);
		PLANNED_QUEUE.addAll(expanded);
		st.bossesRemaining = bosses;
		st.enemiesRemaining = expanded.size() + bosses;
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
				st.spawnCooldown = spawnInterval(st.currentWave);
			} else {
				st.spawnFailures++;
				if (st.spawnFailures >= MAX_SPAWN_FAILURES) {
					TowerDefenseMod.LOGGER.warn(
						"[towerdefense] wave {} spawn failed {} times; skipping one enemy",
						st.currentWave, st.spawnFailures);
					// Keep the planned supply in step with the quota when we skip a bad enemy.
					if (!PLANNED_QUEUE.isEmpty()) {
						PLANNED_QUEUE.pollFirst();
					}
					st.enemiesRemaining--;
					st.spawnFailures = 0;
					st.spawnCooldown = spawnInterval(st.currentWave);
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
			// Finalise Warlord telemetry for the cleared wave (passing the Idol's end HP so the
			// wave's idolDamage + adaptive escalation are computed) and drop spent plan state.
			WarlordDirector.get().finalizeWave(st.currentWave, world.getTime(), st.baseHp);
			PLANNED_QUEUE.clear();
			activeEmphasis = null;
			st.wavesSurvived = st.currentWave;
			int reward = waveReward(st.currentWave);
			payNearbyPlayers(world, st, reward);
			broadcast(server, Text.literal("Wave " + st.currentWave + " cleared! +"
				+ reward + " coins. Idol HP " + st.baseHp + "/" + st.baseMaxHp)
				.formatted(Formatting.GREEN));
			TdFeedback.waveClear(world, st);
			dropWaveReward(server, world, st);
			st.phase = TdArenaState.Phase.INTERMISSION;
			st.intermissionCooldown = intermissionTicks(st.currentWave);
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
		BlockPos sp = spreadSpawn(world, pickSpawnGate(world, st), st.currentWave);
		// Draw the type from the Warlord plan's supply queue if one governs this wave; else
		// fall back to the unchanged random-roster draw (byte-for-byte the default behaviour).
		boolean planned = !PLANNED_QUEUE.isEmpty();
		EntityType<? extends TdEnemyEntity> type;
		if (planned) {
			type = PLANNED_QUEUE.pollFirst();
		} else {
			List<EntityType<? extends TdEnemyEntity>> pool = rosterFor(st.currentWave);
			type = pool.get(world.random.nextInt(pool.size()));
		}
		TdEnemyEntity mob = type.spawn(world, sp, SpawnReason.EVENT);
		if (mob == null) {
			// Return the drawn type to the queue so a failed spawn retries it, not drops it.
			if (planned) {
				PLANNED_QUEUE.addFirst(type);
			}
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
		// The Warlord's ADAPTIVE escalation factor (>= 1.0). When the defence has been
		// dominating, this globally toughens EVERY spawned enemy — regardless of whether it
		// was drawn from the default roster or a Warlord-planned composition — via more HP,
		// bonus armor, and a larger physical size. At the baseline (E == 1.0) it is a no-op.
		double e = WarlordDirector.get().escalation();
		// Scale each enemy's OWN base stats by the wave factor, preserving the
		// roster's relative differences (a wave-10 goblin is still frailer than a
		// wave-10 knight). The escalation factor multiplies HP on top of that.
		double scale = waveScale(wave);
		double baseHp = mob.getMaxHealth();
		setAttribute(mob, EntityAttributes.MAX_HEALTH, baseHp * scale * e);
		mob.setHealth(mob.getMaxHealth());
		// All enemies shuffle in slowly like zombies: a fixed slow march speed (NOT the
		// mob's own base) with only a gentle per-wave nudge, hard-capped low.
		setAttribute(mob, EntityAttributes.MOVEMENT_SPEED,
			Math.min(SPEED_CAP, ZOMBIE_MARCH_SPEED * speedScale(wave)));
		// Only engage an ally/player that comes within range — no long-range hunting.
		setAttribute(mob, EntityAttributes.FOLLOW_RANGE, PATHFIND_RANGE);
		// Mostly knockback-resistant (0.5): arrow hits nudge them back a little (slowing
		// their advance) without launching them off their march to the Idol.
		setAttribute(mob, EntityAttributes.KNOCKBACK_RESISTANCE, 0.5);
		// Per-wave ARMOR (capped) plus the escalation's bonus armor: later — and dominated —
		// enemies soak more chip damage, so towers must out-scale them rather than tickle a
		// swelling horde to death.
		setAttribute(mob, EntityAttributes.ARMOR, armorFor(wave) + escalationArmorBonus(e));
		// Physically enlarge the mob under escalation so a tougher wave visibly looks bigger.
		setAttribute(mob, EntityAttributes.SCALE, escalationSize(e));

		// The archer still marches to base, but re-arm its ranged goal so it shoots
		// players who wander into range (clearGoalsAndTasks stripped it above).
		if (mob instanceof TdArcherEnemy archer) {
			archer.addWaveCombatGoals();
		}

		markEnemyVisible(world, mob);
		steerToBase(mob, st);
		WarlordDirector.get().recordSpawn();
		return true;
	}

	/**
	 * Pick the spawn gate for one spawn. With an active Warlord {@link #activeEmphasis} the
	 * choice is WEIGHTED toward the emphasised flank; with none it is a uniform random gate —
	 * consuming the exact same single {@code random} draw as the original inline selection,
	 * so the default (no-plan) spawn stream is unchanged.
	 */
	private static BlockPos pickSpawnGate(ServerWorld world, TdArenaState st) {
		if (activeEmphasis == null) {
			return st.spawnPoints.get(world.random.nextInt(st.spawnPoints.size()));
		}
		return WarlordDirector.weightedGate(world, st, activeEmphasis);
	}

	/**
	 * Spawn one Warlord of a boss wave's squad: a scaled-up {@code heavy_knight} —
	 * huge HP, a big arrival punch, and a larger physical scale — tagged with
	 * {@link #BOSS_TAG} so the HUD tracks it on the purple boss bar and the death hook
	 * pays the bonus bounty. The {@link TdFeedback#bossSpawn} fanfare fires only for the
	 * FIRST Warlord of the wave (so a large squad doesn't spam the title/roar).
	 */
	private static boolean spawnBoss(ServerWorld world, TdArenaState st) {
		BlockPos sp = spreadSpawn(world, pickSpawnGate(world, st), st.currentWave);
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
		// A boss is a spawned enemy too, so the adaptive escalation factor toughens it as
		// well — more HP and bonus armor on top of the boss's own big multipliers. Its
		// physical size stays at the (already larger) BOSS_SCALE.
		double e = WarlordDirector.get().escalation();
		double scale = waveScale(wave);
		double hp = boss.getMaxHealth() * scale * BOSS_HP_MULT * e;
		setAttribute(boss, EntityAttributes.MAX_HEALTH, hp);
		boss.setHealth(boss.getMaxHealth());
		setAttribute(boss, EntityAttributes.ATTACK_DAMAGE, BOSS_ATTACK_BASE);
		setAttribute(boss, EntityAttributes.SCALE, BOSS_SCALE);
		setAttribute(boss, EntityAttributes.MOVEMENT_SPEED,
			Math.min(SPEED_CAP, ZOMBIE_MARCH_SPEED * speedScale(wave) * BOSS_SPEED_FACTOR));
		setAttribute(boss, EntityAttributes.FOLLOW_RANGE, PATHFIND_RANGE);
		setAttribute(boss, EntityAttributes.KNOCKBACK_RESISTANCE, 0.5);
		// The Warlord stacks BOSS_ARMOR_MULT on the per-wave armor, plus the escalation's bonus
		// armor, making it a true bullet-sponge that a well-upgraded battery must focus down.
		setAttribute(boss, EntityAttributes.ARMOR, armorFor(wave) * BOSS_ARMOR_MULT + escalationArmorBonus(e));

		markEnemyVisible(world, boss);
		steerToBase(boss, st);
		WarlordDirector.get().recordSpawn();
		// Only the first Warlord of the squad triggers the roar + title fanfare.
		if (st.bossesRemaining == bossCount(wave)) {
			TdFeedback.bossSpawn(world, st, sp, wave);
		}
		return true;
	}

	/**
	 * Resolve a gate's actual spawn position, aware of BOTH kinds of gate:
	 *
	 * <p><b>Surface gates</b> (the common case) are snapped to the terrain surface at
	 * their (x,z) via the world heightmap so enemies never spawn buried inside a hill
	 * (which makes {@code type.spawn} return {@code null}) or floating over rough
	 * terrain; the enemy lands a block above ground so it isn't stuck in grass/snow.
	 *
	 * <p><b>Underground gates</b> — the gate marker is set at the player's FEET by
	 * {@code /td spawn}, so a gate placed inside a tunnel/cave carries a trustworthy
	 * below-surface Y. Blindly heightmap-snapping such a gate teleported its spawns to
	 * the surface ABOVE the tunnel, which is exactly the bug this branch fixes: when the
	 * stored Y sits {@link #UNDERGROUND_GATE_DEPTH}+ blocks below the heightmap, we keep
	 * the stored Y and only validate it via {@link #tunnelSpawn} (head room + a floor
	 * within {@link #TUNNEL_FLOOR_SEARCH} blocks). Only if that validation fails (the
	 * tunnel collapsed / was filled in) do we fall back to the surface snap, because a
	 * broken gate must still spawn SOMEWHERE rather than hang the wave.
	 *
	 * <p>The chunk is force-loaded for the match, so heightmap and block reads are valid.
	 */
	private static BlockPos surfaceSpawn(ServerWorld world, BlockPos sp) {
		if (isUndergroundGate(world, sp)) {
			BlockPos tunnel = tunnelSpawn(world, sp);
			if (tunnel != null) {
				return tunnel;
			}
		}
		int surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, sp.getX(), sp.getZ());
		return new BlockPos(sp.getX(), surfaceY + SURFACE_SPAWN_OFFSET, sp.getZ());
	}

	/**
	 * Whether a gate's stored position sits meaningfully BELOW the heightmap surface of
	 * its own column — i.e. it was deliberately placed underground (tunnel/cave). The
	 * {@link #UNDERGROUND_GATE_DEPTH} margin keeps surface gates on bumpy terrain, tall
	 * grass, or snow layers on the plain heightmap-snap path.
	 */
	private static boolean isUndergroundGate(ServerWorld world, BlockPos sp) {
		int surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, sp.getX(), sp.getZ());
		return sp.getY() < surfaceY - UNDERGROUND_GATE_DEPTH;
	}

	/**
	 * Validate an underground spawn cell at the gate's stored Y: the cell and the cell
	 * above must be passable (head room for a biped) with a solid floor directly below.
	 * Walks DOWN up to {@link #TUNNEL_FLOOR_SEARCH} blocks to find that floor, so a gate
	 * marker hovering a couple of blocks over the tunnel floor still resolves. Returns
	 * {@code null} when no standable cell exists (candidate is inside solid rock / over a
	 * void) so the caller can fall back rather than spawn a mob inside a wall.
	 */
	private static BlockPos tunnelSpawn(ServerWorld world, BlockPos pos) {
		for (int dy = 0; dy <= TUNNEL_FLOOR_SEARCH; dy++) {
			BlockPos cell = pos.down(dy);
			if (isStandable(world, cell)) {
				return cell;
			}
		}
		return null;
	}

	/**
	 * Whether a mob can be dropped into {@code pos}: the cell (and the one above it, for
	 * head room) is passable and the cell below is a solid, non-fluid floor to stand on.
	 * Guards against spawning inside solid blocks or in fluids, within build height.
	 */
	private static boolean isStandable(ServerWorld world, BlockPos pos) {
		if (world.isOutOfHeightLimit(pos) || world.isOutOfHeightLimit(pos.up())) {
			return false;
		}
		return canStandOn(world, pos.down()) && passable(world, pos) && passable(world, pos.up());
	}

	/** A solid, non-fluid floor a mob can stand on top of (non-empty collision shape, no fluid). */
	private static boolean canStandOn(ServerWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return state.getFluidState().isEmpty() && !state.getCollisionShape(world, pos).isEmpty();
	}

	/** An empty, non-fluid cell a mob can occupy (no collision, no fluid). */
	private static boolean passable(ServerWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return state.getFluidState().isEmpty() && state.getCollisionShape(world, pos).isEmpty();
	}

	/**
	 * Multi-point spread spawn: pick a random horizontal offset within
	 * {@link #spreadRadius(int)} blocks of the given spawn gate (a square kernel of
	 * {@code [-r, r]} on each axis), then resolve the OFFSET position via
	 * {@link #surfaceSpawn} so it still lands on solid ground. The radius grows with the
	 * wave, so wave 1 emerges tight on the gate tile while mid/late waves pour out across a
	 * wide front around it. The offset is bounded by {@link #SPREAD_CAP} and the gate's
	 * force-loaded 3x3 chunk neighbourhood (pinned in {@link #setArenaForced}) so the
	 * resolved position always sits on loaded, valid terrain.
	 *
	 * <p><b>Underground gates:</b> the offset cell is validated at the GATE's stored Y
	 * (walking down a few blocks for the floor, via {@link #tunnelSpawn}). A random
	 * offset in a narrow tunnel usually lands inside the rock wall — heightmap-snapping
	 * it would pop the enemy onto the surface above the tunnel, so instead we retry with
	 * the un-offset gate position (whose own tunnel-aware {@link #surfaceSpawn} path
	 * keeps it at depth). Underground waves therefore emerge tight from the gate mouth
	 * rather than spread wide, which is the physically sensible behaviour for a tunnel.
	 */
	private static BlockPos spreadSpawn(ServerWorld world, BlockPos sp, int wave) {
		int radius = spreadRadius(wave);
		if (radius <= 0) {
			return surfaceSpawn(world, sp);
		}
		int dx = world.random.nextInt(radius * 2 + 1) - radius;
		int dz = world.random.nextInt(radius * 2 + 1) - radius;
		BlockPos offset = sp.add(dx, 0, dz);
		if (isUndergroundGate(world, sp)) {
			// Keep the spread at TUNNEL depth: accept the offset only if it is a real
			// standable air pocket at the gate's Y; otherwise fall back to the gate
			// itself. Never heightmap-snap the offset — that is the surface-leak bug.
			BlockPos tunnel = tunnelSpawn(world, offset);
			if (tunnel != null) {
				return tunnel;
			}
			return surfaceSpawn(world, sp);
		}
		return surfaceSpawn(world, offset);
	}

	/**
	 * Force-load (or release) the chunks holding the base and every spawn gate, so a
	 * running match keeps spawning + ticking enemies regardless of where players are.
	 * Called with {@code true} when a wave begins and {@code false} on reset/loss.
	 *
	 * <p>Each spawn gate pins its full 3x3 chunk neighbourhood (not just its own chunk) so
	 * that multi-point {@linkplain #spreadSpawn spread spawning} — which can offset up to
	 * {@link #SPREAD_CAP} blocks off the gate, crossing at most one chunk boundary — always
	 * lands on loaded terrain with a valid heightmap.
	 */
	private static void setArenaForced(ServerWorld world, TdArenaState st, boolean forced) {
		if (st.base != null) {
			ChunkPos c = new ChunkPos(st.base);
			world.setChunkForced(c.x, c.z, forced);
		}
		for (BlockPos sp : st.spawnPoints) {
			ChunkPos c = new ChunkPos(sp);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					world.setChunkForced(c.x + dx, c.z + dz, forced);
				}
			}
		}
	}

	/** Release the arena's force-loaded chunks (call before clearing arena state). */
	public static void releaseArenaChunks(MinecraftServer server, TdArenaState st) {
		WALL_DAMAGE.clear();
		PLANNED_QUEUE.clear();
		activeEmphasis = null;
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
			double distSq = e.squaredDistanceTo(bx, by, bz);
			// Pressure metric: track the closest ANY live enemy gets to the Idol this wave
			// (in blocks). The running minimum feeds the Warlord's adaptive escalation.
			WarlordDirector.get().recordApproach(Math.sqrt(distSq));
			if (distSq <= ARRIVAL_DIST_SQ) {
				// Each enemy deals its OWN attack damage (scaled by the wave), so a
				// heavy knight punches the base far harder than a goblin.
				double atk = e instanceof MobEntity m
					? m.getAttributeValue(EntityAttributes.ATTACK_DAMAGE) : 1.0;
				st.baseHp -= (int) Math.ceil(atk * scale);
				st.markDirty();
				TdFeedback.baseHit(world, st);
				// Telemetry: this enemy leaked to the idol (it is discarded, not killed).
				WarlordDirector.get().recordLeak();
				e.discard();
			} else if (e instanceof MobEntity mob) {
				// Stuck-enemy cleanup: cull an enemy that makes NO progress toward the Idol
				// for STUCK_TIMEOUT (lost underground / wedged in terrain) so the wave can
				// finish instead of hanging on a lost mob. Any progress resets the timer.
				if (mob instanceof TdEnemyEntity te) {
					if (distSq < te.bestDistSq) {
						te.bestDistSq = distSq;
						te.stuckTicks = 0;
					} else if (++te.stuckTicks > STUCK_TIMEOUT) {
						e.discard();
						continue;
					}
				}
				// Drown: an enemy caught underwater takes drowning damage — a water moat is
				// a real defense, and it flushes out anything that wanders into water.
				if (mob.isSubmergedInWater() && mob.age % 20 == 0) {
					mob.damage(world, world.getDamageSources().drown(), 4.0f);
				}
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

		// Flyer: IGNORE walls entirely — never dig. Its BirdNavigation path-finds through the
		// AIR, routing up-and-over whatever the defender built, straight at the Idol (the
		// anti-turtle unit). If the flight path-finder can't reach (Idol beyond its range), fall
		// back to a straight 3D beeline so it still seeks the Idol from anywhere on the map.
		if (te != null && te.isFlyer()) {
			Path path = nav.findPathTo(st.base, 1);
			if (path != null && path.reachesTarget()) {
				nav.startMovingAlong(path, NAV_SPEED);
			} else {
				beelineToBase(mob, st);
			}
			return;
		}

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

		// Normal enemy: try to path to the Idol; if a full route exists, march it. If not —
		// whether the way is WALLED or the Idol is simply too far for the (range-bounded)
		// pathfinder — fall back so the enemy STILL seeks the Idol from anywhere on the map:
		// dig a wall if one is directly ahead, otherwise beeline straight at the Idol until
		// it's close enough for real pathfinding to take over the final approach.
		if (te.repathCooldown > 0) {
			te.repathCooldown--;
		}
		if (te.repathCooldown <= 0) {
			te.repathCooldown = WALL_REPATH_INTERVAL;
			Path path = nav.findPathTo(st.base, 1);
			if (path != null && path.reachesTarget()) {
				nav.startMovingAlong(path, NAV_SPEED);
				te.blockedFromBase = false;
				return;
			}
			// digTowardBase returns true only when a breakable block sits DIRECTLY ahead
			// (a real wall); false means open ground → head straight for the Idol instead.
			te.blockedFromBase = digTowardBase(world, mob, st, WALL_DIG_RATE);
			if (!te.blockedFromBase) {
				beelineToBase(mob, st);
			}
			return;
		}
		// Between re-paths, keep acting on the last decision every tick.
		if (te.blockedFromBase) {
			digTowardBase(world, mob, st, WALL_DIG_RATE);
		} else {
			beelineToBase(mob, st);
		}
	}

	/** Walk straight at the Idol via the move control (no pathfinding). The fallback that
	 *  lets an enemy seek the Idol from beyond pathfinding range or across open ground until
	 *  it gets close enough for {@link EntityNavigation} to path the rest of the way. */
	/** Make an enemy clearly visible: a permanent Glowing outline (seen through walls and on
	 *  Xaero's minimap radar) + membership in a RED scoreboard team so that glow/marker reads
	 *  as "the enemy". Called once on spawn.
	 *
	 *  <p>The RED {@link #ENEMY_TEAM} assignment (which tints the outline + minimap radar) is
	 *  applied unconditionally. The {@code GLOWING} effect — the outline itself — is gated on
	 *  the GLOBAL {@link #enemyGlowEnabled} toggle: with outlines OFF we skip the glow entirely
	 *  so a fresh spawn honours the current flag, and it can be turned back on mid-wave via
	 *  {@link #toggleEnemyGlow(MinecraftServer)} (which re-applies it to every live enemy). */
	private static void markEnemyVisible(ServerWorld world, MobEntity mob) {
		if (enemyGlowEnabled) {
			mob.addStatusEffect(glowEffect());
		}
		Scoreboard sb = world.getScoreboard();
		Team team = sb.getTeam(ENEMY_TEAM);
		if (team == null) {
			team = sb.addTeam(ENEMY_TEAM);
			team.setColor(Formatting.RED);
		}
		sb.addScoreHolderToTeam(mob.getUuidAsString(), team);
	}

	/** The permanent, hidden-particle {@code GLOWING} outline effect applied to wave enemies —
	 *  a near-infinite duration so it never lapses mid-run. Built here so the on-spawn path
	 *  ({@link #markEnemyVisible}) and the live re-apply path ({@link #toggleEnemyGlow}) stay in
	 *  lockstep (identical duration/amplifier/flags). */
	private static StatusEffectInstance glowEffect() {
		return new StatusEffectInstance(StatusEffects.GLOWING, Integer.MAX_VALUE, 0, false, false, false);
	}

	/**
	 * Flip the GLOBAL enemy-outline toggle and immediately re-sync every live wave enemy in the
	 * arena world to the new state, then broadcast a short action-bar note to all players.
	 *
	 * <p>Server-authoritative and shared: this is a single mod-wide flag, so any player's keybind
	 * press toggles the outline for EVERYONE (the broadcast makes that clear). Turning it OFF
	 * strips the {@code GLOWING} effect from every {@code td_enemy}-tagged mob currently on the
	 * field; turning it ON re-applies the long, refreshed {@link #glowEffect()} to them all —
	 * mirroring exactly how {@link #markEnemyVisible} applies it on spawn. The RED
	 * {@link #ENEMY_TEAM} colour assignment is left untouched either way. Works mid-wave, and new
	 * spawns automatically honour the flag because {@link #markEnemyVisible} checks it.
	 *
	 * @param server the running server (used to reach the arena world + broadcast feedback)
	 */
	public static void toggleEnemyGlow(MinecraftServer server) {
		enemyGlowEnabled = !enemyGlowEnabled;
		TdArenaState st = TdArenaState.get(server);
		ServerWorld world = st.getArenaWorld(server);
		// Update every enemy currently alive in the arena. Guard on base != null because the
		// arena bounding box (enemies(...)) is anchored on the Idol; with no arena set there are
		// simply no live enemies to re-sync, so we just flip the flag + tell players.
		if (world != null && st.base != null) {
			for (Entity e : enemies(world, st)) {
				if (!(e instanceof MobEntity mob)) {
					continue;
				}
				if (enemyGlowEnabled) {
					mob.addStatusEffect(glowEffect());
				} else {
					mob.removeStatusEffect(StatusEffects.GLOWING);
				}
			}
		}
		broadcast(server, Text.literal("Enemy outlines: " + (enemyGlowEnabled ? "ON" : "OFF")
			+ " (shared toggle for all players)")
			.formatted(enemyGlowEnabled ? Formatting.GREEN : Formatting.GRAY));
	}

	private static void beelineToBase(MobEntity mob, TdArenaState st) {
		if (st.base == null) {
			return;
		}
		mob.getMoveControl().moveTo(st.base.getX() + 0.5, st.base.getY() + 0.5,
			st.base.getZ() + 0.5, NAV_SPEED);
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
	/** Drop the wave-completion loot bundle at one random enemy spawn gate. */
	private static void dropWaveReward(MinecraftServer server, ServerWorld world, TdArenaState st) {
		if (st.spawnPoints.isEmpty()) {
			return;
		}
		boolean bossWave = BOSS_WAVE_INTERVAL > 0 && st.currentWave % BOSS_WAVE_INTERVAL == 0;
		List<ItemStack> loot = WaveRewards.rollDrops(st.currentWave, bossWave, world.random);
		BlockPos gate = surfaceSpawn(world, st.spawnPoints.get(world.random.nextInt(st.spawnPoints.size())));
		for (ItemStack stack : loot) {
			ItemEntity drop = new ItemEntity(world, gate.getX() + 0.5, gate.getY() + 0.5, gate.getZ() + 0.5, stack);
			drop.setToDefaultPickupDelay();
			drop.setVelocity((world.random.nextDouble() - 0.5) * 0.2, 0.2, (world.random.nextDouble() - 0.5) * 0.2);
			world.spawnEntity(drop);
		}
		broadcast(server, Text.literal("Wave " + st.currentWave + " rewards dropped at a spawn gate!")
			.formatted(Formatting.AQUA));
	}

	private static void payNearbyPlayers(ServerWorld world, TdArenaState st, int coins) {
		// Shared gold: every player in the arena world receives the SAME wave payout so
		// co-op teammates stay in lockstep. RPG Fortune is applied as a TEAM bonus — we use
		// the BEST coin multiplier across all online players (the luckiest member's Fortune
		// benefits the whole team) rather than each player's own, which would desync balances.
		double bestCoinMult = 1.0;
		MinecraftServer server = world.getServer();
		if (server != null) {
			for (ServerPlayerEntity online : server.getPlayerManager().getPlayerList()) {
				bestCoinMult = Math.max(bestCoinMult, ProgressLookup.coinMult(online));
			}
		}
		int paid = Math.max(1, (int) Math.round(coins * bestCoinMult));
		for (ServerPlayerEntity player : world.getPlayers()) {
			ItemEntity drop = new ItemEntity(world, player.getX(), player.getBodyY(0.5), player.getZ(),
				new ItemStack(ModItems.COIN, paid));
			// Coins are BANK income, never inventory items: infinite pickup delay disables
			// vanilla walk-over pickup, leaving the server-side vacuum sweep the only collector.
			drop.setPickupDelayInfinite();
			world.spawnEntity(drop);
		}
	}

	private static void loseGame(MinecraftServer server, ServerWorld world, TdArenaState st) {
		st.gameOver = true;
		st.phase = TdArenaState.Phase.IDLE;
		st.baseHp = 0;
		// Drop the shared wall-damage pool so it can't leak across matches.
		WALL_DAMAGE.clear();
		PLANNED_QUEUE.clear();
		activeEmphasis = null;
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
