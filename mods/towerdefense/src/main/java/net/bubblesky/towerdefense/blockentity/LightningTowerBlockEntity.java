package net.bubblesky.towerdefense.blockentity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.bubblesky.towerdefense.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * The lightning tower: a slow-firing, high-cost, high-impact turret. It smites the
 * nearest hostile with a lightning bolt and then arcs to a couple of nearby enemies
 * — the "call down the storm" tower. Its whole design goal is drama <em>without</em>
 * clutter: unlike the arrow/ball towers (whose arrows pile up on the ground), a
 * lightning strike is instantaneous and leaves ZERO lingering projectiles.
 *
 * <p>How the strike works, and why nothing lingers:
 * <ul>
 *   <li><b>Visual only.</b> The bolt is a {@link LightningEntity} spawned with
 *       {@link LightningEntity#setCosmetic(boolean) setCosmetic(true)}, so it does NOT
 *       set fires, does NOT damage entities, and does NOT destroy blocks. It plays its
 *       flash/thunder animation and despawns on its own after a few ticks — no
 *       projectile entity, no dropped items, no scorch, no ground clutter.</li>
 *   <li><b>Damage is applied manually</b> via an owner-credited {@link DamageSource}
 *       (the placer's {@code playerAttack}, falling back to the vanilla lightning-bolt
 *       source, then {@code magic}) so tower kills still pay coins to the builder, exactly
 *       like the cannon/frost towers.</li>
 * </ul>
 *
 * <p>The primary target takes full {@link #LIGHTNING_DAMAGE} (scaled by tier). The
 * strike then <b>chains</b> to up to {@link #CHAIN_TARGETS} additional hostiles within
 * {@link #CHAIN_RANGE} blocks of the primary, each taking {@link #CHAIN_DAMAGE_FACTOR}
 * of that damage and each getting its own small cosmetic bolt so the arc is visible.
 */
public class LightningTowerBlockEntity extends AbstractTowerBlockEntity {
	private static final double BASE_RANGE = 24.0;
	private static final int BASE_COOLDOWN = 45;
	private static final double LIGHTNING_DAMAGE = 10.0;
	/** How far the arc reaches from the primary target when chaining. */
	private static final double CHAIN_RANGE = 5.0;
	/** How many extra enemies the strike jumps to after the primary. */
	private static final int CHAIN_TARGETS = 2;
	/** Chained enemies take this fraction of the primary hit. */
	private static final double CHAIN_DAMAGE_FACTOR = 0.6;

	public LightningTowerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.LIGHTNING_TOWER, pos, state);
	}

	@Override
	public net.bubblesky.towerdefense.tower.TowerKind kind() {
		return net.bubblesky.towerdefense.tower.TowerKind.LIGHTNING;
	}

	@Override
	protected double baseRange() {
		return BASE_RANGE;
	}

	@Override
	protected int baseCooldown() {
		return BASE_COOLDOWN;
	}

	@Override
	protected void fire(ServerWorld world, double cx, double cy, double cz, HostileEntity target) {
		ServerPlayerEntity owner = placerPlayer(world);
		// Owner-credited damage source (player > lightning > magic) so tower kills pay coins.
		DamageSource source = owner != null
			? world.getDamageSources().playerAttack(owner)
			: world.getDamageSources().lightningBolt();
		float primaryDamage = (float) (LIGHTNING_DAMAGE * damageMultiplier());

		// Smite + damage the primary target.
		strike(world, owner, target.getX(), target.getBodyY(0.0), target.getZ());
		if (target.isAlive()) {
			damageAndCredit(world, target, source, primaryDamage);
		}

		// Chain: jump to the nearest hostiles within CHAIN_RANGE of the primary (not the
		// primary itself), closest first, up to CHAIN_TARGETS. Each takes reduced damage
		// and gets its own small cosmetic bolt so the arc reads visually.
		float chainDamage = (float) (primaryDamage * CHAIN_DAMAGE_FACTOR);
		for (HostileEntity mob : nearbyChainTargets(world, target)) {
			strike(world, owner, mob.getX(), mob.getBodyY(0.0), mob.getZ());
			if (mob.isAlive()) {
				damageAndCredit(world, mob, source, chainDamage);
			}
		}

		// Thunder impact, kept quiet so a field of these towers doesn't deafen players.
		world.playSound(null, target.getX(), target.getY(), target.getZ(),
			SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.BLOCKS, 0.3f, 1.5f);
	}

	/**
	 * Gather up to {@link #CHAIN_TARGETS} hostiles within {@link #CHAIN_RANGE} of the
	 * primary target (excluding it and any dead mobs), ordered nearest-first.
	 */
	private List<HostileEntity> nearbyChainTargets(ServerWorld world, HostileEntity primary) {
		Box box = new Box(primary.getBlockPos()).expand(CHAIN_RANGE);
		List<HostileEntity> candidates = new ArrayList<>();
		for (HostileEntity mob : world.getNonSpectatingEntities(HostileEntity.class, box)) {
			if (mob == primary || !mob.isAlive()) {
				continue;
			}
			if (mob.squaredDistanceTo(primary) <= CHAIN_RANGE * CHAIN_RANGE) {
				candidates.add(mob);
			}
		}
		candidates.sort(Comparator.comparingDouble(m -> m.squaredDistanceTo(primary)));
		if (candidates.size() > CHAIN_TARGETS) {
			return candidates.subList(0, CHAIN_TARGETS);
		}
		return candidates;
	}

	/**
	 * Spawn a purely-cosmetic lightning bolt at the given point plus a burst of electric
	 * sparks. {@code setCosmetic(true)} makes the bolt visual-only (no fire, no damage, no
	 * block destruction); it despawns on its own, so nothing lingers in the world.
	 */
	private void strike(ServerWorld world, ServerPlayerEntity owner, double x, double y, double z) {
		// No cosmetic LightningEntity: its vanilla thunderclap plays at ~volume 10 and is heard
		// across the world, which is deafening when a field of these towers fires constantly.
		// Instead we render the bolt purely with particles (fully volume-controlled) — a bright
		// vertical streak of sparks down onto the target plus a crackle at the impact point.
		int t = getTier();
		// Vertical "bolt" streak: a column of sparks from a few blocks up down to the target.
		for (double dy = 0; dy <= 5.0; dy += 0.5) {
			world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, x, y + dy, z,
				2, 0.06, 0.06, 0.06, 0.0);
		}
		// A crackle of electric sparks at the strike point (scaled up per tier).
		world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, x, y + 1.0, z,
			20 + 8 * t, 0.4, 0.8, 0.4, 0.25);
		world.spawnParticles(ParticleTypes.END_ROD, x, y + 1.0, z,
			6 + 2 * t, 0.2, 0.6, 0.2, 0.05);
	}
}
