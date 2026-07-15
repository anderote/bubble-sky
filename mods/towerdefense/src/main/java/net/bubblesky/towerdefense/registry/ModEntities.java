package net.bubblesky.towerdefense.registry;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.bubblesky.towerdefense.colony.ColonistEntity;
import net.bubblesky.towerdefense.entity.FlagArrowEntity;
import net.bubblesky.towerdefense.entity.TowerArrowEntity;
import net.bubblesky.towerdefense.entity.TowerBoltEntity;
import net.bubblesky.towerdefense.entity.TdAllyArcher;
import net.bubblesky.towerdefense.entity.TdAllyEntity;
import net.bubblesky.towerdefense.entity.TdArcherEnemy;
import net.bubblesky.towerdefense.entity.TdBarbarian;
import net.bubblesky.towerdefense.entity.TdBarbarianSapper;
import net.bubblesky.towerdefense.entity.TdEnemyEntity;
import net.bubblesky.towerdefense.entity.TdFootman;
import net.bubblesky.towerdefense.entity.TdMeleeEnemy;
import net.bubblesky.towerdefense.entity.TdSkeletonArcher;
import net.bubblesky.towerdefense.entity.TdSkeletonWarrior;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/**
 * Registers the custom ENEMY ROSTER — six original biped mobs the endless waves
 * are composed from. Each is a distinct {@link EntityType} with its own default
 * attributes (hp / attack / speed) so the roster reads as a real army with roles,
 * while a single {@link TdMeleeEnemy} / {@link TdArcherEnemy} class backs them all.
 *
 * <p>Path A (reskinned vanilla humanoid): every enemy uses the biped model +
 * a custom 64x64 skin, wired up client-side in {@code TowerDefenseModClient}.
 *
 * <p>Base stats (multiplied per-wave by the {@code WaveManager}):
 * <ul>
 *   <li>goblin_skirmisher — 12 hp, 2 atk, 0.30 spd — fast melee swarm</li>
 *   <li>footman           — 20 hp, 3 atk, 0.25 spd — baseline melee</li>
 *   <li>archer            — 16 hp, 4 atk (ranged), 0.25 spd — shoots, keeps distance</li>
 *   <li>man_at_arms       — 30 hp, 5 atk, 0.24 spd — sturdy melee</li>
 *   <li>undead_soldier    — 25 hp, 5 atk, 0.22 spd — tanky</li>
 *   <li>heavy_knight      — 60 hp, 8 atk, 0.20 spd — slow armored bruiser</li>
 * </ul>
 */
public final class ModEntities {
	private ModEntities() {
	}

	// ---- roster entity types ----------------------------------------------
	public static final EntityType<TdMeleeEnemy> GOBLIN_SKIRMISHER =
		registerMelee("goblin_skirmisher", 0.55f, 1.8f);
	public static final EntityType<TdMeleeEnemy> FOOTMAN =
		registerMelee("footman", 0.6f, 1.95f);
	public static final EntityType<TdArcherEnemy> ARCHER =
		registerArcher("archer", 0.6f, 1.95f);
	public static final EntityType<TdMeleeEnemy> MAN_AT_ARMS =
		registerMelee("man_at_arms", 0.6f, 1.95f);
	public static final EntityType<TdMeleeEnemy> UNDEAD_SOLDIER =
		registerMelee("undead_soldier", 0.6f, 1.95f);
	public static final EntityType<TdMeleeEnemy> HEAVY_KNIGHT =
		registerMelee("heavy_knight", 0.7f, 2.0f);
	/** Rugged heavy-melee brute (mid-game). Beefier than a footman; paths around walls. */
	public static final EntityType<TdBarbarian> BARBARIAN =
		registerEnemy("barbarian", TdBarbarian::new, 0.65f, 2.0f);
	/** The siege breaker (deep-game). Bores a straight tunnel to the Idol. */
	public static final EntityType<TdBarbarianSapper> BARBARIAN_SAPPER =
		registerEnemy("barbarian_sapper", TdBarbarianSapper::new, 0.7f, 2.1f);

