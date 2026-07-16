package net.bubblesky.towerdefense.game;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.bubblesky.towerdefense.entity.TdEnemyEntity;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.AffineTransformation;
import org.joml.Vector3f;

/**
 * RPG-style floating DAMAGE NUMBERS over Tower Defense enemies — the little rising
 * "<code>7</code>" that pops off a mob when a <em>player</em> lands a hit.
 *
 * <p>This is a purely additive, server-driven, client-visible cosmetic. Each number is a
 * short-lived vanilla {@link DisplayEntity.TextDisplayEntity} (a text {@code display}
 * entity) spawned at the struck enemy's head: because display entities are ordinary
 * tracked entities, every nearby client renders them automatically with <em>no</em>
 * client mod, no packets, and no HUD changes. Nothing here reads or mutates damage,
 * health, coins, XP, the shop, or the wave state — it only <em>observes</em> the damage
 * event and paints a floating glyph.
 *
 * <h2>When a number fires</h2>
 * A marker is spawned from a {@link ServerLivingEntityEvents#AFTER_DAMAGE} listener, and
 * only when ALL of the following hold (see {@link #onAfterDamage}):
 * <ul>
 *   <li>the victim is a TD wave enemy (a {@link TdEnemyEntity}, or anything carrying the
 *       {@link WaveManager#ENEMY_TAG} command tag);</li>
 *   <li>the hit is <b>player-caused</b> — {@code source.getAttacker()} is a
 *       {@link ServerPlayerEntity}. This single test covers melee, bow shots, and
 *       player-cast spells alike, because all of those attribute their damage through the
 *       casting/attacking player (vanilla {@code playerAttack}); and</li>
 *   <li>the hit is <b>not a tower auto-shot</b> — a tower stamps
 *       {@link TdEnemyEntity#lastTowerHitAge} to the enemy's current {@code age} the instant
 *       before it applies its bolt, so a stamp equal to {@code age} this tick means a tower
 *       (not the player) struck, and no number is shown. Towers fire relentlessly; letting
 *       them spawn numbers would bury the screen in glyphs, so they are suppressed by
 *       default (see {@link #SHOW_TOWER_MARKERS} to re-enable later); and</li>
 *   <li>the applied {@code damageTaken} is strictly positive (a fully-absorbed / 0-damage
 *       hit paints nothing).</li>
 * </ul>
 *
 * <h2>Life of a marker</h2>
 * On spawn the display is configured to always face the camera ({@code billboard = CENTER}),
 * scaled small (~{@value #MARKER_SCALE}) via its {@link AffineTransformation}, given a
 * transparent background and a short view range, and placed just above the enemy's eyes with
 * a little horizontal jitter so overlapping hits fan out. Every server tick the
 * {@link #sweep() sweep} nudges each live marker upward ({@value #RISE_PER_TICK} blocks/tick)
 * and {@link net.minecraft.entity.Entity#discard() discards} it once it is older than
 * {@value #MARKER_LIFETIME_TICKS} ticks (~1s). The number of concurrently live markers is
 * capped at {@value #MAX_MARKERS}: once the cap is hit, further hits simply skip spawning a
 * glyph, so a big fight can never spew thousands of display entities.
 *
 * <p><b>Threading / lifecycle.</b> Both the damage event and the tick sweep run on the
 * server thread, so the plain {@link ArrayList} tracker needs no synchronization. The list
 * holds only ephemeral cosmetic entities; a restart forgets them (any orphaned display
 * simply expires on its own timer if it somehow survived), which is the intended cost-free
 * behavior for throwaway visual state. Register once from mod init via {@link #register()}.
 */
public final class DamageMarkers {

	private DamageMarkers() {
	}

