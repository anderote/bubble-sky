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
		float damage = (float) (FLAME_DAMAGE * damageMultiplier());

		double tx = target.getX();
		double ty = target.getBodyY(0.5);
		double tz = target.getZ();

		// 1) The visible flame stream: a cone of fire from muzzle to target.
		sprayCone(world, cx, cy, cz, tx, ty, tz);

		// 2) Torch every hostile bunched around the aim point.
		Box cone = new Box(target.getBlockPos()).expand(CONE_RADIUS);
		for (HostileEntity mob : world.getNonSpectatingEntities(HostileEntity.class, cone)) {
			if (mob.isAlive() && mob.squaredDistanceTo(target) <= CONE_RADIUS * CONE_RADIUS) {
				mob.setOnFireFor(BURN_SECONDS);
				damageAndCredit(world, mob, source, damage);
			}
		}

		// 3) Lay down a lingering burning-ground patch under the target's feet.
		addBurningPatch(world, target);

		// Fire whoosh, kept quiet so a field of these towers doesn't roar.
		world.playSound(null, cx, cy, cz, SoundEvents.ITEM_FIRECHARGE_USE,
			SoundCategory.BLOCKS, 0.4f, 0.9f + world.random.nextFloat() * 0.2f);
	}

	/**
	 * Draw the flamethrower's jet: step from the muzzle to the target, spawning FLAME
	 * along the whole line, SMALL_FLAME for body, and the occasional LAVA gob for grit.
	 * The spread widens with distance from the muzzle so the stream reads as a cone, and
	 * grows a little per tier so upgraded towers throw a fatter flame.
	 */
	private void sprayCone(ServerWorld world, double cx, double cy, double cz,
			double tx, double ty, double tz) {
		double dx = tx - cx;
		double dy = ty - cy;
		double dz = tz - cz;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (dist < 1.0e-3) {
			return;
		}
		int t = getTier();
		// One flame step roughly every half block along the line.
		int steps = Math.max(4, (int) (dist * 2.0));
		double coneWidth = 0.6 + 0.1 * t;
		for (int i = 0; i <= steps; i++) {
			double f = (double) i / steps;
			double px = cx + dx * f;
			double py = cy + dy * f;
			double pz = cz + dz * f;
			// Spread grows toward the target end so the jet fans out into a cone.
			double spread = coneWidth * f;
			world.spawnParticles(ParticleTypes.FLAME, px, py, pz, 2 + t, spread, spread, spread, 0.01);
			world.spawnParticles(ParticleTypes.SMALL_FLAME, px, py, pz, 3 + t, spread, spread, spread, 0.0);
			if (i % 3 == 0) {
				world.spawnParticles(ParticleTypes.LAVA, px, py, pz, 1, spread * 0.5, spread * 0.5, spread * 0.5, 0.0);
			}
			world.spawnParticles(ParticleTypes.SMOKE, px, py + 0.15, pz, 1, spread * 0.5, spread * 0.5, spread * 0.5, 0.01);
		}
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
		float damage = (float) (TILE_DAMAGE * damageMultiplier());

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
				damageAndCredit(world, mob, source, damage);
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