	// ---- hireable ALLY roster ---------------------------------------------
	/** Friendly biped mobs the player buys with coins to fight the waves. */
	public static final EntityType<TdFootman> ALLY_FOOTMAN =
		registerFootman("ally_footman", 0.6f, 1.95f);
	public static final EntityType<TdAllyArcher> ALLY_ARCHER =
		registerAllyArcher("ally_archer", 0.6f, 1.95f);
	public static final EntityType<TdFootman> ALLY_KNIGHT =
		registerFootman("ally_knight", 0.7f, 2.0f);
	/**
	 * The Warlord's summoned SKELETON ARCHER — a beefy, temporary undead bowman (see
	 * {@link TdSkeletonArcher}). Rendered with the vanilla skeleton skin client-side,
	 * but mechanically a friendly {@link TdAllyEntity} so our own towers never target it.
	 */
	public static final EntityType<TdSkeletonArcher> ALLY_SKELETON =
		registerSkeletonArcher("ally_skeleton", 0.6f, 1.99f);
	/**
	 * The Necromancer's raised SKELETON WARRIOR — a beefy melee undead ally (see
	 * {@link TdSkeletonWarrior}), reanimated from enemy corpses by {@code RAISE_DEAD}.
	 * Rendered with the vanilla skeleton skin client-side (holding a sword), but
	 * mechanically a friendly {@link TdAllyEntity} so our own towers never target it.
	 */
	public static final EntityType<TdSkeletonWarrior> ALLY_SKELETON_WARRIOR =
		registerSkeletonWarrior("ally_skeleton_warrior", 0.6f, 1.99f);

	// ---- colony COLONIST --------------------------------------------------
	/** A named humanoid worker the player recruits into a colony (mine/chop/hunt/forage/haul). */
	public static final EntityType<ColonistEntity> COLONIST =
		registerColonist("colonist", 0.6f, 1.95f);

	// ---- projectiles ------------------------------------------------------
	/** The Flag Bow's arrow: plants a Layout flag wherever it lands. */
	public static final EntityType<FlagArrowEntity> FLAG_ARROW = registerFlagArrow("flag_arrow");

	/** The shoot-to-place tower arrow: builds a tower structure wherever it lands. */
	public static final EntityType<TowerArrowEntity> TOWER_ARROW = registerTowerArrow("tower_arrow");

	/**
	 * The ARROW/BALL towers' combat projectile: a real arrow that deals owner-credited
	 * damage + knockback on hit, then vanishes almost immediately (no lingering clutter).
	 */
	public static final EntityType<TowerBoltEntity> TOWER_BOLT = registerTowerBolt("tower_bolt");

