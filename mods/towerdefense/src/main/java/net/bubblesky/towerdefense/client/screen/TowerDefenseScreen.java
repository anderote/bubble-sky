package net.bubblesky.towerdefense.client.screen;

import java.util.ArrayList;
import java.util.List;
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
 * <p>The shop rows are built from the {@link TdCommand} catalogues (towers,
 * materials, hires, equipment) so entries and prices always match {@code /td buy}.
 * A live status line (coins / wave / enemies) is drawn from {@link TdClientStatus},
 * which reads only vanilla-synced state.
 *
 * <p>The RIGHT column (the shop) is a SCROLLABLE list: the catalogues have grown
 * past what fits on screen, so instead of shrinking rows to squeeze everything in,
 * the column clips to a viewport (scissor), scrolls with the mouse wheel, and shows
 * a slim scrollbar when there is overflow. Shop rows are NOT screen children —
 * clicks are routed manually so the clipped part of a half-visible row can never
 * be clicked.
 *
 * <p>Note: {@code /td} requires operator permission on the server; a non-op player
 * pressing these buttons will simply get the server's "unknown command" response.
 */
public class TowerDefenseScreen extends Screen {
	private static final int BUTTON_H = 20;
	private static final int GAP = 4;
	/** Height of one shop row (button + gap); also the wheel-notch scroll step. */
	private static final int SHOP_STEP = BUTTON_H + GAP;
	/** Height reserved for a gold section header inside the shop list. */
	private static final int HEADER_H = 14;

	private static final int[] QTYS = {1, 5, 10};
	private int selectedTier = 1;
	private int qtyIndex = 0; // index into QTYS

	// ---- scrollable shop column (right side) --------------------------------
	/** A shop button plus its fixed Y offset within the scrolled content. */
	private record ShopRow(ButtonWidget button, int relY) {
	}

	/** A gold section header drawn between shop groups, at its content offset. */
	private record ShopHeader(String label, int relY) {
	}

	private final List<ShopRow> shopRows = new ArrayList<>();
	private final List<ShopHeader> shopHeaders = new ArrayList<>();
	/** Scroll offset in pixels (0 = top). A field, not a local, so it survives the
	 *  clearAndInit() the Tier/Qty toggles trigger and the list doesn't jump back up. */
	private double scrollAmount = 0;
	private int shopX;
	private int shopW;
	private int shopTop;
	private int shopBottom;
	private int shopContentHeight;

	public TowerDefenseScreen() {
		super(Text.literal("Tower Defense"));
	}