	// ---- tuning ------------------------------------------------------------
	/**
	 * Master switch for TOWER-dealt numbers. Left {@code false} so only the player's own
	 * direct hits (melee / bow / spell) paint glyphs — towers fire far too often and would
	 * flood the screen. Flip to {@code true} to also show a number on every tower bolt.
	 */
	private static final boolean SHOW_TOWER_MARKERS = false;
	/** Uniform scale applied to each number's {@link AffineTransformation} (small, readable). */
	private static final float MARKER_SCALE = 0.7f;
	/** How far (blocks) above the enemy's eyes a fresh number spawns. */
	private static final double SPAWN_EYE_OFFSET = 0.4;
	/** Max horizontal jitter (blocks, +/-) applied at spawn so stacked hits fan out. */
	private static final double SPAWN_JITTER = 0.25;
	/** Blocks a live number drifts upward each server tick (gives the "rising damage" feel). */
	private static final double RISE_PER_TICK = 0.03;
	/** Ticks a number lives before it is discarded (20 ticks/sec, so ~0.95s). */
	private static final int MARKER_LIFETIME_TICKS = 19;
	/** View-range multiplier for the display (small — these are only worth seeing up close). */
	private static final float MARKER_VIEW_RANGE = 0.5f;
	/**
	 * Hard cap on concurrently live markers. A frantic multi-player fight against a full
	 * horde could otherwise try to spawn hundreds of display entities per second; past this
	 * cap new hits skip their glyph (the damage itself is unaffected).
	 */
	private static final int MAX_MARKERS = 80;

	// ---- damage → color bands ----------------------------------------------
	/** At/under this rounded damage a number is plain white (a chip hit). */
	private static final int WHITE_MAX = 4;
	/** At/under this rounded damage a number is yellow (a solid hit). */
	private static final int YELLOW_MAX = 9;
	/** At/under this rounded damage a number is gold (a heavy hit); above it, red (a crusher). */
	private static final int GOLD_MAX = 19;

	// ---- state (server-thread-only) ----------------------------------------
	/** Monotonic server-tick counter; each marker's spawn tick is compared against it to expire. */
	private static long tick;
	/** Every live floating number, in spawn order (oldest first). Swept once per tick. */
	private static final List<Marker> MARKERS = new ArrayList<>();

	/** One live floating number: its display entity plus the tick it was spawned on. */
	private record Marker(DisplayEntity.TextDisplayEntity display, long spawnTick) {
	}

