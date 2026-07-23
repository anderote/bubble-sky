package net.bubblesky.towerdefense.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import net.bubblesky.towerdefense.client.ClientConstructionPreview;
import net.bubblesky.towerdefense.construction.ConstructionConfig;
import net.bubblesky.towerdefense.construction.net.UndoConstructionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Fast visual editor for the non-LLM construction spells. */
public final class ConstructionScreen extends Screen {
	private static final int ROW = 20;
	private ConstructionConfig config;
	private final List<FieldLabel> fieldLabels = new ArrayList<>();

	public ConstructionScreen() {
		super(Text.literal("Build Spells"));
		this.config = ClientConstructionPreview.config();
	}

	@Override
	protected void init() {
		fieldLabels.clear();
		int cx = width / 2;
		int panelW = Math.min(390, width - 12);
		int left = cx - panelW / 2;
		int presetW = (panelW - 12) / 3;

		addDrawableChild(ButtonWidget.builder(Text.literal("2×2×30 Bridge"), b -> preset(ConstructionConfig.Type.BRIDGE))
			.dimensions(left, 28, presetW, 18).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("4×30 Wall"), b -> preset(ConstructionConfig.Type.WALL))
			.dimensions(left + presetW + 6, 28, presetW, 18).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("50×50 Flatten"), b -> preset(ConstructionConfig.Type.FLATTEN))
			.dimensions(left + (presetW + 6) * 2, 28, presetW, 18).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("7×7 Tower Pad"), b -> preset(ConstructionConfig.Type.PLATFORM))
			.dimensions(left, 48, presetW, 18).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("16-high Stairs"), b -> preset(ConstructionConfig.Type.STAIRS))
			.dimensions(left + presetW + 6, 48, presetW, 18).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("4×24 Kill Lane"), b -> preset(ConstructionConfig.Type.LANE))
			.dimensions(left + (presetW + 6) * 2, 48, presetW, 18).build());

		int colW = (panelW - 8) / 2;
		int right = left + colW + 8;
		int y = 70;
		cycle(left, y, colW, "Spell: " + config.type().display(), c -> c.withType(c.type().next()));
		cycle(right, y, colW, "Material: " + config.material().display(),
			c -> c.withMaterial(c.material().next(c.type())));
		y += ROW;
		numberField(left, y, colW, "Forward offset", config.forwardOffset(), 1, 24,
			value -> config.withForwardOffset(value));
		numberField(right, y, colW, "Raise/lower", config.verticalOffset(), -16, 32,
			value -> config.withVerticalOffset(value));
		y += ROW;
		cycle(left, y, colW, "Replace: " + config.replaceMode().display(),
			c -> c.withReplaceMode(c.replaceMode().next()));
		cycle(right, y, colW, config.consumeMaterials() ? "Cost: inventory blocks" : "Cost: magic / free",
			c -> c.withConsumeMaterials(!c.consumeMaterials()));

		y += ROW + 3;
		switch (config.type()) {
			case BRIDGE -> bridgeRows(left, right, y, colW);
			case WALL -> wallRows(left, right, y, colW);
			case FLATTEN -> flattenRows(left, right, y, colW);
			case PLATFORM -> platformRows(left, right, y, colW);
			case STAIRS -> stairRows(left, right, y, colW);
			case LANE -> laneRows(left, right, y, colW);
		}

		int bottom = height - 49;
		int actionW = (panelW - 12) / 3;
		addDrawableChild(ButtonWidget.builder(
				Text.literal(ClientConstructionPreview.active() ? "Update Preview" : "Preview → Enter to Build"),
				b -> {
					ClientConstructionPreview.activate(config);
					close();
				}).dimensions(left, bottom, actionW, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Cancel Preview"), b -> {
				ClientConstructionPreview.cancel();
				close();
			}).dimensions(left + actionW + 6, bottom, actionW, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Undo Last Build"), b -> {
				ClientPlayNetworking.send(new UndoConstructionPayload());
				close();
			}).dimensions(left + (actionW + 6) * 2, bottom, actionW, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
			.dimensions(cx - 50, bottom + 24, 100, 20).build());
	}

	private void bridgeRows(int left, int right, int y, int colW) {
		numberField(left, y, colW, "Width", config.width(), 1, 16, value -> config.withWidth(value));
		numberField(right, y, colW, "Length", config.length(), 1, 128, value -> config.withLength(value));
		y += ROW;
		numberField(left, y, colW, "Deck thickness", config.thickness(), 1, 4,
			value -> config.withThickness(value));
		cycle(right, y, colW, config.decorated() ? "Railings: on" : "Railings: off",
			c -> c.withDecorated(!c.decorated()));
		y += ROW;
		numberField(left, y, colW, "Railing height", config.auxiliary(), 0, 32,
			value -> config.withAuxiliary(value));
	}

	private void wallRows(int left, int right, int y, int colW) {
		numberField(left, y, colW, "Length", config.length(), 1, 128, value -> config.withLength(value));
		numberField(right, y, colW, "Height", config.height(), 1, 32, value -> config.withHeight(value));
		y += ROW;
		numberField(left, y, colW, "Thickness", config.thickness(), 1, 4,
			value -> config.withThickness(value));
		cycle(right, y, colW, config.decorated() ? "Battlements: on" : "Battlements: off",
			c -> c.withDecorated(!c.decorated()));
	}

	private void flattenRows(int left, int right, int y, int colW) {
		numberField(left, y, colW, "Width", config.width(), 1, 64, value -> config.withWidth(value));
		numberField(right, y, colW, "Depth", config.length(), 1, 64, value -> config.withLength(value));
		y += ROW;
		numberField(left, y, colW, "Clear above", config.auxiliary(), 0, 32,
			value -> config.withAuxiliary(value));
		numberField(right, y, colW, "Fill holes down", config.fillDepth(), 0, 16,
			value -> config.withFillDepth(value));
	}

	private void platformRows(int left, int right, int y, int colW) {
		numberField(left, y, colW, "Width", config.width(), 1, 16, value -> config.withWidth(value));
		numberField(right, y, colW, "Depth", config.length(), 1, 128, value -> config.withLength(value));
		y += ROW;
		numberField(left, y, colW, "Thickness", config.thickness(), 1, 4,
			value -> config.withThickness(value));
	}

	private void stairRows(int left, int right, int y, int colW) {
		numberField(left, y, colW, "Width", config.width(), 1, 16, value -> config.withWidth(value));
		numberField(right, y, colW, "Rise / steps", config.height(), 1, 32,
			value -> config.withHeight(value));
		y += ROW;
		numberField(left, y, colW, "Top landing", config.auxiliary(), 0, 32,
			value -> config.withAuxiliary(value));
	}

	private void laneRows(int left, int right, int y, int colW) {
		numberField(left, y, colW, "Lane gap", config.width(), 1, 16, value -> config.withWidth(value));
		numberField(right, y, colW, "Length", config.length(), 1, 128, value -> config.withLength(value));
		y += ROW;
		numberField(left, y, colW, "Wall height", config.height(), 1, 32,
			value -> config.withHeight(value));
		numberField(right, y, colW, "Wall thickness", config.thickness(), 1, 4,
			value -> config.withThickness(value));
	}

	private void preset(ConstructionConfig.Type type) {
		set(ConstructionConfig.defaults(type));
	}

	private void cycle(int x, int y, int width, String label, Consumer<ConstructionConfig> update) {
		addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> update.accept(config))
			.dimensions(x, y, width, 20).build());
	}

	private void numberField(int x, int y, int width, String label, int value, int min, int max,
			IntFunction<ConstructionConfig> update) {
		TextFieldWidget field = new TextFieldWidget(textRenderer, x + width - 72, y + 1, 46, 18,
			Text.literal(label));
		field.setMaxLength(4);
		field.setCentered(true);
		field.setTextPredicate(text -> validNumber(text, min, max));
		field.setText(Integer.toString(value));
		field.setChangedListener(text -> {
			Integer parsed = parseNumber(text);
			if (parsed != null) {
				config = update.apply(parsed);
				ClientConstructionPreview.setConfig(config);
			}
		});
		fieldLabels.add(new FieldLabel(label, x + 28, y + 6));
		addDrawableChild(field);
		addDrawableChild(ButtonWidget.builder(Text.literal("−"), b -> adjust(field, value, -1, update))
			.dimensions(x, y, 22, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> adjust(field, value, 1, update))
			.dimensions(x + width - 22, y, 22, 20).build());
	}

	private void adjust(TextFieldWidget field, int fallback, int amount, IntFunction<ConstructionConfig> update) {
		Integer current = parseNumber(field.getText());
		set(update.apply((current == null ? fallback : current) + amount));
	}

	private static boolean validNumber(String text, int min, int max) {
		if (text.isEmpty() || (min < 0 && text.equals("-"))) {
			return true;
		}
		Integer parsed = parseNumber(text);
		return parsed != null && parsed >= min && parsed <= max;
	}

	private static Integer parseNumber(String text) {
		try {
			return text.isEmpty() || text.equals("-") ? null : Integer.parseInt(text);
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private void set(ConstructionConfig value) {
		config = value.normalized();
		ClientConstructionPreview.setConfig(config);
		clearAndInit();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int cx = width / 2;
		context.drawCenteredTextWithShadow(textRenderer,
			Text.literal("Build Spells").formatted(Formatting.AQUA, Formatting.BOLD), cx, 5, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(textRenderer,
			Text.literal("Pick a preset or tune every option; the world stays unchanged until you confirm.")
				.formatted(Formatting.GRAY), cx, 16, 0xFFFFFFFF);
		for (FieldLabel label : fieldLabels) {
			context.drawTextWithShadow(textRenderer, label.text(), label.x(), label.y(), 0xFFD8D8D8);
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private record FieldLabel(String text, int x, int y) {
	}
}
