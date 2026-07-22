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
	/** Pixels the Skills tab scrolls per mouse-wheel notch. */
	private static final int SKILLS_SCROLL_STEP = 12;

	/** Which tab is showing: {@code 0} = Stats, {@code 1} = Skills. */
	private int tab = 0;

	// ---- Stats tab widgets -------------------------------------------------
	/** Per-stat "+" buttons, in {@link Stat} order, so render can toggle their state. */
	private final ButtonWidget[] plusButtons = new ButtonWidget[Stat.values().length];
	/** Vertical scroll offset (px) of the Stats-tab rows. */
	private int statsScroll = 0;
	/** Total pixel height of all global-stat rows. */
	private int statsContentHeight = 0;
	/** Top of the scrollable global-stat list; its bottom is shared with the Skills panel. */
	private int statsPanelTop;

	// ---- Skills tab widgets ------------------------------------------------
	/** The active class resolved from the synced id (null when unclassed). */
	private PlayerClass activeClass;
	/** The active class's tree (empty when unclassed), parallel to {@link #skillPlus}/{@link #skillRowY}. */
	private List<ClassSkillTree.Skill> skillList = List.of();
	/** Per-skill "+" buttons (parallel to {@link #skillList}). */
	private ButtonWidget[] skillPlus = new ButtonWidget[0];
	/**
	 * The Y of each skill row RELATIVE TO the scrollable panel's top (content space), parallel
	 * to {@link #skillList}. Render adds {@link #panelTop} and subtracts {@link #skillsScroll}
	 * to map a row into screen space.
	 */
	private int[] skillRowY = new int[0];
	/** Vertical scroll offset (px) of the Skills-tab content; clamped to {@code [0, maxScroll]}. */
	private int skillsScroll = 0;
	/** Total pixel height of the Skills-tab content (all tiers + rows), computed in {@link #init}. */
	private int skillsContentHeight = 0;
	/** Left/right/top/bottom of the scrollable Skills panel rectangle, computed in {@link #init}. */
	private int panelX1;
	private int panelX2;
	private int panelTop;
	private int panelBottom;
	/** The X of the Skills-tab "+" buttons (sits just left of the right-edge scrollbar gutter). */
	private int skillPlusX;

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
		statsPanelTop = contentTop;
		statsScroll = 0;
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
		statsContentHeight = stats.length * ROW_H;

		// ---- Skills tab: the active class's tree -------------------------
		// The tree can overflow the screen (six 20-rank skills across three tiers), so it lives
		// in a SCROLLABLE panel. Its rectangle spans the same width as the rows, from just below
		// the class-points header down to just above the pinned Close button. skillRowY[i] is
		// stored in CONTENT space (relative to panelTop); render maps it to the screen by adding
		// panelTop and subtracting skillsScroll, then clips to the panel with a scissor.
		panelX1 = labelX;
		panelX2 = plusX + PLUS_W;
		panelTop = 80;
		panelBottom = this.height - 30;
		skillPlusX = plusX - 6; // leave a 2px gutter for the scrollbar on the panel's right edge

		activeClass = PlayerClass.fromId(ClientProgress.activeClass());
		skillList = ClassSkillTree.skills(activeClass);
		skillPlus = new ButtonWidget[skillList.size()];
		skillRowY = new int[skillList.size()];
		skillsScroll = 0;
		int y = 0;
		int lastTier = -1;
		for (int i = 0; i < skillList.size(); i++) {
			ClassSkillTree.Skill skill = skillList.get(i);
			if (skill.tier() != lastTier) {
				y += TIER_HEADER_H;
				lastTier = skill.tier();
			}
			skillRowY[i] = y;
			ButtonWidget plus = ButtonWidget.builder(Text.literal("+"), b -> allocateSkill(skill.id()))
				.dimensions(skillPlusX, panelTop + y + 1, PLUS_W, 18)
				.build();
			skillPlus[i] = plus;
			this.addDrawableChild(plus);
			y += SKILL_ROW_H;
		}
		skillsContentHeight = y; // total content height, for scroll clamping

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

	/**
	 * Show only the active tab's "+" buttons (tab + close buttons stay visible via render). The
	 * Skills-tab "+" buttons are hidden here unconditionally: {@link #renderSkillsTab} re-shows
	 * only the ones that scroll into the panel's visible band each frame, so an off-panel button
	 * never draws (vanilla draws buttons without our scissor) nor keeps a stray click target.
	 */
	private void updateTabVisibility() {
		// Both sets start hidden. The active tab's renderer exposes only buttons whose complete
		// hitbox is inside its clipped scrolling panel.
		for (ButtonWidget b : plusButtons) {
			if (b != null) b.visible = false;
		}
		for (ButtonWidget b : skillPlus) {
			if (b != null) {
				b.visible = false;
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

	/**
	 * Scroll the Skills tab when the wheel turns over its panel. Only handles the event (and
	 * consumes it) while the Skills tab is active, a class is selected, the cursor is inside the
	 * panel rectangle, and the content actually overflows; otherwise it defers to {@code super}
	 * so the Stats tab and everything else behave exactly as before.
	 */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (tab == 0
			&& mouseX >= panelX1 && mouseX <= panelX2
			&& mouseY >= statsPanelTop && mouseY <= panelBottom) {
			int maxScroll = Math.max(0, statsContentHeight - (panelBottom - statsPanelTop));
			if (maxScroll > 0) {
				statsScroll = clamp(statsScroll - (int) (verticalAmount * SKILLS_SCROLL_STEP), 0, maxScroll);
				return true;
			}
		}
		if (tab == 1 && activeClass != null
			&& mouseX >= panelX1 && mouseX <= panelX2
			&& mouseY >= panelTop && mouseY <= panelBottom) {
			int maxScroll = Math.max(0, skillsContentHeight - (panelBottom - panelTop));
			if (maxScroll > 0) {
				// verticalAmount is +1 per notch scrolling UP (toward earlier rows), so subtract.
				skillsScroll = clamp(skillsScroll - (int) (verticalAmount * SKILLS_SCROLL_STEP), 0, maxScroll);
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	/** Clamp {@code v} into the inclusive range {@code [min, max]}. */
	private static int clamp(int v, int min, int max) {
		return v < min ? min : Math.min(v, max);
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
		int visibleHeight = panelBottom - statsPanelTop;
		int maxScroll = Math.max(0, statsContentHeight - visibleHeight);
		statsScroll = clamp(statsScroll, 0, maxScroll);

		context.enableScissor(panelX1, statsPanelTop, panelX2, panelBottom);
		for (int i = 0; i < stats.length; i++) {
			Stat stat = stats[i];
			int y = statsPanelTop + i * ROW_H - statsScroll;
			int textY = y + 6;
			int pts = ClientProgress.points(stat);
			Text label = Text.literal(statName(stat)).formatted(Formatting.WHITE)
				.append(Text.literal("  (" + pts + ")").formatted(Formatting.AQUA))
				.append(Text.literal("  " + appliedBonus(stat, pts)).formatted(Formatting.DARK_GRAY));
			context.drawTextWithShadow(this.textRenderer, label, labelX, textY, 0xFFFFFFFF);
			if (plusButtons[i] != null) {
				boolean inBand = y >= statsPanelTop && y + 20 <= panelBottom;
				plusButtons[i].setY(y);
				plusButtons[i].visible = inBand;
				plusButtons[i].active = inBand && hasPoints;
			}
		}
		context.disableScissor();

		if (maxScroll > 0) {
			drawScrollbar(context, statsPanelTop, panelBottom, statsContentHeight, statsScroll, maxScroll);
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

		// ---- scrollable skill list --------------------------------------
		int visibleHeight = panelBottom - panelTop;
		int maxScroll = Math.max(0, skillsContentHeight - visibleHeight);
		// Re-clamp defensively: content height / screen size may change between frames.
		skillsScroll = clamp(skillsScroll, 0, maxScroll);

		// Clip the rows to the panel so a partially-scrolled row is cut cleanly at the edges and
		// nothing spills over the header above or the tabs/Close button beyond the panel.
		context.enableScissor(panelX1, panelTop, panelX2, panelBottom);
		int lastTier = -1;
		for (int i = 0; i < skillList.size(); i++) {
			ClassSkillTree.Skill skill = skillList.get(i);
			int rowY = panelTop + skillRowY[i] - skillsScroll; // content space -> screen space
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

			// Track the "+" to its scrolled spot, and only show/enable it when the WHOLE button
			// lands inside the panel band — vanilla draws buttons without our scissor, so an
			// out-of-band button would both draw outside the panel and leave a stray click target.
			if (skillPlus[i] != null) {
				int btnY = rowY + 1;
				boolean inBand = btnY >= panelTop && btnY + 18 <= panelBottom;
				skillPlus[i].setY(btnY);
				skillPlus[i].visible = inBand;
				skillPlus[i].active = inBand && canSpend;
			}
		}
		context.disableScissor();

		// ---- scrollbar (only when the content overflows the panel) ------
		if (maxScroll > 0) {
			drawScrollbar(context, panelTop, panelBottom, skillsContentHeight, skillsScroll, maxScroll);
		}
	}

	/** Draw the shared four-pixel scrollbar used by both Character tabs. */
	private void drawScrollbar(DrawContext context, int top, int bottom,
			int contentHeight, int scroll, int maxScroll) {
		int visibleHeight = bottom - top;
		int sbW = 4;
		int sbX = panelX2 - sbW;
		context.fill(sbX, top, sbX + sbW, bottom, 0xFF303030);
		int thumbH = Math.max(20, visibleHeight * visibleHeight / contentHeight);
		int thumbY = top + (int) ((long) scroll * (visibleHeight - thumbH) / maxScroll);
		context.fill(sbX, thumbY, sbX + sbW, thumbY + thumbH, 0xFFAAAAAA);
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
