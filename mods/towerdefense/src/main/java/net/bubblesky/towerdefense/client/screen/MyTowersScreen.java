package net.bubblesky.towerdefense.client.screen;

import java.util.List;
import net.bubblesky.towerdefense.client.ClientTowers;
import net.bubblesky.towerdefense.tower.TowerKind;
import net.bubblesky.towerdefense.towerui.net.TowerActionPayload;
import net.bubblesky.towerdefense.towerui.net.TowerRosterPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * "My Towers" panel (opened with K). Left: a grid of the player's placed towers; click
 * one to select. Right: the selected tower's live stats with Upgrade and Sell buttons
 * that send a {@link TowerActionPayload}; the server validates and resyncs, so the panel
 * updates live. Front-end only over the cached {@link ClientTowers} snapshot.
 */
public class MyTowersScreen extends Screen {
	private static final int COLS = 4;
	private static final int ICON = 40;
	private int selected = 0;
	private ButtonWidget upgradeButton;
	private ButtonWidget sellButton;

	public MyTowersScreen() {
		super(Text.literal("My Towers"));
	}

	@Override
	protected void init() {
		int detailX = this.width / 2 + 20;
		int by = this.height - 62;
		upgradeButton = ButtonWidget.builder(Text.literal("Upgrade"), b -> act(TowerActionPayload.ACTION_UPGRADE))
			.dimensions(detailX, by, 170, 20).build();
		sellButton = ButtonWidget.builder(Text.literal("Sell"), b -> act(TowerActionPayload.ACTION_SELL))
			.dimensions(detailX, by + 24, 170, 20).build();
		this.addDrawableChild(upgradeButton);
		this.addDrawableChild(sellButton);
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> this.close())
			.dimensions(this.width / 2 - 50, this.height - 28, 100, 20).build());
	}

	private int gridX() {
		return this.width / 2 - 20 - COLS * ICON;
	}

	private static final int GRID_Y = 60;

	private void act(int action) {
		List<TowerRosterPayload.Row> rows = ClientTowers.rows();
		if (selected >= 0 && selected < rows.size()) {
			ClientPlayNetworking.send(new TowerActionPayload(rows.get(selected).posId(), action));
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		List<TowerRosterPayload.Row> rows = ClientTowers.rows();
		for (int i = 0; i < rows.size(); i++) {
			int x = gridX() + (i % COLS) * ICON;
			int y = GRID_Y + (i / COLS) * ICON;
			if (mouseX >= x && mouseX < x + ICON - 3 && mouseY >= y && mouseY < y + ICON - 3) {
				selected = i;
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int cx = this.width / 2;
		context.drawCenteredTextWithShadow(this.textRenderer,
			Text.literal("My Towers").formatted(Formatting.GOLD, Formatting.BOLD), cx, 16, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer,
			Text.literal("Coins: " + ClientTowers.coins()).formatted(Formatting.YELLOW), cx, 34, 0xFFFFFFFF);

		List<TowerRosterPayload.Row> rows = ClientTowers.rows();
		if (selected >= rows.size()) {
			selected = Math.max(0, rows.size() - 1);
		}
		for (int i = 0; i < rows.size(); i++) {
			int x = gridX() + (i % COLS) * ICON;
			int y = GRID_Y + (i / COLS) * ICON;
			context.fill(x, y, x + ICON - 3, y + ICON - 3, i == selected ? 0xFF5A5A5A : 0xFF2A2A2A);
			TowerRosterPayload.Row r = rows.get(i);
			TowerKind kind = TowerKind.fromOrdinal(r.kindOrdinal());
			context.drawTextWithShadow(this.textRenderer, Text.literal(kindShort(kind)), x + 4, y + 5, 0xFFFFFFFF);
			context.drawTextWithShadow(this.textRenderer,
				Text.literal("T" + r.tier() + (r.maxed() ? "*" : "")).formatted(Formatting.AQUA),
				x + 4, y + 20, 0xFFFFFFFF);
		}
		renderDetail(context, rows);
	}

	private void renderDetail(DrawContext context, List<TowerRosterPayload.Row> rows) {
		int x = this.width / 2 + 20;
		int y = 60;
		boolean has = selected >= 0 && selected < rows.size();
		upgradeButton.active = has && !rows.get(selected).maxed();
		sellButton.active = has;
		if (!has) {
			context.drawTextWithShadow(this.textRenderer,
				Text.literal("No towers yet. Buy one with /td buy or the J menu.").formatted(Formatting.GRAY),
				x, y, 0xFFFFFFFF);
			upgradeButton.setMessage(Text.literal("Upgrade"));
			sellButton.setMessage(Text.literal("Sell"));
			return;
		}
		TowerRosterPayload.Row r = rows.get(selected);
		TowerKind kind = TowerKind.fromOrdinal(r.kindOrdinal());
		String[] lines = {
			prettify(kind.id()),
			"Tier " + r.tier() + (r.maxed() ? " (MAX)" : ""),
			"Range " + r.range() + "   Damage x" + String.format("%.2f", r.dmgPct() / 100.0),
			"Cooldown " + String.format("%.1fs", r.cooldownTicks() / 20.0),
			r.maxed() ? "Upgrade: MAX tier" : ("Upgrade cost: " + r.upgradeCost() + " coins"),
			"Sell refund: " + r.refund() + " coins",
		};
		for (int i = 0; i < lines.length; i++) {
			context.drawTextWithShadow(this.textRenderer, Text.literal(lines[i]), x, y + i * 13, 0xFFFFFFFF);
		}
		upgradeButton.setMessage(Text.literal(r.maxed() ? "Upgrade — MAX" : "Upgrade — " + r.upgradeCost()));
		sellButton.setMessage(Text.literal("Sell — +" + r.refund()));
	}

	private static String kindShort(TowerKind kind) {
		return prettify(kind.id().replace("_tower", ""));
	}

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
