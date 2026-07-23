package net.bubblesky.towerdefense.blockentity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

/**
 * The flamethrower tower: a fast, continuous close-range incinerator. Where the
 * cannon lobs a single heavy shot and the lightning tower snipes with drama, the
 * flamethrower is all about <em>sustained</em> area denial — it fires on a very
 * short cooldown, sprays a visible cone of flame at the nearest hostile, torches
 * everything bunched around that target, and leaves a lingering patch of burning
 * ground the swarm has to walk through.
 *
 * <p>Three distinct effects, all owner-credited so tower kills still pay coins to
 * the builder (via the placer's {@code playerAttack} source, falling back to the
 * vanilla {@code onFire} source):
 * <ul>
 *   <li><b>The flame stream.</b> On every shot {@link #fire} draws a cone of
 *       {@link net.minecraft.particle.ParticleTypes#FLAME FLAME} /
 *       {@link net.minecraft.particle.ParticleTypes#SMALL_FLAME SMALL_FLAME} /
 *       {@link net.minecraft.particle.ParticleTypes#LAVA LAVA} particles from the
 *       muzzle toward the target — a visible jet of fire that widens as it travels,
 *       so it reads unmistakably as a flamethrower.</li>
 *   <li><b>The forward torch.</b> Every hostile within {@link #CONE_RADIUS} blocks
 *       of the aim point is set on fire for {@link #BURN_SECONDS}s <em>and</em>
 *       takes {@link #FLAME_DAMAGE} (scaled by tier) of owner-credited fire damage.</li>
 *   <li><b>The burning ground.</b> A 3x3 patch of ground tiles under the target's
 *       feet is registered as "burning" for {@link #TILE_LIFETIME_TICKS} ticks.
 *       Crucially this places <b>NO real fire blocks</b> — nothing that could spread
 *       to and consume the player's fort. The patch is tracked purely in-memory as a
 *       {@link BlockPos} → expiry-tick map; a dedicated per-tick pass ({@link #tickFlames})
 *       spawns fire/smoke particles at each live tile and burns any hostile standing
 *       on it. Tiles simply expire and vanish.</li>
 * </ul>
 *
 * <p><b>Why a custom ticker.</b> The base {@link AbstractTowerBlockEntity#tick} pass
 * respects the fire cooldown and so only runs on the shot cadence — but the burning
 * ground must keep hurting enemies and puffing smoke on <em>every</em> tick, between
 * shots. So the flame block wires up {@link #serverTick}, which calls the shared
 * {@link AbstractTowerBlockEntity#tick} (targeting/firing) AND {@link #tickFlames}
 * (the lingering-zone pass) every tick.
 *
 * <p>The burning-tile set is transient — it is deliberately NOT persisted across a
 * reload (a few seconds of ground fire vanishing on chunk reload is harmless) and is
 * kept bounded to {@link #MAX_TILES} entries so it can never grow without limit.
 */
public class FlameTowerBlockEntity extends AbstractTowerBlockEntity {
	private static final double BASE_RANGE = 12.0;
	private static final int BASE_COOLDOWN = 10;
	/** Direct fire damage dealt to each mob caught in the forward cone, per shot (pre-tier). */
	private static final double FLAME_DAMAGE = 3.0;
	/** Blocks around the aim point whose hostiles get torched by a shot. */
	private static final double CONE_RADIUS = 6.0;
	/** Seconds of burning applied by the flame stream / burning ground. */
	private static final float BURN_SECONDS = 4.0f;

	/** Half-width of the ground patch dropped under the target (1 => 3x3). */
	private static final int PATCH_RADIUS = 1;
	/** How long a burning-ground tile stays hot (~3s). */
	private static final int TILE_LIFETIME_TICKS = 60;
	/** Hard cap on tracked burning tiles so the map can never balloon. */
	private static final int MAX_TILES = 32;
	/** Live burning-ground tiles do damage on this cadence (every 10 ticks ≈ 0.5s). */
	private static final int TILE_DAMAGE_INTERVAL = 10;
	/** Fire damage a mob takes each time it is standing on a burning tile (pre-tier). */
	private static final double TILE_DAMAGE = 1.0;

	/**
	 * Active burning-ground tiles: ground {@link BlockPos} → absolute game-time tick at
	 * which the tile expires. Transient (not saved), bounded to {@link #MAX_TILES}.
	 */
	private final Map<BlockPos, Long> burningTiles = new HashMap<>();

