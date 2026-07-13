package net.bubblesky.towerdefense.client.render;

import net.bubblesky.towerdefense.colony.ColonistEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.util.Identifier;

/**
 * Renderer for the colony {@link ColonistEntity} — the same vanilla biped
 * ({@code humanoid}) model the allies use (see {@link TdAllyBipedRenderer}) skinned with
 * the {@code colonist.png} worker texture, plus an {@link ArmorFeatureRenderer} so any
 * worn gear draws. The held tool (pickaxe/axe) is rendered by the base biped renderer.
 */
public class ColonistBipedRenderer
		extends BipedEntityRenderer<ColonistEntity, BipedEntityRenderState, BipedEntityModel<BipedEntityRenderState>> {
	private final Identifier texture;

	public ColonistBipedRenderer(EntityRendererFactory.Context ctx, Identifier texture) {
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
