package net.bubblesky.towerdefense.client;

import java.util.List;
import net.bubblesky.towerdefense.construction.ConstructionConfig;
import net.bubblesky.towerdefense.construction.ConstructionGeometry;
import net.bubblesky.towerdefense.construction.net.ConfirmConstructionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Client-only movable ghost. No fake blocks are inserted into the client world: translucent boxes
 * are rendered from the same pure geometry the server uses, so walking or turning immediately
 * relocates the preview and confirmation cannot leave glass behind.
 */
public final class ClientConstructionPreview {
	private ClientConstructionPreview() {
	}

	private static ConstructionConfig config = ConstructionConfig.defaults(ConstructionConfig.Type.BRIDGE);
	private static boolean active;
	private static KeyBinding menuKey;
	private static KeyBinding confirmKey;
	private static KeyBinding cancelKey;

	public static void register(KeyBinding menu, KeyBinding confirm, KeyBinding cancel) {
		menuKey = menu;
		confirmKey = confirm;
		cancelKey = cancel;
		WorldRenderEvents.AFTER_ENTITIES.register(ClientConstructionPreview::renderWorld);
		HudRenderCallback.EVENT.register(ClientConstructionPreview::renderHud);
	}

	public static ConstructionConfig config() {
		return config;
	}

	public static void setConfig(ConstructionConfig value) {
		config = value.normalized();
	}

	public static boolean active() {
		return active;
	}

	public static void activate(ConstructionConfig value) {
		setConfig(value);
		active = true;
	}

	public static void cancel() {
		active = false;
	}

	public static void confirm() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (!active || mc.player == null || mc.getNetworkHandler() == null) {
			return;
		}
		ClientPlayNetworking.send(new ConfirmConstructionPayload(config));
		mc.player.sendMessage(Text.literal("Build request sent: " + config.type().display()
			+ ". The server will report changed blocks here.").formatted(Formatting.AQUA), false);
		active = false;
	}

	private static void renderWorld(net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext context) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (!active || mc.player == null || mc.world == null) {
			return;
		}
		MatrixStack matrices = context.matrixStack();
		VertexConsumerProvider consumers = context.consumers();
		if (matrices == null || consumers == null) {
			return;
		}
		Vec3d camera = context.camera().getPos();
		List<ConstructionGeometry.PreviewBox> previews =
			ConstructionGeometry.previewBoxes(mc.player.getBlockPos(), mc.player.getHorizontalFacing(), config);

		// Immediate providers close their current non-shared layer when another layer is requested.
		// Finish every translucent fill before asking for the line layer, otherwise the fill consumer
		// points at a closed BufferBuilder and crashes with "Not building!".
		VertexConsumer fill = consumers.getBuffer(RenderLayer.getDebugFilledBox());
		for (ConstructionGeometry.PreviewBox preview : previews) {
			Box box = preview.box().offset(-camera.x, -camera.y, -camera.z);
			float red = ((preview.color() >> 16) & 0xFF) / 255.0f;
			float green = ((preview.color() >> 8) & 0xFF) / 255.0f;
			float blue = (preview.color() & 0xFF) / 255.0f;
			VertexRendering.drawFilledBox(matrices, fill,
				box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
				red, green, blue, preview.alpha());
		}

		VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());
		for (ConstructionGeometry.PreviewBox preview : previews) {
			Box box = preview.box().offset(-camera.x, -camera.y, -camera.z);
			float red = ((preview.color() >> 16) & 0xFF) / 255.0f;
			float green = ((preview.color() >> 8) & 0xFF) / 255.0f;
			float blue = (preview.color() & 0xFF) / 255.0f;
			VertexRendering.drawBox(matrices, lines, box.expand(0.003), red, green, blue, 0.82f);
		}
		for (Box guide : ConstructionGeometry.previewGuideBoxes(
				mc.player.getBlockPos(), mc.player.getHorizontalFacing(), config)) {
			Box box = guide.offset(-camera.x, -camera.y, -camera.z).expand(0.004);
			VertexRendering.drawBox(matrices, lines, box, 0.78f, 0.94f, 1.0f, 0.42f);
		}
	}

	private static void renderHud(DrawContext context, Object tickCounter) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (!active || mc.player == null || mc.currentScreen != null || mc.options.hudHidden) {
			return;
		}
		String menu = menuKey == null ? "B" : menuKey.getBoundKeyLocalizedText().getString();
		String confirm = confirmKey == null ? "ENTER" : confirmKey.getBoundKeyLocalizedText().getString();
		String cancel = cancelKey == null ? "BACKSPACE" : cancelKey.getBoundKeyLocalizedText().getString();
		ConstructionConfig c = config;
		Text title = Text.literal("Preview: " + c.type().display() + "  ")
			.formatted(Formatting.AQUA, Formatting.BOLD)
			.append(Text.literal(summary(c)).formatted(Formatting.WHITE));
		Text controls = Text.literal("Move/turn to position  ·  [" + confirm + "] Build  ·  [" + menu
			+ "] Edit  ·  [" + cancel + "] Cancel").formatted(Formatting.YELLOW);
		int center = mc.getWindow().getScaledWidth() / 2;
		int y = mc.getWindow().getScaledHeight() - 58;
		context.drawCenteredTextWithShadow(mc.textRenderer, title, center, y, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(mc.textRenderer, controls, center, y + 12, 0xFFFFFFFF);
	}

	private static String summary(ConstructionConfig c) {
		return switch (c.type()) {
			case BRIDGE -> c.width() + "w × " + c.length() + "l × " + c.thickness() + "t";
			case WALL -> c.length() + "l × " + c.height() + "h × " + c.thickness() + "t";
			case FLATTEN -> c.width() + " × " + c.length() + " at Y"
				+ (c.verticalOffset() >= 0 ? "+" : "") + c.verticalOffset();
			case PLATFORM -> c.width() + "w × " + c.length() + "d × " + c.thickness() + "t";
			case STAIRS -> c.width() + "w × " + c.height() + " rise + " + c.auxiliary() + " landing";
			case LANE -> c.width() + " gap × " + c.length() + "l × " + c.height() + "h";
		};
	}
}
