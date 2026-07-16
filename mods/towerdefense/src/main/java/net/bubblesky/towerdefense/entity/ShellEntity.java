package net.bubblesky.towerdefense.entity;

import net.bubblesky.towerdefense.registry.ModEntities;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.hit.HitResult;

/**
 * The CANNON tower's ARTILLERY SHELL — the flashy replacement for the old cosmetic
 * snowball. A fast, dark projectile that <em>whistles</em> a smoke trail across the
 * arena and <strong>bursts in a real explosion effect</strong> (flash + emitter puff
 * + smoke shockwave + boom) the instant it lands, then vanishes.
 *
 * <p>Purely a <em>visual</em> piece: the shell itself deals <strong>no damage</strong>
 * and triggers <strong>no world/block explosion or fire</strong>. The cannon tower keeps
 * applying its own splash damage exactly as before (see
 * {@code CannonTowerBlockEntity#fire}); this entity only carries the cinematic.
 *
 * <p>Built on {@link ThrownItemEntity} (the same base the snowball used) so it renders
 * cleanly as a flying item via {@code FlyingItemEntityRenderer}. Its whole life cycle is
 * tuned to be brief and self-cleaning:
 * <ul>
 *   <li><b>Renders as a dark charge</b> — {@link #getDefaultItem()} is a
 *       {@code FIRE_CHARGE}, drawn spinning by {@code ShellEntityRenderer}.</li>
 *   <li><b>Whistles a trail</b> — {@link #tick()} lays down a ribbon of smoke (with the
 *       odd puff of campfire smoke) every tick so the shell reads as an incoming round.</li>
 *   <li><b>Detonates on impact</b> — {@link #onCollision} spawns the explosion visual and
 *       {@link #discard()}s. No {@code super} damage dispatch, so it never hurts anything.</li>
 *   <li><b>Self-destructs</b> — a stray shell that never connects is force-detonated after
 *       {@link #MAX_LIFE} ticks, so nothing lingers in the air.</li>
 * </ul>
 */
public class ShellEntity extends ThrownItemEntity {

	/**
	 * Lifespan cap in ticks (20 ticks = 1 second). A shell that somehow hits nothing is
	 * force-detonated after this so the arena never accumulates in-flight rounds. Three
	 * seconds is comfortably longer than the ~1s it takes to cross the cannon's range.
	 */
	private static final int MAX_LIFE = 60;

	/**
	 * Downward pull per tick. Deliberately lighter than the vanilla thrown default of
	 * {@code 0.03} so the shell flies a flatter, faster arc — reading as an artillery
	 * round rather than a lobbed snowball.
	 */
	private static final double SHELL_GRAVITY = 0.02;

	/** Ticks this shell has been alive; drives the {@link #MAX_LIFE} self-destruct. */
	private int lifeTicks;

	/** Registry-factory constructor (used by {@code EntityType} + client spawns). */
	public ShellEntity(EntityType<? extends ShellEntity> type, net.minecraft.world.World world) {
		super(type, world);
	}

	/**
	 * Muzzle constructor: spawns the shell at an arbitrary world position (the cannon's
	 * muzzle cx/cy/cz) rather than from a living shooter's eye. The firing tower's owner is
	 * credited separately via {@link #setOwner}; the shell deals no damage itself.
	 */
	public ShellEntity(net.minecraft.world.World world, double x, double y, double z) {
		super(ModEntities.SHELL, x, y, z, world, new ItemStack(Items.FIRE_CHARGE));
	}

	@Override
	protected Item getDefaultItem() {
		return Items.FIRE_CHARGE;
	}

	@Override
	protected double getGravity() {
		return SHELL_GRAVITY;
	}

	@Override
	public void tick() {
		super.tick();
		// Whistling smoke trail: a thin ribbon of smoke every tick, with an occasional
		// puff of campfire smoke so the round reads as a hot, incoming shell. Server-spawned
		// so every nearby client sees the same trail.
		if (this.getWorld() instanceof ServerWorld world && this.isAlive()) {
			world.spawnParticles(ParticleTypes.SMOKE, this.getX(), this.getY(), this.getZ(),
				2, 0.02, 0.02, 0.02, 0.0);
			if ((lifeTicks & 1) == 0) {
				world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, this.getX(), this.getY(), this.getZ(),
					1, 0.01, 0.01, 0.01, 0.0);
			}
		}
		// Self-destruct on a miss: a stray shell bursts and is gone in well under three seconds.
		if (++lifeTicks >= MAX_LIFE) {
			this.detonate();
		}
	}

	@Override
	protected void onCollision(HitResult hitResult) {
		// Any impact — block or entity — ends the shell in a burst. We deliberately do NOT
		// call super.onCollision(): there is no damage/deflection to dispatch, and skipping it
		// guarantees the shell never harms whatever it grazed (the tower owns all the damage).
		this.detonate();
	}

	/**
	 * The money shot: a one-off explosion effect at the shell's current position — a bright
	 * {@link net.minecraft.particle.ParticleTypes#FLASH flash}, an
	 * {@link net.minecraft.particle.ParticleTypes#EXPLOSION_EMITTER emitter} puff, a cluster
	 * of {@link net.minecraft.particle.ParticleTypes#EXPLOSION explosion} smoke, an outward
	 * {@code LARGE_SMOKE} shockwave ring, lingering {@code SMOKE}, and a moderate cannon boom —
	 * then the shell is discarded. Purely cosmetic: no world explosion, no block damage, no fire.
	 */
	private void detonate() {
		if (this.getWorld() instanceof ServerWorld world) {
			double x = this.getX();
			double y = this.getY();
			double z = this.getZ();
			// Muzzle-bright flash + the vanilla explosion emitter (the animated fireball puff).
			world.spawnParticles(ParticleTypes.FLASH, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
			world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
			// A tight cluster of explosion puffs at the core.
			world.spawnParticles(ParticleTypes.EXPLOSION, x, y, z, 6, 0.4, 0.3, 0.4, 0.0);
			// Body of the blast: a ball of large smoke.
			world.spawnParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 24, 0.5, 0.4, 0.5, 0.02);
			// Shockwave: a ring of large smoke shoved outward along the ground so the blast
			// visibly expands rather than just puffing in place.
			int spokes = 16;
			for (int i = 0; i < spokes; i++) {
				double angle = (Math.PI * 2.0 * i) / spokes;
				double vx = Math.cos(angle) * 0.35;
				double vz = Math.sin(angle) * 0.35;
				world.spawnParticles(ParticleTypes.LARGE_SMOKE, x, y + 0.1, z, 0, vx, 0.02, vz, 1.0);
			}
			// Lingering rising smoke after the flash clears.
			world.spawnParticles(ParticleTypes.SMOKE, x, y + 0.2, z, 12, 0.4, 0.3, 0.4, 0.01);
			// The boom — a beefy cannon report at moderate volume.
			world.playSound(null, x, y, z, SoundEvents.ENTITY_GENERIC_EXPLODE,
				SoundCategory.BLOCKS, 0.7f, 0.9f);
		}
		this.discard();
	}

	@Override
	protected void writeCustomData(WriteView view) {
		super.writeCustomData(view);
		view.putInt("LifeTicks", lifeTicks);
	}

	@Override
	protected void readCustomData(ReadView view) {
		super.readCustomData(view);
		this.lifeTicks = view.getInt("LifeTicks", 0);
	}
}
