package net.bubblesky.towerdefense.spell;

import net.bubblesky.towerdefense.progression.PlayerProgress;
import net.bubblesky.towerdefense.progression.ProgressEvents;
import net.bubblesky.towerdefense.progression.ProgressState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * A castable SPELL as a hotbar item. Each registered {@code SpellItem} carries exactly one
 * {@link SpellType} (fixed at construction, mirroring how {@code TowerArrowItem} carries a
 * {@code TowerKind}) — the whole spell system rides on one item class rather than a subclass
 * per spell.
 *
 * <h2>Casting (right-click)</h2>
 * {@link #use} runs entirely server-side:
 * <ol>
 *   <li>On the client (or for anything but a fully-resolved server player/world) it just
 *       PASSes — the server is authoritative.</li>
 *   <li>If the item is still on cooldown, it fails with an action-bar note.</li>
 *   <li>If the player has less than {@link SpellType#manaCost() manaCost} mana, it fails with
 *       a "Not enough mana" note.</li>
 *   <li>Otherwise it spends the mana, casts the spell along the player's look vector, starts
 *       the item cooldown ({@link SpellType#cooldownTicks()}), persists + resyncs progression,
 *       and plays cast feedback.</li>
 * </ol>
 * Mana lives on {@link PlayerProgress} (the same record the HUD reads); spending it here and
 * regenerating it in {@code ProgressEvents} both flow to the client through
 * {@link ProgressEvents#sync}.
 */
public class SpellItem extends Item {

	/** The spell this item casts. Immutable; set per registered item in {@code ModItems}. */
	private final SpellType spell;

	public SpellItem(Settings settings, SpellType spell) {
		super(settings);
		this.spell = spell;
	}

	/** The {@link SpellType} this item casts. */
	public SpellType spell() {
		return spell;
	}

	@Override
	public ActionResult use(World world, net.minecraft.entity.player.PlayerEntity user, Hand hand) {
		// Client side predicts nothing — the server owns mana, cooldown and the cast.
		if (world.isClient) {
			return ActionResult.PASS;
		}
		if (!(world instanceof ServerWorld serverWorld) || !(user instanceof ServerPlayerEntity caster)) {
			return ActionResult.PASS;
		}
		ItemStack stack = user.getStackInHand(hand);

		// Cooldown gate.
		if (caster.getItemCooldownManager().isCoolingDown(stack)) {
			return ActionResult.PASS;
		}

		// Mana gate.
		MinecraftServer server = caster.getServer();
		if (server == null) {
			return ActionResult.PASS;
		}
		ProgressState state = ProgressState.get(server);
		PlayerProgress progress = state.forPlayer(caster.getUuid());
		if (progress.getMana() < spell.manaCost()) {
			caster.sendMessage(Text.literal("Not enough mana for " + spell.displayName()
				+ " (" + progress.getMana() + "/" + spell.manaCost() + ").").formatted(Formatting.RED), true);
			return ActionResult.FAIL;
		}

		// Spend, cast, cool down, resync.
		progress.addMana(-spell.manaCost());
		state.markDirty();
		Vec3d aim = caster.getRotationVector();
		spell.cast(serverWorld, caster, aim);
		caster.getItemCooldownManager().set(stack, spell.cooldownTicks());
		ProgressEvents.sync(caster, progress);
		return ActionResult.SUCCESS;
	}
}
