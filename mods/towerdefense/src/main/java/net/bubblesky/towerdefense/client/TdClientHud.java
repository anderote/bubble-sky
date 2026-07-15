package net.bubblesky.towerdefense.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * A small, always-on client HUD line that makes the Tower Defense menu
 * DISCOVERABLE by default. Rendered via {@link HudRenderCallback}:
 *
 * <ul>
 *   <li>when no match is running: a bottom-centre hint
 *       "Tower Defense — press [KEY] to open the menu";</li>
 *   <li>when a match IS running: a compact live line
 *       "[KEY] TD menu · Coins X · Wave Y · Enemies Z" (the fuller wave/base HUD
 *       is already provided server-side by the bossbar + sidebar).</li>
 * </ul>
 *
 * <p>Suppressed while any screen is open or the HUD is hidden (F1), so it never
 * covers menus.
 */
public final class TdClientHud {
	private TdClientHud() {
	}

	private static KeyBinding openKey;

	public static void register(KeyBinding key) {
		openKey = key;
		HudRenderCallback.EVENT.register(TdClientHud::onHudRender);
	}

	private static void onHudRender(DrawContext context, Object tickCounter) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.world == null || mc.currentScreen != null) {
			return;
		}
		if (mc.options != null && mc.options.hudHidden) {
			return;
		}

		// ---- always-on gold counter --------------------------------------------
		// The player's current coins, shown at ALL times (not just during a match),
		// mirroring the server sidebar. Drawn top-left so it sits clear of the
		// wave/idol bossbar at the top-centre.
		// Essence (the premium loot currency) rides on the same line in purple, so the two
		// spendable balances read together at a glance.
		int gold = TdClientStatus.coins();
		int essence = TdClientStatus.essence();
		Text goldLine = Text.literal("Gold: ").formatted(Formatting.GRAY)
			.append(Text.literal(Integer.toString(gold)).formatted(Formatting.GOLD))
			.append(Text.literal("   Essence: ").formatted(Formatting.GRAY))
			.append(Text.literal(Integer.toString(essence)).formatted(Formatting.LIGHT_PURPLE));
		context.drawTextWithShadow(mc.textRenderer, goldLine, 6, 6, 0xFFFFFF);

		// ---- always-on RPG level + XP bar --------------------------------------
		// Player level, a slim XP bar, and unspent-point nudge, drawn just below the gold
		// line at top-left — clear of the top-centre wave/idol bossbar. Progression is
		// permanent, so this is meaningful even outside a live match.
		int level = ClientProgress.level();
		int points = ClientProgress.unspentPoints();
		Text levelLine = Text.literal("Lvl ").formatted(Formatting.GRAY)
			.append(Text.literal(Integer.toString(level)).formatted(Formatting.AQUA));
		if (points > 0) {
			levelLine = levelLine.copy()
				.append(Text.literal("  (+" + points + " — press P)").formatted(Formatting.GREEN));
		}
		context.drawTextWithShadow(mc.textRenderer, levelLine, 6, 18, 0xFFFFFF);
		int barX = 6;
		int barY = 29;
		int barW = 80;
		int barH = 4;
		context.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF000000);
		context.fill(barX, barY, barX + barW, barY + barH, 0xFF303030);
		int filled = (int) (barW * ClientProgress.xpFraction());
		if (filled > 0) {
			context.fill(barX, barY, barX + filled, barY + barH, 0xFF39C339);
		}

		// ---- always-on MANA bar (blue) + active class ----------------------------
		// A slim blue pool beneath the XP bar, reading the synced mana/maxMana. Spells
		// spend it (SpellItem) and it regenerates server-side; the bar creeps live. When a
		// class is active, its name + class-level ride alongside so the loadout is legible.
		int maxMana = ClientProgress.maxMana();
		if (maxMana > 0) {
			int manaY = barY + barH + 4; // just below the XP bar
			Text manaLabel = Text.literal("Mana ").formatted(Formatting.GRAY)
				.append(Text.literal(ClientProgress.mana() + "/" + maxMana).formatted(Formatting.AQUA));
			if (ClientProgress.hasClass()) {
				manaLabel = manaLabel.copy().append(Text.literal("   " + classDisplay()
						+ " L" + ClientProgress.classLevel()).formatted(Formatting.LIGHT_PURPLE));
			}
			context.drawTextWithShadow(mc.textRenderer, manaLabel, 6, manaY, 0xFFFFFF);
			int manaBarY = manaY + 11;
			context.fill(barX - 1, manaBarY - 1, barX + barW + 1, manaBarY + barH + 1, 0xFF000000);
			context.fill(barX, manaBarY, barX + barW, manaBarY + barH, 0xFF202030);
			int manaFilled = (int) (barW * ClientProgress.manaFraction());
			if (manaFilled > 0) {
				context.fill(barX, manaBarY, barX + manaFilled, manaBarY + barH, 0xFF3B7BE0);
			}
		}

		String keyName = openKey != null ? openKey.getBoundKeyLocalizedText().getString() : "?";
		Text line;
		if (TdClientStatus.active()) {
			int coins = TdClientStatus.coins();
			int wave = TdClientStatus.wave();
			int enemies = TdClientStatus.enemiesVisible();
			line = Text.literal("[" + keyName + "] TD menu").formatted(Formatting.GOLD)
				.append(Text.literal("  ·  Coins ").formatted(Formatting.GRAY))
				.append(Text.literal(Integer.toString(coins)).formatted(Formatting.AQUA))
				.append(Text.literal("  ·  Wave ").formatted(Formatting.GRAY))
				.append(Text.literal(wave < 0 ? "-" : Integer.toString(wave)).formatted(Formatting.YELLOW))
				.append(Text.literal("  ·  Enemies ").formatted(Formatting.GRAY))
				.append(Text.literal(Integer.toString(enemies)).formatted(Formatting.RED));
		} else {
			line = Text.literal("Tower Defense").formatted(Formatting.GOLD, Formatting.BOLD)
				.append(Text.literal(" — press ").formatted(Formatting.GRAY))
				.append(Text.literal("[" + keyName + "]").formatted(Formatting.YELLOW))
				.append(Text.literal(" to open the menu").formatted(Formatting.GRAY));
		}

		int width = mc.getWindow().getScaledWidth();
		int height = mc.getWindow().getScaledHeight();
		int textWidth = mc.textRenderer.getWidth(line);
		int x = (width - textWidth) / 2;
		// Sit just above the hotbar (hotbar occupies ~bottom 22px).
		int y = height - 34;
		context.drawTextWithShadow(mc.textRenderer, line, x, y, 0xFFFFFF);
	}

	/** The active class id capitalized for the HUD (e.g. {@code "mage"} → {@code "Mage"}). */
	private static String classDisplay() {
		String id = ClientProgress.activeClass();
		if (id == null || id.isEmpty()) {
			return "";
		}
		return Character.toUpperCase(id.charAt(0)) + id.substring(1);
	}
}
