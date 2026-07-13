package net.bubblesky.towerdefense.progression;

import net.bubblesky.towerdefense.game.WaveManager;
import net.bubblesky.towerdefense.progression.PlayerProgress.Stat;
import net.bubblesky.towerdefense.progression.net.AllocatePointPayload;
import net.bubblesky.towerdefense.progression.net.ProgressSyncPayload;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Wires the RPG progression system into the server: XP on enemy kills, stat
 * (re)application + sync on the player-lifecycle events, and the allocation-packet
 * round-trip. All registration happens in {@link #register()}, called once from mod
 * init.
 *
 * <h2>XP per kill</h2>
 * Its OWN {@link ServerLivingEntityEvents#AFTER_DEATH} listener — deliberately separate
 * from {@link WaveManager}'s boss-bounty listener (which pays coins; this one grants XP).
 * When a dead entity carries the {@code td_enemy} command tag, XP is derived from the
 * enemy's max health: {@code round(maxHealth * 0.2)} (min 1). A {@code td_boss} kill
 * multiplies that by {@value #BOSS_XP_MULT}. The killer (if a player) gets the full
 * amount; other players within {@value #SHARE_RADIUS} blocks of the corpse share a
 * {@value #SHARE_FRACTION} fraction each. Because enemy max health already scales with
 * the wave, XP naturally grows as the run deepens.
 */
public final class ProgressEvents {

	private ProgressEvents() {
	}

	// ---- XP tuning ---------------------------------------------------------
	/** Base XP per kill = round(enemy max health * this), floored to a minimum of 1. */
	private static final double XP_PER_MAX_HEALTH = 0.2;
	/** Boss ({@code td_boss}) kills grant this multiple of the base XP. */
	private static final double BOSS_XP_MULT = 5.0;
	/** Nearby (non-killer) players each earn this fraction of the base XP. */
	private static final double SHARE_FRACTION = 0.25;
	/** Radius (blocks) around the slain enemy within which nearby players share XP. */
	private static final double SHARE_RADIUS = 24.0;

	/** Register all progression hooks + networking. Call once from mod init. */
	public static void register() {
		// Networking types (registered here on BOTH sides — mod init runs on client + server).
		PayloadTypeRegistry.playC2S().register(AllocatePointPayload.ID, AllocatePointPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ProgressSyncPayload.ID, ProgressSyncPayload.CODEC);

		// Server receiver: validate + allocate + re-apply + save + resync.
		ServerPlayNetworking.registerGlobalReceiver(AllocatePointPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			ProgressState state = ProgressState.get(context.server());
			PlayerProgress progress = state.forPlayer(player.getUuid());
			if (progress.allocate(payload.stat())) {
				state.markDirty();
				StatModifiers.apply(player, progress);
				sync(player, progress);
			}
		});

		// XP award on TD-enemy death (own listener, separate from WaveManager's boss bounty).
		ServerLivingEntityEvents.AFTER_DEATH.register(ProgressEvents::onEntityDeath);

		// (Re)apply stats + push a fresh sync whenever the player (re)enters the world.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> refresh(handler.player));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> refresh(newPlayer));
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
			(player, origin, destination) -> refresh(player));
	}

	// ---- XP hook -----------------------------------------------------------
	private static void onEntityDeath(LivingEntity entity, DamageSource source) {
		if (!(entity.getWorld() instanceof ServerWorld world)) {
			return;
		}
		if (!entity.getCommandTags().contains(WaveManager.ENEMY_TAG)) {
			return;
		}
		boolean boss = entity.getCommandTags().contains(WaveManager.BOSS_TAG);
		int baseXp = Math.max(1, (int) Math.round(entity.getMaxHealth() * XP_PER_MAX_HEALTH));
		if (boss) {
			baseXp = (int) Math.round(baseXp * BOSS_XP_MULT);
		}

		ProgressState state = ProgressState.get(world.getServer());
		ServerPlayerEntity killer = source.getAttacker() instanceof ServerPlayerEntity sp ? sp : null;
		if (killer != null) {
			award(state, killer, baseXp);
		}

		// Nearby (non-killer) players share a fraction of the kill's XP.
		int shareXp = Math.max(1, (int) Math.round(baseXp * SHARE_FRACTION));
		double radiusSq = SHARE_RADIUS * SHARE_RADIUS;
		for (ServerPlayerEntity player : world.getPlayers()) {
			if (player == killer) {
				continue;
			}
			if (player.squaredDistanceTo(entity.getX(), entity.getY(), entity.getZ()) > radiusSq) {
				continue;
			}
			award(state, player, shareXp);
		}
	}

	/** Bank XP for one player, fire a level-up message on any level gained, then resync. */
	private static void award(ProgressState state, ServerPlayerEntity player, int xp) {
		PlayerProgress progress = state.forPlayer(player.getUuid());
		int gained = progress.addXp(xp);
		state.markDirty();
		if (gained > 0) {
			int pts = progress.getUnspentPoints();
			player.sendMessage(Text.literal("Level up! You are now level " + progress.getLevel()
					+ " — " + pts + " skill point" + (pts == 1 ? "" : "s")
					+ " to spend (press P).")
				.formatted(Formatting.AQUA, Formatting.BOLD), false);
		}
		sync(player, progress);
	}

	// ---- lifecycle sync ----------------------------------------------------
	/** Re-apply attribute modifiers and push a fresh snapshot to one player. */
	public static void refresh(ServerPlayerEntity player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		ProgressState state = ProgressState.get(server);
		PlayerProgress progress = state.forPlayer(player.getUuid());
		StatModifiers.apply(player, progress);
		sync(player, progress);
	}

	/** Send the current progression snapshot to a player's client. */
	public static void sync(ServerPlayerEntity player, PlayerProgress progress) {
		int[] alloc = new int[Stat.values().length];
		for (Stat stat : Stat.values()) {
			alloc[stat.ordinal()] = progress.points(stat);
		}
		ServerPlayNetworking.send(player,
			new ProgressSyncPayload(progress.getXp(), progress.getLevel(), progress.getUnspentPoints(), alloc));
	}
}
