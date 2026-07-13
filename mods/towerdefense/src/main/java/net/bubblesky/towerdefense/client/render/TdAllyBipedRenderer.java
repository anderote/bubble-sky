package net.bubblesky.towerdefense.client.render;

import net.bubblesky.towerdefense.entity.TdAllyEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.util.Identifier;

/**
 * Renderer for the friendly ally roster — the same vanilla biped ({@code humanoid})
 * model as the enemies (see {@link TdBipedEntityRenderer}) but skinned with a
 * blue-tinted texture so allies read as friendly on the field. One instance per
 * ally {@link net.minecraft.entity.EntityType}; the texture is passed at
 * registration time.
 *
 * <p>Beyond the plain biped, this adds an {@link ArmorFeatureRenderer} so the themed
 * armour + uniforms the allies are kitted out with on spawn actually draw on the
 * model (the base {@code BipedEntityRenderer} already renders held weapons/bows, but
 * not worn armour).
 */
public class TdAllyBipedRenderer
		extends BipedEntityRenderer<TdAllyEntity, BipedEntityRenderState, BipedEntityModel<BipedEntityRenderState>> {
	private final Identifier texture;

	public TdAllyBipedRenderer(EntityRendererFactory.Context ctx, Identifier texture) {
		super(ctx, new BipedEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER)), 0.5f);
		this.texture = texture;
		this.addFeature(new ArmorFeatureRenderer<>(this,
			new BipedEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
			new BipedEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)),
			ctx.getEquipmentRenderer()));
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