	private static EntityType<FlagArrowEntity> registerFlagArrow(String name) {
		RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, name));
		EntityType<FlagArrowEntity> type = EntityType.Builder
			.<FlagArrowEntity>create(FlagArrowEntity::new, SpawnGroup.MISC)
			.dimensions(0.5f, 0.5f)
			.maxTrackingRange(4)
			.trackingTickInterval(20)
			.build(key);
		return Registry.register(Registries.ENTITY_TYPE, key, type);
	}

	private static EntityType<TowerArrowEntity> registerTowerArrow(String name) {
		RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, name));
		EntityType<TowerArrowEntity> type = EntityType.Builder
			.<TowerArrowEntity>create(TowerArrowEntity::new, SpawnGroup.MISC)
			.dimensions(0.5f, 0.5f)
			.maxTrackingRange(4)
			.trackingTickInterval(20)
			.build(key);
		return Registry.register(Registries.ENTITY_TYPE, key, type);
	}

	private static EntityType<TowerBoltEntity> registerTowerBolt(String name) {
		RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, name));
		EntityType<TowerBoltEntity> type = EntityType.Builder
			.<TowerBoltEntity>create(TowerBoltEntity::new, SpawnGroup.MISC)
			.dimensions(0.5f, 0.5f)
			.maxTrackingRange(4)
			.trackingTickInterval(20)
			.build(key);
		return Registry.register(Registries.ENTITY_TYPE, key, type);
	}

	/**
	 * Generic enemy registrar for concrete {@link TdEnemyEntity} subclasses (e.g. the
	 * barbarians) that need their own class rather than the shared {@link TdMeleeEnemy}.
	 * Same wiring as {@link #registerMelee} — a MONSTER spawn group with a 48-block
	 * tracking range — but parameterised over the entity factory / type.
	 */
	private static <T extends TdEnemyEntity> EntityType<T> registerEnemy(
			String name, EntityType.EntityFactory<T> factory, float width, float height) {
		RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, name));
		EntityType<T> type = EntityType.Builder
			.create(factory, SpawnGroup.MONSTER)
			.dimensions(width, height)
			.maxTrackingRange(48)
			.build(key);
		return Registry.register(Registries.ENTITY_TYPE, key, type);
	}

	private static EntityType<TdMeleeEnemy> registerMelee(String name, float width, float height) {
		RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, name));
		EntityType<TdMeleeEnemy> type = EntityType.Builder
			.create(TdMeleeEnemy::new, SpawnGroup.MONSTER)
			.dimensions(width, height)
			.maxTrackingRange(48)
			.build(key);
		return Registry.register(Registries.ENTITY_TYPE, key, type);
	}

	private static EntityType<TdArcherEnemy> registerArcher(String name, float width, float height) {
		RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, name));
		EntityType<TdArcherEnemy> type = EntityType.Builder
			.create(TdArcherEnemy::new, SpawnGroup.MONSTER)
			.dimensions(width, height)
			.maxTrackingRange(48)
			.build(key);
		return Registry.register(Registries.ENTITY_TYPE, key, type);
	}

	private static EntityType<TdFootman> registerFootman(String name, float width, float height) {
		RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, name));
		EntityType<TdFootman> type = EntityType.Builder
			.create(TdFootman::new, SpawnGroup.CREATURE)
			.dimensions(width, height)
			.maxTrackingRange(48)
			.build(key);
		return Registry.register(Registries.ENTITY_TYPE, key, type);
	}

	private static EntityType<TdAllyArcher> registerAllyArcher(String name, float width, float height) {
		RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, name));
		EntityType<TdAllyArcher> type = EntityType.Builder
			.create(TdAllyArcher::new, SpawnGroup.CREATURE)
			.dimensions(width, height)
			.maxTrackingRange(48)
			.build(key);
		return Registry.register(Registries.ENTITY_TYPE, key, type);
	}

	private static EntityType<TdSkeletonArcher> registerSkeletonArcher(String name, float width, float height) {
		RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, name));
		EntityType<TdSkeletonArcher> type = EntityType.Builder
			.create(TdSkeletonArcher::new, SpawnGroup.CREATURE)
			.dimensions(width, height)
			.maxTrackingRange(48)
			.build(key);
		return Registry.register(Registries.ENTITY_TYPE, key, type);
	}

	private static EntityType<TdSkeletonWarrior> registerSkeletonWarrior(String name, float width, float height) {
		RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, name));
		EntityType<TdSkeletonWarrior> type = EntityType.Builder
			.create(TdSkeletonWarrior::new, SpawnGroup.CREATURE)
			.dimensions(width, height)
			.maxTrackingRange(48)
			.build(key);
		return Registry.register(Registries.ENTITY_TYPE, key, type);
	}

	private static EntityType<ColonistEntity> registerColonist(String name, float width, float height) {
		RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, name));
		EntityType<ColonistEntity> type = EntityType.Builder
			.create(ColonistEntity::new, SpawnGroup.CREATURE)
			.dimensions(width, height)
			.maxTrackingRange(48)
			.build(key);
		return Registry.register(Registries.ENTITY_TYPE, key, type);
	}

	// ---- default attributes -----------------------------------------------
	private static DefaultAttributeContainer.Builder attrs(double hp, double atk, double speed) {
		return HostileEntity.createHostileAttributes()
			.add(EntityAttributes.MAX_HEALTH, hp)
			.add(EntityAttributes.ATTACK_DAMAGE, atk)
			.add(EntityAttributes.MOVEMENT_SPEED, speed)
			.add(EntityAttributes.FOLLOW_RANGE, 24.0);
	}

	/** Attribute template for the friendly ally roster (a PathAwareEntity/creature). */
	private static DefaultAttributeContainer.Builder allyAttrs(double hp, double atk, double speed) {
		return MobEntity.createMobAttributes()
			.add(EntityAttributes.MAX_HEALTH, hp)
			.add(EntityAttributes.ATTACK_DAMAGE, atk)
			.add(EntityAttributes.MOVEMENT_SPEED, speed)
			.add(EntityAttributes.FOLLOW_RANGE, 32.0);
	}

	/** Registers default attributes for every roster type. Call once from init. */
	public static void registerAttributes() {
		FabricDefaultAttributeRegistry.register(GOBLIN_SKIRMISHER, attrs(12.0, 2.0, 0.30));
		FabricDefaultAttributeRegistry.register(FOOTMAN, attrs(20.0, 3.0, 0.25));
		FabricDefaultAttributeRegistry.register(ARCHER, attrs(16.0, 4.0, 0.25));
		FabricDefaultAttributeRegistry.register(MAN_AT_ARMS, attrs(30.0, 5.0, 0.24));
		FabricDefaultAttributeRegistry.register(UNDEAD_SOLDIER, attrs(25.0, 5.0, 0.22));
		FabricDefaultAttributeRegistry.register(HEAVY_KNIGHT, attrs(60.0, 8.0, 0.20));
		// Barbarians: a rugged bruiser and the tanky straight-line siege sapper.
		FabricDefaultAttributeRegistry.register(BARBARIAN, attrs(45.0, 7.0, 0.26));
		FabricDefaultAttributeRegistry.register(BARBARIAN_SAPPER, attrs(70.0, 6.0, 0.18));

		// Allies are tuned a notch above their same-name enemy so a few can hold a line.
		FabricDefaultAttributeRegistry.register(ALLY_FOOTMAN, allyAttrs(28.0, 5.0, 0.28));
		FabricDefaultAttributeRegistry.register(ALLY_ARCHER, allyAttrs(20.0, 5.0, 0.26));
		FabricDefaultAttributeRegistry.register(ALLY_KNIGHT, allyAttrs(60.0, 8.0, 0.24));
		// Summoned skeleton archer: pretty strong (34 hp, 7 atk → hard-hitting arrows),
		// a touch nimbler and longer-sighted than the base ally archer.
		FabricDefaultAttributeRegistry.register(ALLY_SKELETON, allyAttrs(34.0, 7.0, 0.28));
		// Raised skeleton warrior: a sturdy melee bruiser (30 hp, 7 atk, brisk 0.30 speed).
		FabricDefaultAttributeRegistry.register(ALLY_SKELETON_WARRIOR, allyAttrs(30.0, 7.0, 0.30));

		// Colonist: a sturdy worker — enough hp to survive stray mobs, a light melee hit
		// (used when hunting), and a brisk work pace.
		FabricDefaultAttributeRegistry.register(COLONIST, allyAttrs(24.0, 3.0, 0.30));
	}

	/** All roster types, ordered light -> heavy (used by the wave composer). */
	@SuppressWarnings("unchecked")
	public static EntityType<? extends TdEnemyEntity>[] roster() {
		return (EntityType<? extends TdEnemyEntity>[]) new EntityType<?>[] {
			GOBLIN_SKIRMISHER, FOOTMAN, ARCHER, MAN_AT_ARMS, UNDEAD_SOLDIER, HEAVY_KNIGHT,
			BARBARIAN, BARBARIAN_SAPPER,
		};
	}

	/** Forces class load so the static registrations above run. */
	public static void initialize() {
	}
}
