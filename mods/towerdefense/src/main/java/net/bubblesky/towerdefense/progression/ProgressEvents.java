package net.bubblesky.towerdefense.progression;

import net.bubblesky.towerdefense.command.TdCommand;
import net.bubblesky.towerdefense.game.WaveManager;
import java.util.LinkedHashMap;
import java.util.Map;
import net.bubblesky.towerdefense.progression.PlayerProgress.Stat;
import net.bubblesky.towerdefense.progression.net.AllocateClassPointPayload;
import net.bubblesky.towerdefense.progression.net.AllocatePointPayload;
import net.bubblesky.towerdefense.progression.net.ProgressSyncPayload;
import net.bubblesky.towerdefense.progression.net.SelectClassPayload;
import net.bubblesky.towerdefense.registry.ModItems;
import net.bubblesky.towerdefense.spell.SpellManager;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.ItemEntity;
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
 * the wave, XP naturally grows as the run deepens. Each recipient's raw share is further
 * scaled by their own {@link Stat#INTELLIGENCE} multiplier ({@link ProgressLookup#xpMult})
 * before being banked, in {@link #award}.
 */
public final class ProgressEvents {

	private ProgressEvents() {
	}

	// ---- XP tuning ---------------------------------------------------------
	/** Base XP per kill = round(enemy max health * this), floored to a minimum of 1. */
	private static final double XP_PER_MAX_HEALTH = 0.2;
	/** Boss ({@code td_boss}) kills grant this multiple of the base XP. */
	private static final double BOSS_XP_MULT = 5.0;

	// ---- ESSENCE tuning ----------------------------------------------------
	// Essence is the PREMIUM loot currency, so it accrues far slower than XP/gold: a base
	// kill yields ~1 essence and a boss a modest chunk. Base essence = ceil(maxHealth /
	// ESSENCE_HEALTH_DIVISOR): with a divisor of 25 a typical 10-25 HP enemy drops 1, a
	// beefy 50 HP elite drops 2, and a boss is further multiplied. The killer banks the
	// full amount (scaled by their own Engineer Salvage essenceMult); nearby players share
	// only the ROUNDED fraction, so normal kills give bystanders nothing and only bosses
	// spread essence around — keeping the drip deliberately thin.
	/** Base essence per kill = ceil(enemy max health / this), floored to a minimum of 1. */
	private static final double ESSENCE_HEALTH_DIVISOR = 25.0;
	/** Boss ({@code td_boss}) kills grant this multiple of the base essence. */
	private static final double BOSS_ESSENCE_MULT = 5.0;
	/** Nearby (non-killer) players each earn this fraction of the base XP. */
	private static final double SHARE_FRACTION = 0.25;
	/** Radius (blocks) around the slain enemy within which nearby players share XP. */
	private static final double SHARE_RADIUS = 24.0;

	/** Register all progression hooks + networking. Call once from mod init. */
	public static void register() {
		// Networking types (registered here on BOTH sides — mod init runs on client + server).
		PayloadTypeRegistry.playC2S().register(AllocatePointPayload.ID, AllocatePointPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(AllocateClassPointPayload.ID, AllocateClassPointPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(SelectClassPayload.ID, SelectClassPayload.CODEC);
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

		// Server receiver: spend one CLASS point on a skill in the active class's tree.
		// Authoritative validation mirrors ClassSkillTree: active class present, unspent class
		// points, the skill exists, current rank below its max, and class level meets the tier
		// gate. On success: allocate (decrements unspent), re-apply StatModifiers (so passives
		// like Fleet Foot / Arcane Mind refresh), save, and resync.
		ServerPlayNetworking.registerGlobalReceiver(AllocateClassPointPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			ProgressState state = ProgressState.get(context.server());
			PlayerProgress progress = state.forPlayer(player.getUuid());
			PlayerClass active = progress.getActiveClass();
			if (active == null) {
				actionbar(player, "Pick a class first with /td class.");
				return;
			}
			ClassSkillTree.Skill skill = ClassSkillTree.skill(active, payload.skillId());
			if (skill == null) {
				return; // unknown skill id for this class — ignore
			}
			ClassProgress track = progress.classProgress(active);
			if (track.getUnspentPoints() <= 0) {
				actionbar(player, "No class points to spend.");
				return;
			}
			if (track.points(skill.id()) >= skill.maxRank()) {
				actionbar(player, skill.displayName() + " is already at max rank.");
				return;
			}
			int gate = ClassSkillTree.levelGate(skill.tier());
			if (track.getLevel() < gate) {
				actionbar(player, skill.displayName() + " unlocks at " + active.displayName()
					+ " level " + gate + ".");
				return;
			}
			if (track.allocate(skill.id())) {
				state.markDirty();
				StatModifiers.apply(player, progress);
				sync(player, progress);
			}
		});

		// Server receiver: pick a class from the (future) class-pick screen — same effect
		// as /td class <name> (validate id, set active, grant loadout, re-apply, resync).
		ServerPlayNetworking.registerGlobalReceiver(SelectClassPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			PlayerClass cls = PlayerClass.fromId(payload.classId());
			if (cls == null) {
				return; // ignore an unknown class id rather than throw
			}
			ClassLoadout.select(player, cls);
			player.sendMessage(Text.literal("Class set to " + cls.displayName() + ".")
				.formatted(Formatting.GREEN), false);
		});

		// XP award on TD-enemy death (own listener, separate from WaveManager's boss bounty).
		ServerLivingEntityEvents.AFTER_DEATH.register(ProgressEvents::onEntityDeath);

		// Gold-coin auto-collect: every few ticks, vacuum nearby COIN drops into the banks.
		ServerTickEvents.END_SERVER_TICK.register(ProgressEvents::onCollectTick);

		// Mana regeneration: once per second, refill every player's pool toward its cap at an
		// Intelligence-scaled rate, resyncing only when a pool actually changed.
		ServerTickEvents.END_SERVER_TICK.register(ProgressEvents::onManaRegenTick);

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
		// Drop a raisable corpse marker at the fallen enemy so the Necromancer's RAISE_DEAD
		// can reanimate it (expires shortly; see SpellManager.CORPSE_LIFETIME_TICKS).
		SpellManager.recordCorpse(world, entity.getPos());
		boolean boss = entity.getCommandTags().contains(WaveManager.BOSS_TAG);
		int baseXp = Math.max(1, (int) Math.round(entity.getMaxHealth() * XP_PER_MAX_HEALTH));
		if (boss) {
			baseXp = (int) Math.round(baseXp * BOSS_XP_MULT);
		}

		// ESSENCE mirrors the XP award (killer full, nearby a rounded share) but at a much
		// smaller, ceil-of-a-divisor rate so it stays premium. Boss kills drop a chunk.
		int baseEssence = Math.max(1, (int) Math.ceil(entity.getMaxHealth() / ESSENCE_HEALTH_DIVISOR));
		if (boss) {
			baseEssence = (int) Math.round(baseEssence * BOSS_ESSENCE_MULT);
		}

		ProgressState state = ProgressState.get(world.getServer());
		ServerPlayerEntity killer = source.getAttacker() instanceof ServerPlayerEntity sp ? sp : null;
		if (killer != null) {
			award(state, killer, baseXp, baseEssence);
		}

		// Nearby (non-killer) players share a fraction of the kill's XP + essence. XP shares
		// floor to a minimum of 1; essence shares are NOT floored (round only), so bystanders
		// earn essence only when the base amount is large enough — chiefly on boss kills.
		int shareXp = Math.max(1, (int) Math.round(baseXp * SHARE_FRACTION));
		int shareEssence = (int) Math.round(baseEssence * SHARE_FRACTION);
		double radiusSq = SHARE_RADIUS * SHARE_RADIUS;
		for (ServerPlayerEntity player : world.getPlayers()) {
			if (player == killer) {
				continue;
			}
			if (player.squaredDistanceTo(entity.getX(), entity.getY(), entity.getZ()) > radiusSq) {
				continue;
			}
			award(state, player, shareXp, shareEssence);
		}
	}

	/**
	 * Bank XP (and any essence) for one player, fire a level-up message on any level gained,
	 * then resync. The raw XP is scaled by the recipient's OWN {@link Stat#INTELLIGENCE}
	 * multiplier and the raw essence by their OWN Engineer Salvage
	 * ({@link StatModifiers#essenceMult}) before banking, so each reward reflects that
	 * player's own investment. A single {@link #sync} at the end pushes both.
	 *
	 * @param essence raw essence to bank before the per-player multiplier (0 to bank none)
	 */
	private static void award(ProgressState state, ServerPlayerEntity player, int xp, int essence) {
		PlayerProgress progress = state.forPlayer(player.getUuid());
		int scaledXp = Math.max(1, (int) Math.round(xp * ProgressLookup.xpMult(player)));
		int gained = progress.addXp(scaledXp);
		if (essence > 0) {
			int scaledEssence = Math.max(1, (int) Math.round(essence * StatModifiers.essenceMult(progress)));
			progress.addEssence(scaledEssence);
		}
		state.markDirty();
		if (gained > 0) {
			int pts = progress.getUnspentPoints();
			player.sendMessage(Text.literal("Level up! You are now level " + progress.getLevel()
					+ " — " + pts + " skill point" + (pts == 1 ? "" : "s")
					+ " to spend (press P).")
				.formatted(Formatting.AQUA, Formatting.BOLD), false);
		}
		// XP SPLIT: the SAME activity also feeds the player's ACTIVE class track (if any).
		// Class XP equals the (Intelligence-scaled) global award, so a class levels at the
		// same pace as the character while it's the one being played. No active class →
		// global XP only (unchanged behavior).
		PlayerClass active = progress.getActiveClass();
		if (active != null) {
			ClassProgress classTrack = progress.classProgress(active);
			int classGained = classTrack.addXp(scaledXp);
			if (classGained > 0) {
				int cpts = classTrack.getUnspentPoints();
				player.sendMessage(Text.literal(active.displayName() + " level up! Now "
						+ active.displayName() + " level " + classTrack.getLevel() + " — " + cpts
						+ " class point" + (cpts == 1 ? "" : "s") + " banked.")
					.formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD), false);
			}
		}
		sync(player, progress);
	}

	// ---- lifecycle sync ----------------------------------------------------
	/**
	 * Re-apply attribute modifiers and push a fresh snapshot to one player, then handle the
	 * per-life class step: prompt an unclassed player to pick, or RE-GRANT the active class's
	 * loadout so it survives death/respawn/world-change. Fired on join/respawn/world-change.
	 */
	public static void refresh(ServerPlayerEntity player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		ProgressState state = ProgressState.get(server);
		PlayerProgress progress = state.forPlayer(player.getUuid());
		StatModifiers.apply(player, progress);
		handleClassOnSpawn(player, progress);
		sync(player, progress);
	}

	/**
	 * The respawn / first-spawn class hook: if the player has NO active class, nudge them to
	 * pick one; otherwise re-grant that class's loadout (gear + placeholder spells) so a
	 * fresh life is armed. Called from {@link #refresh}.
	 */
	private static void handleClassOnSpawn(ServerPlayerEntity player, PlayerProgress progress) {
		PlayerClass active = progress.getActiveClass();
		if (active == null) {
			promptClassPick(player);
		} else {
			ClassLoadout.grant(player, active);
		}
	}

	/** Chat prompt listing the classes and the {@code /td class <name>} pick command. */
	private static void promptClassPick(ServerPlayerEntity player) {
		player.sendMessage(Text.literal("Choose your class! Run ")
				.formatted(Formatting.GOLD)
				.append(Text.literal("/td class <mage|ranger|engineer|necromancer>").formatted(Formatting.YELLOW))
				.append(Text.literal(" to pick a loadout for this life.").formatted(Formatting.GOLD)),
			false);
		player.sendMessage(Text.literal("  mage = spellpower · ranger = archery · engineer = builder · "
				+ "necromancer = summoner. (Bare /td class lists them.)")
			.formatted(Formatting.GRAY), false);
	}

	/**
	 * Send the current progression snapshot to a player's client: the global track (xp /
	 * level / points / gold / allocations) plus the class layer (mana / maxMana / active
	 * class id + that class's own level / within-level xp / unspent points). Class fields
	 * are zero/empty when unclassed.
	 */
	public static void sync(ServerPlayerEntity player, PlayerProgress progress) {
		int[] alloc = new int[Stat.values().length];
		for (Stat stat : Stat.values()) {
			alloc[stat.ordinal()] = progress.points(stat);
		}
		PlayerClass active = progress.getActiveClass();
		String classId = active == null ? "" : active.id();
		int classLevel = 0;
		int classXp = 0;
		int classPoints = 0;
		Map<String, Integer> classAllocations = new LinkedHashMap<>();
		if (active != null) {
			ClassProgress track = progress.classProgress(active);
			classLevel = track.getLevel();
			classXp = track.getXp();
			classPoints = track.getUnspentPoints();
			classAllocations.putAll(track.allocations());
		}
		ServerPlayNetworking.send(player,
			new ProgressSyncPayload(progress.getXp(), progress.getLevel(), progress.getUnspentPoints(),
				progress.getGold(), progress.getEssence(), alloc,
				progress.getMana(), progress.getMaxMana(), classId, classLevel, classXp, classPoints,
				classAllocations));
	}

	/** Send a red action-bar note to a player (used for Skills-tab allocation rejections). */
	private static void actionbar(ServerPlayerEntity player, String message) {
		player.sendMessage(Text.literal(message).formatted(Formatting.RED), true);
	}

	// ---- gold-coin auto-collect (vacuum) -----------------------------------
	/** How often (server ticks) the coin-vacuum sweep runs — every quarter-second. */
	private static final int COLLECT_INTERVAL = 5;
	/** Tick accumulator, so the sweep runs once per {@link #COLLECT_INTERVAL} ticks. */
	private static int collectTicker = 0;

	/**
	 * The coin-vacuum sweep, run every {@link #COLLECT_INTERVAL} ticks. For each online
	 * player it scans for dropped {@code COIN} {@link ItemEntity}s within that player's
	 * Intelligence-driven {@link ProgressLookup#collectionRadius} and removes them from the
	 * world, tallying the total coin count collected across everyone. That total is then
	 * credited to EVERY online player's bank ({@link TdCommand#grantCoinsToAll}) — equal
	 * shared income, so a single collected coin fills every teammate's bank the same. A
	 * coin discarded by one player's sweep won't be re-counted by the next (the per-player
	 * scan is re-issued after each removal, and dead entities drop out of the query).
	 */
	private static void onCollectTick(MinecraftServer server) {
		if (++collectTicker < COLLECT_INTERVAL) {
			return;
		}
		collectTicker = 0;

		int collected = 0;
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			if (!(player.getWorld() instanceof ServerWorld world)) {
				continue;
			}
			double radius = ProgressLookup.collectionRadius(player);
			double radiusSq = radius * radius;
			net.minecraft.util.math.Box box = player.getBoundingBox().expand(radius);
			for (ItemEntity drop : world.getEntitiesByClass(ItemEntity.class, box,
					e -> e.isAlive() && e.getStack().isOf(ModItems.COIN))) {
				// Refine the cube query to a true sphere around the player.
				if (player.squaredDistanceTo(drop) > radiusSq) {
					continue;
				}
				collected += drop.getStack().getCount();
				drop.discard();
			}
		}

		if (collected > 0) {
			// Equal shared income: each collected coin credits EVERY online player's bank.
			TdCommand.grantCoinsToAll(server, collected);
		}
	}

	// ---- mana regeneration -------------------------------------------------
	/** How often (server ticks) mana regen ticks — once per second. */
	private static final int MANA_REGEN_INTERVAL = 20;
	/** Tick accumulator for the once-per-second mana regen sweep. */
	private static int manaTicker = 0;

	/**
	 * Once per second, credit each online player's mana pool at their Intelligence-scaled
	 * rate ({@link StatModifiers#manaRegenPerSecond}), clamped to their max. A pool that
	 * actually changed (i.e. was below full) is resynced so the HUD bar creeps up live; a
	 * full pool costs nothing and sends nothing, so the payload is never spammed.
	 */
	private static void onManaRegenTick(MinecraftServer server) {
		if (++manaTicker < MANA_REGEN_INTERVAL) {
			return;
		}
		manaTicker = 0;
		ProgressState state = ProgressState.get(server);
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			PlayerProgress progress = state.forPlayer(player.getUuid());
			int before = progress.getMana();
			if (before >= progress.getMaxMana()) {
				continue; // already full — nothing to regen, nothing to sync
			}
			progress.addMana(StatModifiers.manaRegenPerSecond(progress));
			if (progress.getMana() != before) {
				state.markDirty();
				sync(player, progress);
			}
		}
	}
}
