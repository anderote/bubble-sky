package net.bubblesky.towerdefense.client.render;

import net.bubblesky.towerdefense.entity.TdFriendlyEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.util.Identifier;

/** Shared humanoid renderer for hired infantry. */
public class TdFriendlyEntityRenderer extends BipedEntityRenderer<TdFriendlyEntity,
		BipedEntityRenderState, BipedEntityModel<BipedEntityRenderState>> {
	private final Identifier texture;

	public TdFriendlyEntityRenderer(EntityRendererFactory.Context context, Identifier texture) {
		super(context, new BipedEntityModel<>(context.getPart(EntityModelLayers.PLAYER)), 0.5f);
		this.texture = texture;
	}

	@Override public BipedEntityRenderState createRenderState() { return new BipedEntityRenderState(); }
	@Override public Identifier getTexture(BipedEntityRenderState state) { return texture; }
}
