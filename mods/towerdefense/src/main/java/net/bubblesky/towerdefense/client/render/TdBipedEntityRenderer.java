package net.bubblesky.towerdefense.client.render;

import net.bubblesky.towerdefense.entity.TdEnemyEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.util.Identifier;

/**
 * Path A renderer for the enemy roster: a plain vanilla biped ({@code humanoid})
 * model on the built-in {@link EntityModelLayers#PLAYER} layer, skinned with a
 * per-enemy 64x64 texture. One instance per {@link EntityType} — the texture is
 * passed in at registration time, so all six roster mobs reuse this one class.
 *
 * <p>1.21.6 render pipeline: extends {@link BipedEntityRenderer} (which supplies
 * {@code updateRenderState} biped pose copying) and only has to provide the two
 * abstract hooks — {@link #createRenderState()} and {@link #getTexture}. Reusing
 * the PLAYER layer means no custom {@code EntityModelLayerRegistry} entry is
 * needed; the model parts are already loaded by the client.
 */
public class TdBipedEntityRenderer
		extends BipedEntityRenderer<TdEnemyEntity, BipedEntityRenderState, BipedEntityModel<BipedEntityRenderState>> {
	private final Identifier texture;

	public TdBipedEntityRenderer(EntityRendererFactory.Context ctx, Identifier texture) {
		super(ctx, new BipedEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER)), 0.5f);
		this.texture = texture;
	}

	@Override
	public BipedEntityRenderState createRenderState() {
		return new BipedEntityRenderState();
	}

	@Override
	public Identifier getTexture(BipedEntityRenderState state) {
		return texture;
	}
}
