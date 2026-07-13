package net.bubblesky.towerdefense.client.screen;

import net.bubblesky.towerdefense.client.TdClientStatus;
import net.bubblesky.towerdefense.command.TdCommand;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The client "Tower Defense" control menu (opened with the TD keybind). Every
 * button issues one of the REAL {@code /td} subcommands via
 * {@link ClientPlayNetworkHandler#sendChatCommand(String)} — the same commands a
 * player could type — so this screen is purely a discoverable front-end and adds
 * no new server behaviour.
 *
 * <p>The shop rows are built from {@link TdCommand#catalogue()} so tower types and
 * prices always match {@code /td buy}. A live status line (coins / wave / enemies)
 * is drawn from {@link TdClientStatus}, which reads only vanilla-synced state.
 *
 * <p>Note: {@code /td} requires operator permission on the server; a non-op player
 * pressing these buttons will simply get the server's "unknown command" response.
 */
public class TowerDefenseScreen extends Screen {
	private static final int BUTTON_H = 20;
	private static final int GAP = 4;

	public TowerDefenseScreen() {
		super(Text.literal("Tower Defense"));
	}

	@Override
	protected void init() {
		int colW = 150;
		int cx = this.width / 2;
		// Two columns of controls, centred, starting below the title + status line.
		int leftX = cx - colW - GAP;
		int rightX = cx + GAP;
		int y = 56;

		// ---- left column: game controls -----------------------------------
		int ly = y;
		addButton(leftX, ly, colW, Text.literal("Set Up Arena (here)"),
			b -> run("td arena", false));
		ly += BUTTON_H + GAP;
		addButton(leftX, ly, colW, Text.literal("Start / Next Wave"),
			b -> run("td wave", false));
		ly += BUTTON_H + GAP;
		addButton(leftX, ly, colW, Text.literal("Restart Game"),
			b -> run("td restart", false));
		ly += BUTTON_H + GAP;
		addButton(leftX, ly, colW, Text.literal("Reset / Stop"),
			b -> run("td reset", false));
		ly += BUTTON_H + GAP;
		addButton(leftX, ly, colW, Text.literal("Status"),
			b -> run("td status", false));
		ly += BUTTON_H + GAP;
		addButton(leftX, ly, colW, Text.literal("Help"),
			b -> run("td help", false));
		ly += BUTTON_H + GAP + 6;

		// ---- left column: ally orders -------------------------------------
		addButton(leftX, ly, colW, Text.literal("Order: Hold (defend)"),
			b -> run("td command hold", false));
		ly += BUTTON_H + GAP;
		addButton(leftX, ly, colW, Text.literal("Order: Attack (advance)"),
			b -> run("td command attack", false));
		ly += BUTTON_H + GAP;
		addButton(leftX, ly, colW, Text.literal("Order: Follow me"),
			b -> run("td command follow", false));

		// ---- right column: shop (real catalogue) + placement --------------
		int ry = y;
		addButton(rightX, ry, colW, Text.literal("Shop (list towers)"),
			b -> run("td shop", false));
		ry += BUTTON_H + GAP;
		for (TdCommand.ShopEntry entry : TdCommand.catalogue()) {
			String label = "Buy " + prettify(entry.id()) + " (" + entry.price() + ")";
			// Buying hands over a placeable tower block — the player places it to raise
			// the tower. Close the menu so they can place it right away.
			addButton(rightX, ry, colW, Text.literal(label),
				b -> run("td buy " + entry.id(), true));
			ry += BUTTON_H + GAP;
		}
		addButton(rightX, ry, colW, Text.literal("Upgrade (aim at tower)"),
			b -> run("td upgrade", true));
		ry += BUTTON_H + GAP + 6;

		// ---- right column: hire allies (real catalogue) -------------------
		for (TdCommand.HireEntry entry : TdCommand.hireCatalogue()) {
			String label = "Hire " + prettify(entry.id()) + " (" + entry.price() + ")";
			addButton(rightX, ry, colW, Text.literal(label),
				b -> run("td hire " + entry.id(), false));
			ry += BUTTON_H + GAP;
		}

		// ---- close --------------------------------------------------------
		int bottom = Math.max(ly, ry) + BUTTON_H + GAP + 6;
		bottom = Math.min(bottom, this.height - BUTTON_H - 6);
		addButton(cx - 50, bottom, 100, Text.literal("Close"), b -> this.close());
	}

	private void addButton(int x, int y, int w, Text label, ButtonWidget.PressAction action) {
		this.addDrawableChild(ButtonWidget.builder(label, action).dimensions(x, y, w, BUTTON_H).build());
	}

	/** Send a bare {@code /td ...} command as the player; optionally close first so
	 *  the command can use the player's crosshair (placement/upgrade). */
	private void run(String command, boolean closeFirst) {
		if (closeFirst) {
			this.close();
		}
		ClientPlayNetworkHandler nh = MinecraftClient.getInstance().getNetworkHandler();
		if (nh != null) {
			nh.sendChatCommand(command);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int cx = this.width / 2;

		context.drawCenteredTextWithShadow(this.textRenderer,
			Text.literal("Tower Defense").formatted(Formatting.GOLD, Formatting.BOLD),
			cx, 16, 0xFFFFFF);

		int coins = TdClientStatus.coins();
		int wave = TdClientStatus.wave();
		int enemies = TdClientStatus.enemiesVisible();
		String waveText = wave < 0 ? "-" : Integer.toString(wave);
		Text status = Text.literal("Coins: ")
			.append(Text.literal(Integer.toString(coins)).formatted(Formatting.AQUA))
			.append(Text.literal("   Wave: ").formatted(Formatting.WHITE))
			.append(Text.literal(waveText).formatted(Formatting.YELLOW))
			.append(Text.literal("   Enemies: ").formatted(Formatting.WHITE))
			.append(Text.literal(Integer.toString(enemies)).formatted(Formatting.RED));
		context.drawCenteredTextWithShadow(this.textRenderer, status, cx, 34, 0xFFFFFF);
	}

	/** Prettify a snake_case tower id for display, e.g. {@code arrow_tower -> Arrow Tower}. */
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
		// Don't pause: this is (usually) a live multiplayer match.
		return false;
	}
}
