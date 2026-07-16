package net.bubblesky.towerdefense.client.render;

import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/**
 * Renderer for the Juggernaut tank: the same reskinned biped as the rest of the roster
 * (see {@link TdBipedEntityRenderer}) but drawn ~1.4x larger so it visibly TOWERS over the
 * ordinary horde.
 *
 * <p>The enlargement is applied here in the render {@link #scale} hook rather than via the
 * {@code generic.scale} attribute, because the wave manager reserves that attribute for its
 * adaptive-escalation growth (it reassigns it on every spawn). Scaling in the renderer keeps
 * the Juggernaut reliably big regardless of the escalation factor, and an escalated wave
 * still enlarges it further on top of this (the attribute scale composes with this render
 * scale).
 */
public class JuggernautBipedRenderer extends TdBipedEntityRenderer {
	/** How much larger than a normal roster biped the Juggernaut is drawn. */
	private static final float TANK_SCALE = 1.4f;

	public JuggernautBipedRenderer(EntityRendererFactory.Context ctx, Identifier texture) {
		super(ctx, texture);
	}

	@Override
	protected void scale(BipedEntityRenderState state, MatrixStack matrices) {
		super.scale(state, matrices);
		matrices.scale(TANK_SCALE, TANK_SCALE, TANK_SCALE);
	}
}
