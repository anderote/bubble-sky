package net.bubblesky.towerdefense.colony;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.entity.EntityData;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * A COLONIST — a named, humanoid worker the player recruits (with gold) into a
 * colony. Colonists are a specialised CLONE of the {@code TdAllyEntity} order-driven
 * ally: instead of fighting waves they autonomously do useful WORK around a home
 * flag — mining ore, chopping wood, hunting animals, foraging drops — and haul the
 * yield to nearby chests.
 *
 * <p>Behaviour is driven by a {@link Job} the colonist is currently performing plus a
 * {@link #priorities} ordering over the work types: each decision the colonist picks
 * the highest-priority job that has an available target within {@link ColonyWorkGoal}'s
 * home radius of {@link #getHome()}, executed by the single custom {@link ColonyWorkGoal}
 * (the mirror of the ally's {@code AllyOrderGoal}). New vs allies: a colonist holds a
 * small {@link SimpleInventory}, breaks blocks over time, and deposits into chests.
 *
 * <p>Everything the colony needs to survive a reload — the current job, the priority
 * ordering, the home anchor, the owner, the mine-target ore filter and the carried
 * inventory — is persisted in entity NBT ({@link #writeCustomData}/{@link #readCustomData}),
 * and the entity is made {@link #setPersistent() persistent} on spawn. Colonists are
 * tagged {@link #COLONIST_TAG} so commands can find the roster.
 */
public class ColonistEntity extends PathAwareEntity {
	/** Scoreboard/command tag marking an entity as a colony colonist. */
	public static final String COLONIST_TAG = "td_colonist";

	/** Number of carry slots in a colonist's backpack. */
	public static final int INVENTORY_SIZE = 9;

	/** The work types a colonist can be assigned. IDLE is the "nothing to do" fallback. */
	public enum Job { MINE, CHOP, HUNT, FORAGE, HAUL, IDLE }

	/** The auto-name pool — colonists are christened from here on spawn (round-robin-ish). */
	private static final String[] NAME_POOL = {
		"Alden", "Bram", "Cara", "Dara", "Enoch", "Fenn", "Greta", "Hale",
		"Isolde", "Jorun", "Kesler", "Lira", "Maddox", "Nessa", "Orrin", "Perrin",
		"Quill", "Rhea", "Sten", "Tavish", "Ulla", "Voss", "Wren", "Yara",
	};
	/** Rotating index so successive recruits in a session draw distinct names. */
	private static int nameCursor = 0;

	/** The colonist's current work (drives the name tag + the work goal). */
	private Job job = Job.IDLE;
	/** Priority ordering over work types — the colonist runs the highest available. */
	private final List<Job> priorities = new ArrayList<>(List.of(
		Job.HAUL, Job.MINE, Job.CHOP, Job.FORAGE, Job.HUNT));
	/** Home anchor (the colony flag this colonist is bound to). */
	@Nullable
	private BlockPos home;
	/** The player who recruited / commands this colonist. */
	@Nullable
	private UUID owner;
	/** Ore keyword the MINE job seeks (e.g. {@code "iron"}); {@code null} = the default valuable set. */
	@Nullable
	private String oreFilter;
	/** The colonist's carry backpack — filled by gather jobs, emptied by HAUL. */
	private final SimpleInventory inventory = new SimpleInventory(INVENTORY_SIZE);
	/** One-shot latch so the "no chest to haul to" warning is only announced once. */
	private boolean warnedNoChest = false;
	/** Transient deposit hint (e.g. the chest the commanding player was looking at); not persisted. */
	@Nullable
	private BlockPos preferredChest;

	public ColonistEntity(EntityType<? extends ColonistEntity> type, World world) {
		super(type, world);
	}

	@Override
	protected void initGoals() {
		this.goalSelector.add(0, new SwimGoal(this));
		// The single rule-based work brain — mirrors the ally's order goal.
		this.goalSelector.add(2, new ColonyWorkGoal(this));
		this.goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		this.goalSelector.add(8, new LookAroundGoal(this));
	}

	// ---- spawn kit ---------------------------------------------------------
	/**
	 * On spawn (the recruit path — not NBT reload) give the colonist a working name, a
	 * visible task name tag, and a pickaxe/axe in hand (locked so it never drops). The
	 * held tool is what {@code ColonyWorkGoal} passes to the loot tables so ore actually
	 * yields its raw drop.
	 */
	@Override
	public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty,
			SpawnReason spawnReason, @Nullable EntityData entityData) {
		EntityData data = super.initialize(world, difficulty, spawnReason, entityData);
		if (this.getCustomName() == null) {
			assignName();
		}
		// A pickaxe is standing-issue kit, not loot — never drop it.
		this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			this.setEquipmentDropChance(slot, 0.0f);
		}
		this.setCustomNameVisible(true);
		refreshNameTag();
		return data;
	}

	/** Pick the next name from the pool and set it as the base custom name. */
	private void assignName() {
		String name = NAME_POOL[Math.floorMod(nameCursor++, NAME_POOL.length)];
		this.setCustomName(Text.literal(name));
	}

	/** The bare colonist name (without the task decoration), for command feedback / matching. */
	public String getColonistName() {
		Text name = this.getCustomName();
		if (name == null) {
			return "Colonist";
		}
		// Strip any "⛏ Name — task" decoration back to just the name.
		String s = name.getString();
		int dash = s.indexOf(" — ");
		if (dash >= 0) {
			s = s.substring(0, dash);
		}
		// Drop a leading emoji + space if present.
		int space = s.indexOf(' ');
		if (space > 0 && s.charAt(0) > 0x2000) {
			s = s.substring(space + 1);
		}
		return s.trim();
	}

	// ---- job / priority state ---------------------------------------------
	public Job getJob() {
		return job;
	}

	/** Set the active job and refresh the floating task tag. */
	public void setJob(Job job) {
		this.job = job;
		refreshNameTag();
	}

	public List<Job> getPriorities() {
		return priorities;
	}

	/** Bump a work type to the FRONT of this colonist's priority list. */
	public void prioritize(Job work) {
		priorities.remove(work);
		priorities.add(0, work);
	}

	@Nullable
	public BlockPos getHome() {
		return home;
	}

	public void setHome(@Nullable BlockPos home) {
		this.home = home;
	}

	@Nullable
	public UUID getOwner() {
		return owner;
	}

	public void setOwner(@Nullable UUID owner) {
		this.owner = owner;
	}

	@Nullable
	public String getOreFilter() {
		return oreFilter;
	}

	public void setOreFilter(@Nullable String oreFilter) {
		this.oreFilter = oreFilter;
	}

	public SimpleInventory getInventory() {
		return inventory;
	}

	public boolean hasWarnedNoChest() {
		return warnedNoChest;
	}

	public void setWarnedNoChest(boolean warned) {
		this.warnedNoChest = warned;
	}

	@Nullable
	public BlockPos getPreferredChest() {
		return preferredChest;
	}

	public void setPreferredChest(@Nullable BlockPos preferredChest) {
		this.preferredChest = preferredChest;
	}

	/** Resolve the owning player if loaded, else null. */
	@Nullable
	public PlayerEntity resolveOwner() {
		if (owner == null) {
			return null;
		}
		return this.getWorld().getPlayerByUuid(owner);
	}

	// ---- inventory helpers -------------------------------------------------
	/** True when no backpack slot can accept any more items. */
	public boolean isInventoryFull() {
		for (int i = 0; i < inventory.size(); i++) {
			ItemStack stack = inventory.getStack(i);
			if (stack.isEmpty() || stack.getCount() < stack.getMaxCount()) {
				return false;
			}
		}
		return true;
	}

	/** True when the backpack holds nothing. */
	public boolean isInventoryEmpty() {
		return inventory.isEmpty();
	}

	// ---- name tag ----------------------------------------------------------
	/** Rebuild the floating name tag as {@code "<glyph> <name> — <task>"}. */
	public void refreshNameTag() {
		String name = getColonistName();
		String glyph;
		String task;
		switch (job) {
			case MINE -> { glyph = "⛏"; task = "mining" + (oreFilter != null ? " " + oreFilter : ""); }
			case CHOP -> { glyph = "🪓"; task = "chopping"; }
			case HUNT -> { glyph = "🏹"; task = "hunting"; }
			case FORAGE -> { glyph = "🧺"; task = "foraging"; }
			case HAUL -> { glyph = "📦"; task = "hauling"; }
			default -> { glyph = "💤"; task = "idle"; }
		}
		this.setCustomName(Text.literal(glyph + " " + name)
			.formatted(Formatting.AQUA)
			.append(Text.literal(" — " + task).formatted(Formatting.GRAY)));
		this.setCustomNameVisible(true);
	}

	// ---- persistence -------------------------------------------------------
	@Override
	protected void writeCustomData(WriteView view) {
		super.writeCustomData(view);
		view.putString("Job", job.name());
		StringBuilder prio = new StringBuilder();
		for (int i = 0; i < priorities.size(); i++) {
			if (i > 0) {
				prio.append(',');
			}
			prio.append(priorities.get(i).name());
		}
		view.putString("Priorities", prio.toString());
		if (home != null) {
			view.putInt("HomeX", home.getX());
			view.putInt("HomeY", home.getY());
			view.putInt("HomeZ", home.getZ());
		}
		if (owner != null) {
			view.putString("Owner", owner.toString());
		}
		if (oreFilter != null) {
			view.putString("OreFilter", oreFilter);
		}
		// The carried backpack — round-tripped via the vanilla inventory codec.
		Inventories.writeData(view.get("Backpack"), inventory.getHeldStacks());
	}

	@Override
	protected void readCustomData(ReadView view) {
		super.readCustomData(view);
		this.job = parseJob(view.getString("Job", "IDLE"), Job.IDLE);
		String prio = view.getString("Priorities", "");
		if (!prio.isEmpty()) {
			priorities.clear();
			for (String part : prio.split(",")) {
				Job j = parseJob(part, null);
				if (j != null && !priorities.contains(j)) {
					priorities.add(j);
				}
			}
			// Guarantee every work type is present (defensive against a stale/short list).
			for (Job j : List.of(Job.HAUL, Job.MINE, Job.CHOP, Job.FORAGE, Job.HUNT)) {
				if (!priorities.contains(j)) {
					priorities.add(j);
				}
			}
		}
		if (view.getOptionalInt("HomeX").isPresent()) {
			this.home = new BlockPos(
				view.getInt("HomeX", 0), view.getInt("HomeY", 0), view.getInt("HomeZ", 0));
		} else {
			this.home = null;
		}
		view.getOptionalString("Owner").ifPresent(s -> {
			try {
				this.owner = UUID.fromString(s);
			} catch (IllegalArgumentException ignored) {
				this.owner = null;
			}
		});
		this.oreFilter = view.getOptionalString("OreFilter").orElse(null);
		view.getOptionalReadView("Backpack").ifPresent(
			backpack -> Inventories.readData(backpack, inventory.getHeldStacks()));
		refreshNameTag();
	}

	@Nullable
	private static Job parseJob(String name, @Nullable Job fallback) {
		try {
			return Job.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	/** Drop the backpack contents into the world on death so gathered goods aren't lost. */
	@Override
	protected void dropInventory(net.minecraft.server.world.ServerWorld world) {
		super.dropInventory(world);
		for (int i = 0; i < inventory.size(); i++) {
			ItemStack stack = inventory.getStack(i);
			if (!stack.isEmpty()) {
				this.dropStack(world, stack);
			}
		}
		inventory.clear();
	}
}
