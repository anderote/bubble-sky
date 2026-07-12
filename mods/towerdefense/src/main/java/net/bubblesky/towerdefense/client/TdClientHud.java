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
}
