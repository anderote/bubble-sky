package net.bubblesky.towerdefense.client.screen;

import net.bubblesky.towerdefense.client.ClientProgress;
import net.bubblesky.towerdefense.progression.PlayerProgress.Stat;
import net.bubblesky.towerdefense.progression.net.AllocatePointPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The client "Character" screen (opened with {@code P}). Shows the player's level, an
 * XP progress bar, the unspent skill-point count, and one {@code +} button per
 * {@link Stat} that spends a point via an {@link AllocatePointPayload}. Each button is
 * disabled when no points remain.
 *
 * <p>Purely a front-end over the cached {@link ClientProgress} snapshot: pressing
 * {@code +} sends the C2S packet and the server (authoritative) validates, allocates,
 * and resyncs — the screen reflects the new snapshot on the next render. Point counts
 * and button-enabled state are re-read every frame in {@link #render}, so a server
 * resync updates the UI live without reopening.
 */
public class CharacterScreen extends Screen {

	private static final int ROW_H = 22;
	private static final int PLUS_W = 22;

	/** Per-stat "+" buttons, in {@link Stat} order, so render can toggle their state. */
	private final ButtonWidget[] plusButtons = new ButtonWidget[Stat.values().length];

	private int contentTop;
	private int labelX;
	private int plusX;

	public CharacterScreen() {
		super(Text.literal("Character"));
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int panelW = 240;
		labelX = cx - panelW / 2;
		plusX = cx + panelW / 2 - PLUS_W;
		// Leave room for title + level + XP bar + points line.
		contentTop = 84;

		Stat[] stats = Stat.values();
		for (int i = 0; i < stats.length; i++) {
			Stat stat = stats[i];
			int y = contentTop + i * ROW_H;
			ButtonWidget plus = ButtonWidget.builder(Text.literal("+"), b -> allocate(stat))
				.dimensions(plusX, y, PLUS_W, 20)
				.build();
			plusButtons[i] = plus;
			this.addDrawableChild(plus);
		}

		int closeY = contentTop + stats.length * ROW_H + 12;
		closeY = Math.min(closeY, this.height - 28);
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> this.close())
			.dimensions(cx - 50, closeY, 100, 20)
			.build());
	}

	/** Send a spend-a-point request for a stat (server validates; ignored if no points). */
	private void allocate(Stat stat) {
		if (ClientProgress.unspentPoints() <= 0) {
			return;
		}
		ClientPlayNetworking.send(new AllocatePointPayload(stat));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int cx = this.width / 2;

		// ---- header: title, level, XP bar, unspent points ----------------
		context.drawCenteredTextWithShadow(this.textRenderer,
			Text.literal("Character").formatted(Formatting.GOLD, Formatting.BOLD), cx, 20, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer,
			Text.literal("Level " + ClientProgress.level()).formatted(Formatting.YELLOW), cx, 36, 0xFFFFFFFF);

		// XP bar.
		int barW = 200;
		int barX = cx - barW / 2;
		int barY = 50;
		int barH = 8;
		context.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF000000);
		context.fill(barX, barY, barX + barW, barY + barH, 0xFF303030);
		int filled = (int) (barW * ClientProgress.xpFraction());
		if (filled > 0) {
			context.fill(barX, barY, barX + filled, barY + barH, 0xFF39C339);
		}
		context.drawCenteredTextWithShadow(this.textRenderer,
			Text.literal(ClientProgress.xp() + " / " + ClientProgress.xpForNextLevel() + " XP")
				.formatted(Formatting.GRAY), cx, barY + barH + 3, 0xFFFFFFFF);

		int points = ClientProgress.unspentPoints();
		Text pointsLine = Text.literal("Skill points: ")
			.formatted(Formatting.WHITE)
			.append(Text.literal(Integer.toString(points))
				.formatted(points > 0 ? Formatting.GREEN : Formatting.DARK_GRAY));
		context.drawCenteredTextWithShadow(this.textRenderer, pointsLine, cx, 70, 0xFFFFFFFF);

		// ---- stat rows ----------------------------------------------------
		Stat[] stats = Stat.values();
		boolean hasPoints = points > 0;
		for (int i = 0; i < stats.length; i++) {
			Stat stat = stats[i];
			int y = contentTop + i * ROW_H;
			int textY = y + 6;
			int pts = ClientProgress.points(stat);
			Text label = Text.literal(statName(stat)).formatted(Formatting.WHITE)
				.append(Text.literal("  (" + pts + ")").formatted(Formatting.AQUA))
				.append(Text.literal("  " + appliedBonus(stat, pts)).formatted(Formatting.DARK_GRAY));
			context.drawTextWithShadow(this.textRenderer, label, labelX, textY, 0xFFFFFFFF);
			if (plusButtons[i] != null) {
				plusButtons[i].active = hasPoints;
			}
		}
	}

	/** Display name for a stat. */
	private static String statName(Stat stat) {
		return switch (stat) {
			case VITALITY -> "Vitality";
			case STRENGTH -> "Strength";
			case AGILITY -> "Agility";
			case MARKSMANSHIP -> "Marksmanship";
			case FORTUNE -> "Fortune";
			case INTELLIGENCE -> "Intelligence";
			case RESILIENCE -> "Resilience";
		};
	}

	/** The player's CURRENT total bonus from a stat, given points spent. */
	private static String appliedBonus(Stat stat, int points) {
		return switch (stat) {
			case VITALITY -> "+" + (points * 4) + " HP";
			case STRENGTH -> "+" + String.format("%.1f", points * 1.0) + " melee";
			case AGILITY -> "+" + (points * 3) + "% speed";
			case MARKSMANSHIP -> "+" + (points * 10) + "% ranged dmg";
			case FORTUNE -> "+" + (points * 12) + "% coins";
			case INTELLIGENCE -> "+" + (points * 8) + "% XP";
			case RESILIENCE -> "+" + points + " armor";
		};
	}

	@Override
	public boolean shouldPause() {
		// Don't pause: this is (usually) a live multiplayer match.
		return false;
	}
}
