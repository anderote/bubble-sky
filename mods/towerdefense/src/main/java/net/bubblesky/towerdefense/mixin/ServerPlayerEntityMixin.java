package net.bubblesky.towerdefense.mixin;

import net.bubblesky.towerdefense.spell.SpellItem;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes {@link SpellItem}s NON-droppable. Spell items are class-kit grants
 * ({@code ClassLoadout}) that live in reserved hotbar slots; a player who tosses one (or
 * dies) shouldn't be able to lose it — or litter the ground with copies that the respawn
 * re-grant would then duplicate.
 *
 * <h2>Why THIS injection point</h2>
 * {@code ServerPlayerEntity#dropItem(ItemStack, boolean, boolean)} (the override of
 * {@code LivingEntity}'s method) is the single server-side funnel every player item-drop
 * flows through in 1.21.6:
 * <ul>
 *   <li><b>Q / Ctrl-Q</b> — {@code ServerPlayerEntity#dropSelectedItem} removes the stack
 *       from the inventory, then calls {@code dropItem(stack, false, true)}.</li>
 *   <li><b>Dragging out of an inventory screen</b> — {@code ScreenHandler}'s throw-click
 *       (slot -999) calls {@code player.dropItem(cursorStack, true)} which delegates here.</li>
 *   <li><b>Death</b> — {@code PlayerInventory#dropAll} AND {@code EntityEquipment#dropAll}
 *       (offhand/armor) each call {@code dropItem(stack, true, false)} per stack.</li>
 * </ul>
 * Cancelling at HEAD with a {@code null} return therefore blocks every path at once, and
 * every vanilla caller already null-checks the result (an empty stack drops {@code null}
 * normally), so no caller breaks.
 *
 * <h2>Alive vs dead</h2>
 * <ul>
 *   <li><b>Alive</b> (manual drop): the calling path already removed the stack from the
 *       inventory/cursor, so we re-insert it ({@code insertStack} absorbs the stack, so the
 *       caller's follow-up "clear the source slot" is a harmless no-op) and force a full
 *       client resync via {@code currentScreenHandler.syncState()} — the client PREDICTED
 *       the drop locally, and without the resync its inventory would show the item gone.</li>
 *   <li><b>Dead</b> (death-drop sweep): the stack is simply VOIDED (cancel, no re-insert).
 *       {@code ProgressEvents}' AFTER_RESPAWN hook re-grants the whole class loadout on
 *       respawn, so re-inserting mid-{@code dropAll} would only survive into a random
 *       already-swept slot and DUPLICATE the spell next to the fresh grant.</li>
 * </ul>
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {

	@Inject(
		method = "dropItem(Lnet/minecraft/item/ItemStack;ZZ)Lnet/minecraft/entity/ItemEntity;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void towerdefense$blockSpellItemDrop(ItemStack stack, boolean throwRandomly,
			boolean retainOwnership, CallbackInfoReturnable<ItemEntity> cir) {
		if (stack.isEmpty() || !(stack.getItem() instanceof SpellItem)) {
			return;
		}
		ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
		if (self.isAlive()) {
			// Manual drop: put the (already-removed) stack back into the inventory. If the
			// inventory is somehow full (e.g. thrown from another screen's cursor), the
			// remainder is voided — acceptable, since the loadout re-grants spells anyway.
			self.getInventory().insertStack(stack);
			// The client predicted this drop; force-sync inventory + cursor so it un-predicts.
			self.currentScreenHandler.syncState();
		}
		// Dead: void the stack — the respawn re-grant restores it, so nothing hits the ground.
		cir.setReturnValue(null);
	}
}