	@Override
	protected void init() {
		shopRows.clear();
		shopHeaders.clear();

		int colW = 150;
		int cx = this.width / 2;
		// Two columns of controls, centred, starting below the title + status line.
		int leftX = cx - colW - GAP;
		int rightX = cx + GAP;

		int top = 52;
		int bottomReserve = 30;                  // room for the Close button

		// The LEFT column still adapts its row step so all its rows fit without
		// scrolling (it is short and fixed-size). The RIGHT column no longer factors
		// into this: it scrolls instead of squeezing.
		int leftRows = 10;                       // 7 controls + 3 orders (update if you add/remove)
		int avail = this.height - top - bottomReserve;
		int step = Math.max(16, Math.min(BUTTON_H + GAP, avail / Math.max(1, leftRows)));
		int rowH = step - 2;                     // button height slightly less than the step

		// ---- left column: game controls -----------------------------------
		int ly = top;
		addButton(leftX, ly, colW, rowH, Text.literal("Set Up Arena (here)"),
			b -> run("td arena", false));
		ly += step;
		addButton(leftX, ly, colW, rowH, Text.literal("Start / Next Wave"),
			b -> run("td wave", false));
		ly += step;
		addButton(leftX, ly, colW, rowH, Text.literal("Restart Game"),
			b -> run("td restart", false));
		ly += step;
		addButton(leftX, ly, colW, rowH, Text.literal("Reset / Stop"),
			b -> run("td reset", false));
		ly += step;
		addButton(leftX, ly, colW, rowH, Text.literal("Status"),
			b -> run("td status", false));
		ly += step;
		addButton(leftX, ly, colW, rowH, Text.literal("Help"),
			b -> run("td help", false));
		ly += step;
		addButton(leftX, ly, colW, rowH, Text.literal("Build Spells (preview)"),
			b -> {
				if (this.client != null) {
					this.client.setScreen(new ConstructionScreen());
				}
			});
		ly += step;

		// ---- left column: ally orders -------------------------------------
		addButton(leftX, ly, colW, rowH, Text.literal("Order: Hold (defend)"),
			b -> run("td command hold", false));
		ly += step;
		addButton(leftX, ly, colW, rowH, Text.literal("Order: Attack (advance)"),
			b -> run("td command attack", false));
		ly += step;
		addButton(leftX, ly, colW, rowH, Text.literal("Order: Follow me"),
			b -> run("td command follow", false));

		// ---- right column: the scrollable shop list -----------------------
		shopX = rightX;
		shopW = colW;
		shopTop = top;
		shopBottom = this.height - bottomReserve;

		int rel = 0;
		rel = addShopButton(rel, Text.literal("Shop (list towers)"),
			b -> run("td shop", false));
		rel = addShopButton(rel, Text.literal("Tier: " + selectedTier + " / " + net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity.MAX_TIER),
			b -> { selectedTier = selectedTier >= net.bubblesky.towerdefense.blockentity.AbstractTowerBlockEntity.MAX_TIER ? 1 : selectedTier + 1; this.clearAndInit(); });
		rel = addShopButton(rel, Text.literal("Qty: x" + QTYS[qtyIndex]),
			b -> { qtyIndex = (qtyIndex + 1) % QTYS.length; this.clearAndInit(); });

		rel = addShopHeader(rel, "Towers");
		for (TdCommand.ShopEntry entry : TdCommand.catalogue()) {
			int qty = QTYS[qtyIndex];
			int cost = TdCommand.costToTier(entry.price(), selectedTier) * qty;
			String label = "Buy " + prettify(entry.id()) + " T" + selectedTier + " x" + qty + " (" + cost + ")";
			// Buying hands over placeable tower block(s) pre-built to the selected tier —
			// the player places them to raise the towers. Close the menu so they can place
			// right away.
			rel = addShopButton(rel, Text.literal(label),
				b -> run("td buy " + entry.id() + " " + qty + " " + selectedTier, true));
		}
		rel = addShopButton(rel, Text.literal("Upgrade (aim at tower)"),
			b -> run("td upgrade", true));

		// Blocks bought straight into the inventory for walling lanes / building mazes.
		// They don't need the crosshair, so the menu stays open after buying.
		rel = addShopHeader(rel, "Build Materials");
		for (TdCommand.MaterialEntry entry : TdCommand.materialCatalogue()) {
			String label = "Buy " + prettify(entry.id()) + " x" + entry.count() + " (" + entry.price() + ")";
			rel = addShopButton(rel, Text.literal(label),
				b -> run("td buy " + entry.id(), false));
		}

		rel = addShopHeader(rel, "Hire Allies");
		for (TdCommand.HireEntry entry : TdCommand.hireCatalogue()) {
			String label = "Hire " + prettify(entry.id()) + " (" + entry.price() + ")";
			rel = addShopButton(rel, Text.literal(label),
				b -> run("td hire " + entry.id(), false));
		}

		// Personal gear bundles — vanilla armour/weapons straight to the inventory,
		// so the menu stays open like the materials rows.
		rel = addShopHeader(rel, "Equipment");
		for (TdCommand.EquipEntry entry : TdCommand.equipmentCatalogue()) {
			String label = "Buy " + prettify(entry.id()) + " (" + entry.price() + ")";
			rel = addShopButton(rel, Text.literal(label),
				b -> run("td buy " + entry.id(), false));
		}
		shopContentHeight = rel;

		// Clamp a stale offset (window resize / catalogue change) and place every row.
		scrollAmount = clampScroll(scrollAmount);
		applyScroll();

		// ---- close --------------------------------------------------------
		int bottom = this.height - bottomReserve + 4;
		addButton(cx - 50, bottom, 100, rowH, Text.literal("Close"), b -> this.close());
	}