	public FlameTowerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.FLAME_TOWER, pos, state);
	}

	@Override
	public net.bubblesky.towerdefense.tower.TowerKind kind() {
		return net.bubblesky.towerdefense.tower.TowerKind.FLAME;
	}

	@Override
	protected double baseRange() {
		return BASE_RANGE;
	}

	@Override
	protected int baseCooldown() {
		return BASE_COOLDOWN;
	}

	/**
	 * Custom ticker for the flame block: runs the shared targeting/firing pass AND the
	 * lingering burning-ground pass every tick. Wire it up in the block's {@code getTicker}
	 * via {@code validateTicker(type, FLAME_TOWER, FlameTowerBlockEntity::serverTick)}.
	 */
	public static void serverTick(World world, BlockPos pos, BlockState state, FlameTowerBlockEntity be) {
		// Targeting + firing on the fire cooldown (shared with every other tower).
		AbstractTowerBlockEntity.tick(world, pos, state, be);
		// Burning-ground zone: must run every tick, independent of the fire cooldown.
		if (world instanceof ServerWorld serverWorld) {
			be.tickFlames(serverWorld);
		}
	}

	/** Convenience so callers can grab a correctly-typed ticker without repeating the cast. */
	public static BlockEntityTicker<FlameTowerBlockEntity> ticker() {
		return FlameTowerBlockEntity::serverTick;
	}

	@Override
	protected void fire(ServerWorld world, double cx, double cy, double cz, HostileEntity target) {
		ServerPlayerEntity owner = placerPlayer(world);
		// Owner-credited fire damage (player > vanilla on-fire) so tower kills pay coins.
		DamageSource source = owner != null
			? world.getDamageSources().playerAttack(owner)
			: world.getDamageSources().onFire();
		double tx = target.getX();
		double ty = target.getBodyY(0.5);
		double tz = target.getZ();

		// 1) The visible flame stream: a directional jet of fire from muzzle to target.
		sprayFlameJet(world, cx, cy, cz, tx, ty, tz);

		// 2) Torch every hostile bunched around the aim point.
		Box cone = new Box(target.getBlockPos()).expand(CONE_RADIUS);
		for (HostileEntity mob : world.getNonSpectatingEntities(HostileEntity.class, cone)) {
			if (mob.isAlive() && mob.squaredDistanceTo(target) <= CONE_RADIUS * CONE_RADIUS) {
				mob.setOnFireFor(BURN_SECONDS);
				damageAndCredit(world, mob, source,
					(float) (FLAME_DAMAGE * damageMultiplier(mob)));
			}
		}

		// 3) Lay down a lingering burning-ground patch under the target's feet.
		addBurningPatch(world, target);

		// Fire whoosh, kept quiet so a field of these towers doesn't roar.
		world.playSound(null, cx, cy, cz, SoundEvents.ITEM_FIRECHARGE_USE,
			SoundCategory.BLOCKS, 0.4f, 0.9f + world.random.nextFloat() * 0.2f);
	}

	/**
	 * Draw the flamethrower's JET: a dense, directional stream of fire shot from the muzzle
	 * straight at the target, so it reads unmistakably as a flamethrower rather than a puff of
	 * scattered flames.
	 *
	 * <p>The key is HOW the particles are spawned. Rather than the vanilla "count &gt; 0" mode
	 * (which scatters particles with random velocities), every jet particle is emitted in the
	 * <b>count == 0</b> mode of {@link ServerWorld#spawnParticles}: the delta-x/y/z arguments are
	 * then interpreted as the particle's <em>velocity</em> and the {@code speed} arg as its scale,
	 * so each flame is launched with a real velocity pointing down-range at the target. There is no
	 * positional spread — the fan-out comes purely from jittering the velocity DIRECTION within a
	 * narrow cone that widens toward the target end, so the stream flares out like a real nozzle.
	 *
	 * <p>Three layers give the jet depth:
	 * <ul>
	 *   <li>a bright, fast {@link ParticleTypes#SMALL_FLAME SMALL_FLAME} <b>core</b> (tight cone,
	 *       highest velocity),</li>
	 *   <li>a fuller {@link ParticleTypes#FLAME FLAME} <b>body</b> around it (wider cone), and</li>
	 *   <li>a slow, wispy {@link ParticleTypes#CAMPFIRE_COSY_SMOKE}/{@link ParticleTypes#SMOKE}
	 *       <b>trail</b> plus the occasional {@link ParticleTypes#LAVA LAVA} gob for grit.</li>
	 * </ul>
	 * A {@link ParticleTypes#FLAME}/{@link ParticleTypes#LARGE_SMOKE} <b>burst</b> at the target
	 * point sells the impact. Everything scales a little with {@link #getTier() tier} so upgraded
	 * towers throw a fatter, faster flame. This is purely cosmetic — no mechanics touched.
	 */
	private void sprayFlameJet(ServerWorld world, double cx, double cy, double cz,
			double tx, double ty, double tz) {
		double dx = tx - cx;
		double dy = ty - cy;
		double dz = tz - cz;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (dist < 1.0e-3) {
			return;
		}
		// Unit direction muzzle -> target: the axis every jet particle is fired along.
		double nx = dx / dist;
		double ny = dy / dist;
		double nz = dz / dist;
		int t = getTier();
		Random rnd = world.random;
		// Base down-range speed of the jet, a touch faster per tier so the stream reaches further.
		double jetSpeed = 0.9 + 0.12 * t;
		// Emit at ~2.5 points per block so the stream is continuous, not dotted.
		int steps = Math.max(6, (int) (dist * 2.5));
		for (int i = 0; i <= steps; i++) {
			double f = (double) i / steps;
			double px = cx + dx * f;
			double py = cy + dy * f;
			double pz = cz + dz * f;
			// Cone half-angle grows toward the target end so the jet fans out like a nozzle.
			double cone = 0.05 + 0.20 * f + 0.02 * t;

			// Bright, fast core: SMALL_FLAME in a tight cone at the highest velocity.
			for (int k = 0; k < 2 + t; k++) {
				emitDirected(world, ParticleTypes.SMALL_FLAME, px, py, pz, nx, ny, nz, cone * 0.5, jetSpeed * 1.25, rnd);
			}
			// Fuller body of FLAME around the core in a wider cone.
			for (int k = 0; k < 3 + t; k++) {
				emitDirected(world, ParticleTypes.FLAME, px, py, pz, nx, ny, nz, cone, jetSpeed, rnd);
			}
			// Wispy smoke trailing the flame, slower so it lags behind the front.
			if (i % 2 == 0) {
				emitDirected(world, ParticleTypes.CAMPFIRE_COSY_SMOKE, px, py + 0.05, pz, nx, ny, nz, cone, jetSpeed * 0.45, rnd);
			}
			// Occasional molten gob for grit.
			if (i % 4 == 0) {
				emitDirected(world, ParticleTypes.LAVA, px, py, pz, nx, ny, nz, cone * 0.7, jetSpeed * 0.8, rnd);
			}
		}

		// Impact burst at the target so the far end of the jet "splashes" into fire.
		world.spawnParticles(ParticleTypes.FLAME, tx, ty, tz, 12 + 2 * t, 0.2, 0.2, 0.2, 0.06);
		world.spawnParticles(ParticleTypes.SMALL_FLAME, tx, ty, tz, 10 + 2 * t, 0.15, 0.15, 0.15, 0.09);
		world.spawnParticles(ParticleTypes.LARGE_SMOKE, tx, ty, tz, 4, 0.2, 0.25, 0.2, 0.02);
	}

	/**
	 * Emit ONE particle launched down-range along {@code (nx,ny,nz)} with its direction jittered
	 * inside a {@code spread}-wide cone, at {@code speed}. Uses the {@code count == 0} form of
	 * {@link ServerWorld#spawnParticles} so the delta args carry the particle's VELOCITY (not a
	 * positional offset): the flame actually flies toward the target instead of milling in place.
	 */
	private static void emitDirected(ServerWorld world, net.minecraft.particle.ParticleEffect particle,
			double px, double py, double pz, double nx, double ny, double nz,
			double spread, double speed, Random rnd) {
		double vx = nx + (rnd.nextDouble() - 0.5) * spread;
		double vy = ny + (rnd.nextDouble() - 0.5) * spread;
		double vz = nz + (rnd.nextDouble() - 0.5) * spread;
		double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
		if (len < 1.0e-6) {
			len = 1.0;
		}
		double s = speed / len;
		// count == 0 => (dx,dy,dz) is the velocity, last arg scales it. No positional spread.
		world.spawnParticles(particle, px, py, pz, 0, vx * s, vy * s, vz * s, 1.0);
	}

	/**
	 * Register a 3x3 patch of "burning ground" tiles under the target's feet, each with a
	 * fresh {@link #TILE_LIFETIME_TICKS}-tick expiry. NO real fire block is placed — the tiles
	 * live only in {@link #burningTiles}, so they can never spread to or consume the player's
	 * fort. Refreshing a tile already burning just resets its expiry. The map is trimmed to
	 * {@link #MAX_TILES} (oldest-expiry first) after each patch so it stays bounded.
	 */
	private void addBurningPatch(ServerWorld world, HostileEntity target) {
		long expiry = world.getTime() + TILE_LIFETIME_TICKS;
		BlockPos feet = target.getBlockPos();
		for (int ox = -PATCH_RADIUS; ox <= PATCH_RADIUS; ox++) {
			for (int oz = -PATCH_RADIUS; oz <= PATCH_RADIUS; oz++) {
				burningTiles.put(feet.add(ox, 0, oz), expiry);
			}
		}
		trimTiles();
	}

	/** Drop the soonest-to-expire tiles until we're back under {@link #MAX_TILES}. */
	private void trimTiles() {
		while (burningTiles.size() > MAX_TILES) {
			Map.Entry<BlockPos, Long> soonest = null;
			for (Map.Entry<BlockPos, Long> e : burningTiles.entrySet()) {
				if (soonest == null || e.getValue() < soonest.getValue()) {
					soonest = e;
				}
			}
			if (soonest == null) {
				break;
			}
			burningTiles.remove(soonest.getKey());
		}
	}

	/**
	 * Per-tick burning-ground pass (called every tick by {@link #serverTick}, NOT gated by
	 * the fire cooldown). For each live tile it spawns fire/smoke particles so the patch is
	 * visible, and — on the {@link #TILE_DAMAGE_INTERVAL} cadence — sets on fire and deals a
	 * small owner-credited hit to any hostile standing in or just above it. Expired tiles are
	 * dropped. Places NO blocks, so the player's builds are never at risk.
	 */
	public void tickFlames(ServerWorld world) {
		if (burningTiles.isEmpty()) {
			return;
		}
		long now = world.getTime();

		// Prune expired tiles first.
		Iterator<Map.Entry<BlockPos, Long>> it = burningTiles.entrySet().iterator();
		while (it.hasNext()) {
			if (it.next().getValue() <= now) {
				it.remove();
			}
		}
		if (burningTiles.isEmpty()) {
			return;
		}

		// Cosmetic: puff flame + smoke from the surface of each live tile.
		for (BlockPos tile : burningTiles.keySet()) {
			double px = tile.getX() + 0.5;
			double py = tile.getY() + 1.0;
			double pz = tile.getZ() + 0.5;
			world.spawnParticles(ParticleTypes.FLAME, px, py, pz, 2, 0.3, 0.1, 0.3, 0.01);
			world.spawnParticles(ParticleTypes.SMOKE, px, py + 0.1, pz, 1, 0.3, 0.2, 0.3, 0.01);
		}

		// Damage only on the interval so standing on fire chips away rather than instakills.
		if (now % TILE_DAMAGE_INTERVAL != 0) {
			return;
		}
		ServerPlayerEntity owner = placerPlayer(world);
		DamageSource source = owner != null
			? world.getDamageSources().playerAttack(owner)
			: world.getDamageSources().onFire();
		// One query over the bounding box of all tiles, then match by feet position.
		Box bounds = tileBounds();
		List<HostileEntity> mobs = world.getNonSpectatingEntities(HostileEntity.class, bounds);
		for (HostileEntity mob : mobs) {
			if (!mob.isAlive()) {
				continue;
			}
			// The ground tile a mob is standing in/above is the block at its feet or just below.
			BlockPos feet = mob.getBlockPos();
			if (burningTiles.containsKey(feet) || burningTiles.containsKey(feet.down())) {
				mob.setOnFireFor(BURN_SECONDS);
				damageAndCredit(world, mob, source,
					(float) (TILE_DAMAGE * damageMultiplier(mob)));
			}
		}
	}

	/** Axis-aligned box spanning every live burning tile, expanded a bit for feet-above matches. */
	private Box tileBounds() {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPos p : burningTiles.keySet()) {
			minX = Math.min(minX, p.getX());
			minY = Math.min(minY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX());
			maxY = Math.max(maxY, p.getY());
			maxZ = Math.max(maxZ, p.getZ());
		}
		// +1 in Y so mobs standing ON the tile (feet one block above) are still caught.
		return new Box(minX, minY, minZ, maxX + 1, maxY + 2, maxZ + 1);
	}
}