	/**
	 * Register the damage listener and the once-per-tick sweep. Call once from mod init.
	 * The listener spawns numbers on qualifying player hits; the sweep animates and expires
	 * them (mirroring the manager sweeps in {@code SpellManager}).
	 */
	public static void register() {
		ServerLivingEntityEvents.AFTER_DAMAGE.register(DamageMarkers::onAfterDamage);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tick++;
			sweep();
		});
	}

	// ---- damage hook -------------------------------------------------------
	/**
	 * The {@link ServerLivingEntityEvents#AFTER_DAMAGE} callback: decide whether this hit
	 * earns a floating number and, if so, spawn one. See the class javadoc for the full
	 * player-hit vs tower-hit filter. Never throws for a non-qualifying hit — it just returns.
	 *
	 * @param entity          the entity that was hurt
	 * @param source          the damage source (used to identify a player attacker)
	 * @param baseDamageTaken the pre-mitigation damage (unused; kept for the event signature)
	 * @param damageTaken     the actual damage applied — the value we paint
	 * @param blocked         whether the hit was blocked (unused)
	 */
	private static void onAfterDamage(LivingEntity entity, DamageSource source,
			float baseDamageTaken, float damageTaken, boolean blocked) {
		if (!(entity.getWorld() instanceof ServerWorld world)) {
			return;
		}
		if (damageTaken <= 0.0f) {
			return;
		}
		// Victim must be a TD wave enemy (class or the shared td_enemy tag).
		if (!(entity instanceof TdEnemyEntity) && !entity.getCommandTags().contains(WaveManager.ENEMY_TAG)) {
			return;
		}
		// Player-caused only: melee, bow, and player-cast spells all resolve to the player.
		if (!(source.getAttacker() instanceof ServerPlayerEntity)) {
			return;
		}
		// Suppress tower auto-shots: a tower stamps lastTowerHitAge == age just before its hit,
		// and AFTER_DAMAGE fires synchronously inside that same damage call. Skip unless tower
		// numbers have been explicitly enabled.
		if (!SHOW_TOWER_MARKERS && entity instanceof TdEnemyEntity td && td.lastTowerHitAge == entity.age) {
			return;
		}
		// Respect the concurrency cap — a huge fight must not spawn a swarm of display entities.
		if (MARKERS.size() >= MAX_MARKERS) {
			return;
		}
		spawnMarker(world, entity, damageTaken);
	}

	// ---- spawning ----------------------------------------------------------
	/**
	 * Create and register one floating number at {@code enemy}'s head showing the rounded
	 * {@code damageTaken}. Configures the text display for an RPG damage indicator: billboard
	 * = CENTER (always faces the camera), a small uniform scale, a transparent background, a
	 * short view range, and a color band that warms from white → yellow → gold → red as the
	 * hit grows. The display inherits {@code noClip} from {@link DisplayEntity}, so it is
	 * inert and non-collidable; gravity is disabled so the sweep alone drives its rise.
	 */
	private static void spawnMarker(ServerWorld world, LivingEntity enemy, float damageTaken) {
		int rounded = Math.round(damageTaken);
		DisplayEntity.TextDisplayEntity display = new DisplayEntity.TextDisplayEntity(EntityType.TEXT_DISPLAY, world);

		double jx = (world.random.nextDouble() - 0.5) * 2.0 * SPAWN_JITTER;
		double jz = (world.random.nextDouble() - 0.5) * 2.0 * SPAWN_JITTER;
		display.setPosition(enemy.getX() + jx, enemy.getEyeY() + SPAWN_EYE_OFFSET, enemy.getZ() + jz);
		display.setNoGravity(true);

		display.setText(Text.literal(String.valueOf(rounded)).formatted(colorFor(rounded)));
		display.setBillboardMode(DisplayEntity.BillboardMode.CENTER);
		// Transparent background (ARGB with 0 alpha) — the glyph floats with no text box.
		display.setBackground(0);
		display.setViewRange(MARKER_VIEW_RANGE);
		// Uniform shrink via the transformation's scale vector (translation/rotations identity).
		display.setTransformation(new AffineTransformation(
			null, null, new Vector3f(MARKER_SCALE, MARKER_SCALE, MARKER_SCALE), null));

		world.spawnEntity(display);
		MARKERS.add(new Marker(display, tick));
	}

	/** Color band for a rounded damage value: white (chip) → yellow → gold → red (crusher). */
	private static Formatting colorFor(int rounded) {
		if (rounded <= WHITE_MAX) {
			return Formatting.WHITE;
		}
		if (rounded <= YELLOW_MAX) {
			return Formatting.YELLOW;
		}
		if (rounded <= GOLD_MAX) {
			return Formatting.GOLD;
		}
		return Formatting.RED;
	}

	// ---- per-tick sweep ----------------------------------------------------
	/**
	 * Advance every live number one tick: nudge it upward by {@value #RISE_PER_TICK} blocks
	 * and discard it (removing its entry) once it is older than {@value #MARKER_LIFETIME_TICKS}
	 * ticks or has otherwise been removed from the world. Mirrors the sweeps in
	 * {@code SpellManager} — a plain iterator pass, server-thread only.
	 */
	private static void sweep() {
		Iterator<Marker> it = MARKERS.iterator();
		while (it.hasNext()) {
			Marker m = it.next();
			DisplayEntity.TextDisplayEntity d = m.display();
			if (d == null || d.isRemoved()) {
				it.remove();
				continue;
			}
			if (tick - m.spawnTick() >= MARKER_LIFETIME_TICKS) {
				d.discard();
				it.remove();
				continue;
			}
			d.setPosition(d.getX(), d.getY() + RISE_PER_TICK, d.getZ());
		}
	}
}