	private void addButton(int x, int y, int w, int h, Text label, ButtonWidget.PressAction action) {
		this.addDrawableChild(ButtonWidget.builder(label, action).dimensions(x, y, w, h).build());
	}

	/**
	 * Append one button row to the scrolled shop content. Deliberately NOT added as a
	 * screen child: rendering happens inside the scissored viewport and clicks are
	 * routed manually in {@link #mouseClicked}, both offset-aware.
	 * Returns the next row's content offset.
	 */
	private int addShopButton(int relY, Text label, ButtonWidget.PressAction action) {
		ButtonWidget button = ButtonWidget.builder(label, action)
			.dimensions(shopX, shopTop + relY, shopW, BUTTON_H).build();
		shopRows.add(new ShopRow(button, relY));
		return relY + SHOP_STEP;
	}

	/** Append a gold section header row to the scrolled shop content. */
	private int addShopHeader(int relY, String label) {
		shopHeaders.add(new ShopHeader(label, relY));
		return relY + HEADER_H;
	}

	/** Pixels of content hidden below the viewport when scrolled to the top (0 = it all fits). */
	private int maxScroll() {
		return Math.max(0, shopContentHeight - (shopBottom - shopTop));
	}

	private double clampScroll(double value) {
		return Math.max(0, Math.min(value, maxScroll()));
	}

	/**
	 * Re-position every shop row for the current scroll offset, and hide rows fully
	 * outside the viewport so stale hover/click states can't linger on them.
	 */
	private void applyScroll() {
		int off = (int) Math.round(scrollAmount);
		for (ShopRow row : shopRows) {
			int y = shopTop + row.relY() - off;
			row.button().setY(y);
			row.button().visible = y + BUTTON_H > shopTop && y < shopBottom;
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		// The shop list is the only scrollable thing on this screen, so any wheel
		// movement scrolls it — no need to hover the column precisely mid-fight.
		if (maxScroll() > 0) {
			scrollAmount = clampScroll(scrollAmount - verticalAmount * SHOP_STEP);
			applyScroll();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// Shop rows are not children: route clicks manually, and only when the cursor
		// is inside the viewport, so the clipped part of a half-visible row is dead.
		if (mouseY >= shopTop && mouseY < shopBottom) {
			for (ShopRow row : shopRows) {
				if (row.button().visible && row.button().mouseClicked(mouseX, mouseY, button)) {
					return true;
				}
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
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

		// ---- the scrolled shop column, clipped to its viewport ------------
		context.enableScissor(shopX, shopTop, shopX + shopW, shopBottom);
		int off = (int) Math.round(scrollAmount);
		for (ShopHeader header : shopHeaders) {
			int y = shopTop + header.relY() - off;
			if (y + HEADER_H > shopTop && y < shopBottom) {
				context.drawTextWithShadow(this.textRenderer,
					Text.literal(header.label()).formatted(Formatting.GOLD, Formatting.BOLD),
					shopX + 2, y + 3, 0xFFFFFF);
			}
		}
		// Suppress hover highlights while the cursor is outside the viewport, so a
		// row peeking out from under the clip edge doesn't light up misleadingly.
		boolean cursorInView = mouseY >= shopTop && mouseY < shopBottom;
		int hoverX = cursorInView ? mouseX : -9999;
		int hoverY = cursorInView ? mouseY : -9999;
		for (ShopRow row : shopRows) {
			if (row.button().visible) {
				row.button().render(context, hoverX, hoverY, delta);
			}
		}
		context.disableScissor();

		// ---- scrollbar (only when there is overflow) ----------------------
		int max = maxScroll();
		if (max > 0) {
			int trackX = shopX + shopW + 2;
			int viewH = shopBottom - shopTop;
			context.fill(trackX, shopTop, trackX + 4, shopBottom, 0x66000000);
			int thumbH = Math.max(10, viewH * viewH / shopContentHeight);
			int thumbY = shopTop + (int) Math.round((viewH - thumbH) * (scrollAmount / max));
			context.fill(trackX, thumbY, trackX + 4, thumbY + thumbH, 0xFFAAAAAA);
		}
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
