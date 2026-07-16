package net.bubblesky.towerdefense.client.screen;

import net.bubblesky.towerdefense.tower.TowerKind;
import net.bubblesky.towerdefense.towerui.net.OpenTowerMenuPayload;
import net.bubblesky.towerdefense.towerui.net.TowerActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Per-tower CONTEXT MENU, opened by PUNCHING / start-mining a tower block (the
 * {@code AttackBlockCallback} in {@code TowerUiEvents} cancels the break and pushes an
 * {@link OpenTowerMenuPayload}). Renders the tower's live stats from that snapshot and offers four
 * owner actions:
 *
 * <ul>
 *   <li><b>Upgrade</b> — spend coins for the next tier (disabled when maxed). Keeps the screen open;
 *       the server re-pushes a fresh snapshot so the stats update in place.</li>
 *   <li><b>Sell</b> — tear down for a partial coin refund.</li>
 *   <li><b>Recycle</b> — tear down and get the placeable block back in inventory (no coins).</li>
 *   <li><b>Destroy</b> — tear down with nothing returned (two-click confirm; drawn in red).</li>
 * </ul>
 *
 * <p>Non-owners see the stats but all four action buttons are disabled. Sell / Recycle / Destroy are
 * terminal, so the screen closes itself after issuing them. All action requests reuse the existing
 * {@link TowerActionPayload}; the server validates ownership + affordability and resyncs.
 */
public class TowerMenuScreen extends Screen {
	/** The stat snapshot currently being displayed (swapped in place on an in-menu Upgrade refresh). */
	private OpenTowerMenuPayload snap;

	private ButtonWidget upgradeButton;
	private ButtonWidget sellButton;
	private ButtonWidget recycleButton;
	private ButtonWidget destroyButton;

	/** Destroy is a two-click confirm: the first click ARMS it, the second actually tears down. */
	private boolean destroyArmed = false;

	public TowerMenuScreen(OpenTowerMenuPayload snap) {
		super(Text.literal(prettify(TowerKind.fromOrdinal(snap.kindOrdinal()).id())));
		this.snap = snap;
	}

	/** The tower this menu is bound to — lets the client receiver decide refresh-vs-reopen. */
	public long posId() {
		return snap.posId();
	}

	/** Swap in a fresh snapshot (e.g. after an Upgrade) and relabel the buttons without reopening. */
	public void updateSnapshot(OpenTowerMenuPayload updated) {
		this.snap = updated;
		this.destroyArmed = false;
		refreshButtons();
	}

	@Override
	protected void init() {
		int bx = this.width / 2 - 100;
		int by = this.height / 2 + 6;
		// Two rows of two action buttons, then a full-width Close below them.
		upgradeButton = ButtonWidget.builder(Text.literal("Upgrade"), b -> act(TowerActionPayload.ACTION_UPGRADE, false))
			.dimensions(bx, by, 98, 20).build();
		sellButton = ButtonWidget.builder(Text.literal("Sell"), b -> act(TowerActionPayload.ACTION_SELL, true))
			.dimensions(bx + 102, by, 98, 20).build();
		recycleButton = ButtonWidget.builder(Text.literal("Recycle"), b -> act(TowerActionPayload.ACTION_RECYCLE, true))
			.dimensions(bx, by + 24, 98, 20).build();
		destroyButton = ButtonWidget.builder(Text.literal("Destroy"), b -> onDestroy())
			.dimensions(bx + 102, by + 24, 98, 20).build();
		this.addDrawableChild(upgradeButton);
		this.addDrawableChild(sellButton);
		this.addDrawableChild(recycleButton);
		this.addDrawableChild(destroyButton);
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> this.close())
			.dimensions(bx, by + 48, 200, 20).build());
		refreshButtons();
	}

	/** Send an action for this tower. {@code terminal} actions (sell/recycle/destroy) close the menu. */
	private void act(int action, boolean terminal) {
		ClientPlayNetworking.send(new TowerActionPayload(snap.posId(), action));
		if (terminal) {
			this.close();
		}
	}

	/** Destroy needs a confirming second click; the first only arms it (and relabels in red). */
	private void onDestroy() {
		if (!destroyArmed) {
			destroyArmed = true;
			refreshButtons();
			return;
		}
		act(TowerActionPayload.ACTION_DESTROY, true);
	}

	/** Re-apply button enabled-state + labels from the current snapshot + owner/confirm flags. */
	private void refreshButtons() {
		if (upgradeButton == null) {
			return; // not initialised yet
		}
		boolean owner = snap.isOwner();
		boolean maxed = snap.upgradeCost() < 0;
		upgradeButton.active = owner && !maxed;
		sellButton.active = owner;
		recycleButton.active = owner;
		destroyButton.active = owner;
		upgradeButton.setMessage(Text.literal(maxed ? "Upgrade — MAX" : "Upgrade — " + snap.upgradeCost()));
		sellButton.setMessage(Text.literal("Sell — +" + snap.sellRefund()));
		recycleButton.setMessage(Text.literal("Recycle"));
		destroyButton.setMessage(destroyArmed
			? Text.literal("Destroy — Confirm?").formatted(Formatting.RED, Formatting.BOLD)
			: Text.literal("Destroy").formatted(Formatting.RED));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int cx = this.width / 2;
		TowerKind kind = TowerKind.fromOrdinal(snap.kindOrdinal());

		// Title = tower kind.
		context.drawCenteredTextWithShadow(this.textRenderer,
			Text.literal(prettify(kind.id())).formatted(Formatting.GOLD, Formatting.BOLD), cx, 24, 0xFFFFFFFF);

		// Stat block, centred above the buttons.
		String vet = "Veterancy: rank " + snap.veterancy() + " / " + snap.maxVeterancy()
			+ (snap.veterancy() >= snap.maxVeterancy() ? "  (MAX)" : "  (+" + snap.killsToNext() + " to next)");
		String[] lines = {
			"Tier " + snap.tier() + " / " + snap.maxTier() + (snap.upgradeCost() < 0 ? "  (MAX)" : ""),
			snap.kills() + " kills",
			vet,
			"Damage x" + String.format("%.2f", snap.dmgPct() / 100.0),
			"Range " + snap.range() + " blocks",
			"Fire rate: every " + String.format("%.1fs", snap.cooldownTicks() / 20.0)
				+ "  (" + String.format("%.2f", 20.0 / Math.max(1, snap.cooldownTicks())) + "/s)",
			"Coins invested: " + snap.invested(),
		};
		int y = this.height / 2 - 92;
		for (String line : lines) {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(line), cx, y, 0xFFFFFFFF);
			y += 12;
		}

		// Ownership footnote: non-owners can look but not touch.
		if (!snap.isOwner()) {
			context.drawCenteredTextWithShadow(this.textRenderer,
				Text.literal("Owned by " + snap.ownerName() + " — you can't modify it.").formatted(Formatting.GRAY),
				cx, y + 2, 0xFFFFFFFF);
		}
	}

	/** Turn a snake_case tower id ("arrow_tower") into a display title ("Arrow Tower"). */
	private static String prettify(String id) {
		StringBuilder sb = new StringBuilder();
		boolean cap = true;
		for (char c : id.toCharArray()) {
			if (c == '_') {
				sb.append(' ');
				cap = true;
			} else if (cap) {
				sb.append(Character.toUpperCase(c));
				cap = false;
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
