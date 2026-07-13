package net.bubblesky.towerdefense.registry;

import net.bubblesky.towerdefense.TowerDefenseMod;
import net.bubblesky.towerdefense.entity.FlagArrowEntity;
import net.bubblesky.towerdefense.entity.TdArcherEnemy;
import net.bubblesky.towerdefense.entity.TdEnemyEntity;
import net.bubblesky.towerdefense.entity.TdFriendlyEntity;
import net.bubblesky.towerdefense.entity.TdMeleeEnemy;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.PathAwareEntity;
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

	public static final EntityType<TdFriendlyEntity> HIRED_MILITIA = registerFriendly("hired_militia");
	public static final EntityType<TdFriendlyEntity> HIRED_ARCHER = registerFriendly("hired_archer");
	public static final EntityType<TdFriendlyEntity> HIRED_SHIELD_GUARD = registerFriendly("hired_shield_guard");
	public static final EntityType<TdFriendlyEntity> HIRED_HEAVY_KNIGHT = registerFriendly("hired_heavy_knight");
	public static final EntityType<TdFriendlyEntity> HIRED_WIZARD = registerFriendly("hired_wizard");

	// ---- projectiles ------------------------------------------------------
	/** The Flag Bow's arrow: plants a Layout flag wherever it lands. */
	public static final EntityType<FlagArrowEntity> FLAG_ARROW = registerFlagArrow("flag_arrow");

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

	private static EntityType<TdFriendlyEntity> registerFriendly(String name) {
		RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE,
			Identifier.of(TowerDefenseMod.MOD_ID, name));
		EntityType<TdFriendlyEntity> type = EntityType.Builder
			.create(TdFriendlyEntity::new, SpawnGroup.CREATURE)
			.dimensions(0.6f, 1.95f).maxTrackingRange(48).build(key);
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

	/** Registers default attributes for every roster type. Call once from init. */
	public static void registerAttributes() {
		FabricDefaultAttributeRegistry.register(GOBLIN_SKIRMISHER, attrs(12.0, 2.0, 0.30));
		FabricDefaultAttributeRegistry.register(FOOTMAN, attrs(20.0, 3.0, 0.25));
		FabricDefaultAttributeRegistry.register(ARCHER, attrs(16.0, 4.0, 0.25));
		FabricDefaultAttributeRegistry.register(MAN_AT_ARMS, attrs(30.0, 5.0, 0.24));
		FabricDefaultAttributeRegistry.register(UNDEAD_SOLDIER, attrs(25.0, 5.0, 0.22));
		FabricDefaultAttributeRegistry.register(HEAVY_KNIGHT, attrs(60.0, 8.0, 0.20));
		FabricDefaultAttributeRegistry.register(HIRED_MILITIA, friendlyAttrs(24.0, 4.0, 0.28));
		FabricDefaultAttributeRegistry.register(HIRED_ARCHER, friendlyAttrs(20.0, 3.0, 0.27));
		FabricDefaultAttributeRegistry.register(HIRED_SHIELD_GUARD,
			friendlyAttrs(50.0, 4.0, 0.22).add(EntityAttributes.KNOCKBACK_RESISTANCE, 0.7));
		FabricDefaultAttributeRegistry.register(HIRED_HEAVY_KNIGHT, friendlyAttrs(70.0, 9.0, 0.20));
		FabricDefaultAttributeRegistry.register(HIRED_WIZARD, friendlyAttrs(26.0, 3.0, 0.25));
	}

	private static DefaultAttributeContainer.Builder friendlyAttrs(double hp, double atk, double speed) {
		return PathAwareEntity.createMobAttributes()
			.add(EntityAttributes.MAX_HEALTH, hp).add(EntityAttributes.ATTACK_DAMAGE, atk)
			.add(EntityAttributes.MOVEMENT_SPEED, speed).add(EntityAttributes.FOLLOW_RANGE, 24.0);
	}

	/** All roster types, ordered light -> heavy (used by the wave composer). */
	@SuppressWarnings("unchecked")
	public static EntityType<? extends TdEnemyEntity>[] roster() {
		return (EntityType<? extends TdEnemyEntity>[]) new EntityType<?>[] {
			GOBLIN_SKIRMISHER, FOOTMAN, ARCHER, MAN_AT_ARMS, UNDEAD_SOLDIER, HEAVY_KNIGHT,
		};
	}

	/** Forces class load so the static registrations above run. */
	public static void initialize() {
	}
}
