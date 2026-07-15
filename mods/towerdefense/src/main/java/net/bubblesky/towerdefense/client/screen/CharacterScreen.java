package net.bubblesky.towerdefense.client.screen;

import java.util.List;
import net.bubblesky.towerdefense.client.ClientProgress;
import net.bubblesky.towerdefense.progression.ClassSkillTree;
import net.bubblesky.towerdefense.progression.PlayerClass;
import net.bubblesky.towerdefense.progression.PlayerProgress.Stat;
import net.bubblesky.towerdefense.progression.net.AllocateClassPointPayload;
import net.bubblesky.towerdefense.progression.net.AllocatePointPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The client "Character" screen (opened with {@code P}), now a TWO-TAB view:
 *
 * <ul>
 *   <li><b>Stats</b> — the original global 7-stat panel: level, XP bar, unspent skill points,
 *       and a {@code +} per {@link Stat} that spends a GLOBAL point via
 *       {@link AllocatePointPayload}. Unchanged in behavior.</li>
 *   <li><b>Skills</b> — the active class's Diablo-2-style tree from {@link ClassSkillTree},
 *       grouped by tier. Each row shows the skill's name, {@code rank X/max}, its
 *       ACTIVE/PASSIVE kind, a one-line effect description, and a {@code +} that spends a
 *       CLASS point via {@link AllocateClassPointPayload}. The {@code +} enables only when the
 *       client mirror of the server rules holds (unspent class points &gt; 0, rank below max,
 *       and class level meets the tier gate). With no active class the tab shows a pick-a-class
 *       note.</li>
 * </ul>
 *
 * <p>Purely a front-end over the cached {@link ClientProgress} snapshot: pressing {@code +}
 * sends the C2S packet and the server (authoritative) validates, allocates, and resyncs — the
 * screen reflects the new snapshot on the next render. Counts and button-enabled state are
 * re-read every frame in {@link #render}, so a server resync updates the UI live.
 */
public class CharacterScreen extends Screen {

	private static final int ROW_H = 22;
	private static final int PLUS_W = 22;
	/** Height of one Skills-tab skill row (name line + description line). */
	private static final int SKILL_ROW_H = 26;
	/** Vertical space reserved above the first skill of each tier for the tier header. */
	private static final int TIER_HEADER_H = 13;

	/** Which tab is showing: {@code 0} = Stats, {@code 1} = Skills. */
	private int tab = 0;

	// ---- Stats tab widgets -------------------------------------------------
	/** Per-stat "+" buttons, in {@link Stat} order, so render can toggle their state. */
	private final ButtonWidget[] plusButtons = new ButtonWidget[Stat.values().length];

	// ---- Skills tab widgets ------------------------------------------------
	/** The active class resolved from the synced id (null when unclassed). */
	private PlayerClass activeClass;
	/** The active class's tree (empty when unclassed), parallel to {@link #skillPlus}/{@link #skillRowY}. */
	private List<ClassSkillTree.Skill> skillList = List.of();
	/** Per-skill "+" buttons (parallel to {@link #skillList}). */
	private ButtonWidget[] skillPlus = new ButtonWidget[0];
	/** The Y of each skill row (parallel to {@link #skillList}). */
	private int[] skillRowY = new int[0];

	// ---- shared layout -----------------------------------------------------
	private int contentTop;
	private int labelX;
	private int plusX;

	public CharacterScreen() {
		super(Text.literal("Character"));
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int panelW = 260;
		labelX = cx - panelW / 2;
		plusX = cx + panelW / 2 - PLUS_W;

		// ---- tab toggle (always visible) ----------------------------------
		int tabY = 28;
		int tabW = 74;
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Stats"), b -> switchTab(0))
			.dimensions(cx - tabW - 2, tabY, tabW, 18).build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Skills"), b -> switchTab(1))
			.dimensions(cx + 2, tabY, tabW, 18).build());

		// ---- Stats tab: 7-stat rows --------------------------------------
		contentTop = 100;
		Stat[] stats = Stat.values();
		for (int i = 0; i < stats.length; i++) {
			Stat stat = stats[i];
			int y = contentTop + i * ROW_H;
			ButtonWidget plus = ButtonWidget.builder(Text.literal("+"), b -> allocateStat(stat))
				.dimensions(plusX, y, PLUS_W, 20)
				.build();
			plusButtons[i] = plus;
			this.addDrawableChild(plus);
		}

		// ---- Skills tab: the active class's tree -------------------------
		activeClass = PlayerClass.fromId(ClientProgress.activeClass());
		skillList = ClassSkillTree.skills(activeClass);
		skillPlus = new ButtonWidget[skillList.size()];
		skillRowY = new int[skillList.size()];
		int skillTop = 90;
		int y = skillTop;
		int lastTier = -1;
		for (int i = 0; i < skillList.size(); i++) {
			ClassSkillTree.Skill skill = skillList.get(i);
			if (skill.tier() != lastTier) {
				y += TIER_HEADER_H;
				lastTier = skill.tier();
			}
			skillRowY[i] = y;
			ButtonWidget plus = ButtonWidget.builder(Text.literal("+"), b -> allocateSkill(skill.id()))
				.dimensions(plusX, y + 1, PLUS_W, 18)
				.build();
			skillPlus[i] = plus;
			this.addDrawableChild(plus);
			y += SKILL_ROW_H;
		}

		// Pin Close to the bottom so it never collides with the (taller) Skills-tab rows.
		int closeY = this.height - 26;
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> this.close())
			.dimensions(cx - 50, closeY, 100, 20)
			.build());

		updateTabVisibility();
	}

	/** Flip to a tab and refresh which widgets are shown. */
	private void switchTab(int which) {
		this.tab = which;
		updateTabVisibility();
	}

	/** Show only the active tab's "+" buttons (tab + close buttons stay visible via render). */
	private void updateTabVisibility() {
		for (ButtonWidget b : plusButtons) {
			if (b != null) {
				b.visible = tab == 0;
			}
		}
		for (ButtonWidget b : skillPlus) {
			if (b != null) {
				b.visible = tab == 1;
			}
		}
	}

	/** Send a spend-a-point request for a global stat (server validates). */
	private void allocateStat(Stat stat) {
		if (ClientProgress.unspentPoints() <= 0) {
			return;
		}
		ClientPlayNetworking.send(new AllocatePointPayload(stat));
	}

	/** Send a spend-a-class-point request for a skill id (server validates). */
	private void allocateSkill(String skillId) {
		if (ClientProgress.classPoints() <= 0) {
			return;
		}
		ClientPlayNetworking.send(new AllocateClassPointPayload(skillId));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int cx = this.width / 2;

		context.drawCenteredTextWithShadow(this.textRenderer,
			Text.literal("Character").formatted(Formatting.GOLD, Formatting.BOLD), cx, 14, 0xFFFFFFFF);

		// Premium essence balance in the header (purple), legible on both tabs — it funds
		// respec and the /td essence buff sink.
		context.drawCenteredTextWithShadow(this.textRenderer,
			Text.literal("Essence: ").formatted(Formatting.GRAY)
				.append(Text.literal(Integer.toString(ClientProgress.essence())).formatted(Formatting.LIGHT_PURPLE)),
			cx, 28, 0xFFFFFFFF);

		if (tab == 0) {
			renderStatsTab(context, cx);
		} else {
			renderSkillsTab(context, cx);
		}
	}

	// ---- Stats tab ---------------------------------------------------------
	private void renderStatsTab(DrawContext context, int cx) {
		context.drawCenteredTextWithShadow(this.textRenderer,
			Text.literal("Level " + ClientProgress.level()).formatted(Formatting.YELLOW), cx, 52, 0xFFFFFFFF);

		// XP bar.
		int barW = 200;
		int barX = cx - barW / 2;
		int barY = 66;
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
		context.drawCenteredTextWithShadow(this.textRenderer, pointsLine, cx, 86, 0xFFFFFFFF);

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

	// ---- Skills tab --------------------------------------------------------
	private void renderSkillsTab(DrawContext context, int cx) {
		if (activeClass == null) {
			context.drawCenteredTextWithShadow(this.textRenderer,
				Text.literal("No class selected.").formatted(Formatting.GRAY), cx, 70, 0xFFFFFFFF);
			context.drawCenteredTextWithShadow(this.textRenderer,
				Text.literal("Pick one with /td class <mage|ranger|engineer|necromancer>.")
					.formatted(Formatting.DARK_GRAY), cx, 86, 0xFFFFFFFF);
			return;
		}

		int classLevel = ClientProgress.classLevel();
		int classPoints = ClientProgress.classPoints();
		context.drawCenteredTextWithShadow(this.textRenderer,
			Text.literal(activeClass.displayName() + " — Level " + classLevel)
				.formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD), cx, 50, 0xFFFFFFFF);
		Text ptsLine = Text.literal("Class points: ")
			.formatted(Formatting.WHITE)
			.append(Text.literal(Integer.toString(classPoints))
				.formatted(classPoints > 0 ? Formatting.GREEN : Formatting.DARK_GRAY));
		context.drawCenteredTextWithShadow(this.textRenderer, ptsLine, cx, 66, 0xFFFFFFFF);

		int lastTier = -1;
		for (int i = 0; i < skillList.size(); i++) {
			ClassSkillTree.Skill skill = skillList.get(i);
			int rowY = skillRowY[i];
			int gate = ClassSkillTree.levelGate(skill.tier());

			// Tier header (once, above the first skill in each tier).
			if (skill.tier() != lastTier) {
				lastTier = skill.tier();
				Formatting tierColor = classLevel >= gate ? Formatting.GOLD : Formatting.DARK_GRAY;
				context.drawTextWithShadow(this.textRenderer,
					Text.literal("Tier " + skill.tier() + "  (unlocks at Lv " + gate + ")")
						.formatted(tierColor),
					labelX, rowY - TIER_HEADER_H + 2, 0xFFFFFFFF);
			}

			int rank = ClientProgress.classRank(skill.id());
			boolean unlocked = classLevel >= gate;
			boolean maxed = rank >= skill.maxRank();
			boolean canSpend = classPoints > 0 && !maxed && unlocked;

			Formatting kindColor = skill.isPassive() ? Formatting.BLUE : Formatting.AQUA;
			Formatting nameColor = unlocked ? Formatting.WHITE : Formatting.DARK_GRAY;
			Text name = Text.literal(skill.displayName()).formatted(nameColor)
				.append(Text.literal("  " + rank + "/" + skill.maxRank())
					.formatted(maxed ? Formatting.GOLD : Formatting.GREEN))
				.append(Text.literal("  [" + (skill.isPassive() ? "PASSIVE" : "ACTIVE") + "]")
					.formatted(kindColor));
			context.drawTextWithShadow(this.textRenderer, name, labelX, rowY + 2, 0xFFFFFFFF);
			context.drawTextWithShadow(this.textRenderer,
				Text.literal(skill.description()).formatted(Formatting.DARK_GRAY),
				labelX, rowY + 13, 0xFFFFFFFF);

			if (skillPlus[i] != null) {
				skillPlus[i].active = canSpend;
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
