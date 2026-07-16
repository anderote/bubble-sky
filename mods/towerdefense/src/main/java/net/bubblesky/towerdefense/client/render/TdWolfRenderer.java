package net.bubblesky.towerdefense.client.render;

import net.bubblesky.towerdefense.entity.TdEnemyEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.WolfEntityModel;
import net.minecraft.client.render.entity.state.WolfEntityRenderState;
import net.minecraft.util.Identifier;

/**
 * Renderer for the Direwolf beast: reuses the vanilla {@link WolfEntityModel} (on the
 * built-in {@code WOLF} model layer) so the pack rusher reads as a four-legged wolf rather
 * than a humanoid, skinned with a per-enemy texture.
 *
 * <p>Because our {@link TdEnemyEntity} is not a vanilla {@code WolfEntity}, we cannot bind
 * the vanilla {@code WolfEntityRenderer} directly (its render type is fixed to
 * {@code WolfEntity}); instead this thin {@link MobEntityRenderer} pairs the wolf model with
 * a plain {@link WolfEntityRenderState}. That state's fields all carry sensible defaults
 * (upright pose, dry fur, neutral tail), and the base renderer still copies the generic
 * walk/limb animation into it — so the Direwolf trots on all fours with no wolf-specific
 * state wiring needed.
 */
public class TdWolfRenderer
		extends MobEntityRenderer<TdEnemyEntity, WolfEntityRenderState, WolfEntityModel> {
	private final Identifier texture;

	public TdWolfRenderer(EntityRendererFactory.Context ctx, Identifier texture) {
		super(ctx, new WolfEntityModel(ctx.getPart(EntityModelLayers.WOLF)), 0.5f);
		this.texture = texture;
	}

	@Override
	public WolfEntityRenderState createRenderState() {
		return new WolfEntityRenderState();
	}

	@Override
	public Identifier getTexture(WolfEntityRenderState state) {
		return texture;
	}
}
