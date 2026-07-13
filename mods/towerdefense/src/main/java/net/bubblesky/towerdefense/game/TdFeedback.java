package net.bubblesky.towerdefense.game;

import net.bubblesky.towerdefense.state.TdArenaState;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Audiovisual feedback for the tower-defense game loop: server-side sounds,
 * particles, and screen titles fired at the moments that matter (wave start,
 * wave clear, base hit, boss spawn/defeat, game over). Kept out of
 * {@link WaveManager} so the game logic stays readable and the "juice" lives in
 * one place.
 *
 * <p>Everything here is server-authoritative — {@code world.playSound(null,...)}
 * and {@code ServerWorld.spawnParticles(...)} broadcast to nearby clients, and
 * titles are sent as {@link TitleS2CPacket}/{@link SubtitleS2CPacket} to arena
 * players. No client mixin is needed, so vanilla clients still get the sounds and
 * particles (only the custom entity skins require the mod client-side).
 */
public final class TdFeedback {
	private TdFeedback() {
	}

	private static final SoundCategory CAT = SoundCategory.HOSTILE;

	// ---- wave lifecycle ----------------------------------------------------
	/** A normal wave begins: a low horn blast + a puff at each spawn gate. */
	public static void waveStart(ServerWorld world, TdArenaState st) {
		playAtBase(world, st, SoundEvents.ENTITY_WITHER_SPAWN, 0.5f, 1.4f);
		for (BlockPos sp : st.spawnPoints) {
			world.spawnParticles(ParticleTypes.LARGE_SMOKE,
				sp.getX() + 0.5, sp.getY() + 1.0, sp.getZ() + 0.5, 20, 0.4, 0.6, 0.4, 0.02);
			world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME,
				sp.getX() + 0.5, sp.getY() + 0.5, sp.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0.02);
		}
	}

	/** A wave is cleared: a triumphant level-up chime + green sparkle at the base. */
	public static void waveClear(ServerWorld world, TdArenaState st) {
		playAtBase(world, st, SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
		if (st.base != null) {
			world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
				st.base.getX() + 0.5, st.base.getY() + 1.2, st.base.getZ() + 0.5,
				30, 0.8, 0.8, 0.8, 0.1);
		}
	}

	/** An enemy reached the base: an alarm thud + red damage burst at the base. */
	public static void baseHit(ServerWorld world, TdArenaState st) {
		if (st.base == null) {
			return;
		}
		double x = st.base.getX() + 0.5;
		double y = st.base.getY() + 1.0;
		double z = st.base.getZ() + 0.5;
		world.playSound(null, st.base, SoundEvents.BLOCK_ANVIL_LAND, CAT, 0.7f, 0.6f);
		world.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, x, y, z, 12, 0.5, 0.4, 0.5, 0.05);
		world.spawnParticles(new DustParticleEffect(0xFF3020, 1.6f), x, y, z, 20, 0.6, 0.5, 0.6, 0.02);
	}

	// ---- boss --------------------------------------------------------------
	/** A boss wave begins: dragon growl + wither horn, an explosion puff, a title. */
	public static void bossSpawn(ServerWorld world, TdArenaState st, BlockPos at, int wave) {
		world.playSound(null, at, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, CAT, 1.0f, 0.8f);
		world.playSound(null, at, SoundEvents.ENTITY_WITHER_SPAWN, CAT, 0.8f, 0.8f);
		world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER,
			at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
		world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME,
			at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 40, 0.6, 0.8, 0.6, 0.05);
		title(world,
			Text.literal("A Warlord approaches!").formatted(Formatting.DARK_PURPLE, Formatting.BOLD),
			Text.literal("Boss Wave " + wave).formatted(Formatting.LIGHT_PURPLE));
	}

	/** The boss dies: a totem flourish + celebratory sparkle + a reward title. */
	public static void bossDefeated(ServerWorld world, TdArenaState st, int bonus) {
		playAtBase(world, st, SoundEvents.ITEM_TOTEM_USE, 1.0f, 1.0f);
		playAtBase(world, st, SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
		if (st.base != null) {
			world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
				st.base.getX() + 0.5, st.base.getY() + 1.2, st.base.getZ() + 0.5,
				40, 1.0, 1.0, 1.0, 0.2);
		}
		title(world,
			Text.literal("Warlord defeated!").formatted(Formatting.GOLD, Formatting.BOLD),
			Text.literal("+" + bonus + " bonus coins").formatted(Formatting.YELLOW));
	}

	// ---- game over ---------------------------------------------------------
	/** The base has fallen: a grim wither death + smoke, and a summary title. */
	public static void gameOver(ServerWorld world, TdArenaState st, int wavesSurvived) {
		playAtBase(world, st, SoundEvents.ENTITY_WITHER_DEATH, 1.0f, 0.7f);
		if (st.base != null) {
			world.spawnParticles(ParticleTypes.LARGE_SMOKE,
				st.base.getX() + 0.5, st.base.getY() + 1.0, st.base.getZ() + 0.5,
				60, 0.8, 1.0, 0.8, 0.05);
			world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER,
				st.base.getX() + 0.5, st.base.getY() + 1.0, st.base.getZ() + 0.5, 1, 0, 0, 0, 0);
		}
		title(world,
			Text.literal("The Idol has fallen").formatted(Formatting.DARK_RED, Formatting.BOLD),
			Text.literal("Survived " + wavesSurvived + " wave" + (wavesSurvived == 1 ? "" : "s")
				+ " — /td restart").formatted(Formatting.RED));
	}

	// ---- helpers -----------------------------------------------------------
	private static void playAtBase(ServerWorld world, TdArenaState st, SoundEvent sound,
			float volume, float pitch) {
		if (st.base == null) {
			return;
		}
		world.playSound(null, st.base, sound, CAT, volume, pitch);
	}

	/** Push a title + subtitle to every player in the arena world. */
	private static void title(ServerWorld world, Text title, @Nullable Text subtitle) {
		for (ServerPlayerEntity player : world.getPlayers()) {
			player.networkHandler.sendPacket(new TitleS2CPacket(title));
			if (subtitle != null) {
				player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
			}
		}
	}
}
