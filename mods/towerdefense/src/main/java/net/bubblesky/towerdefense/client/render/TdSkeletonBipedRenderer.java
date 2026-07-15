package net.bubblesky.towerdefense.client.render;

import net.bubblesky.towerdefense.entity.TdSkeletonArcher;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.SkeletonEntityModel;
import net.minecraft.client.render.entity.state.SkeletonEntityRenderState;
import net.minecraft.util.Identifier;

/**
 * Renderer for the Warlord's summoned {@link TdSkeletonArcher}. Although the entity is
 * one of our friendly {@link net.bubblesky.towerdefense.entity.TdAllyEntity} allies
 * under the hood (see {@link TdSkeletonArcher} for why it is not a vanilla skeleton),
 * we skin it with the VANILLA skeleton look so it reads as a raised undead bowman:
 *
 * <ul>
 *   <li>the vanilla {@link SkeletonEntityModel} on the shared {@link EntityModelLayers#SKELETON}
 *       layer (a stock layer, so no custom model-layer registration is needed);</li>
 *   <li>the vanilla skeleton texture.</li>
 * </ul>
 *
 * <p>The base {@link BipedEntityRenderer} already draws the held bow, so no extra
 * feature layers are wired up here. The render state is a plain
 * {@link SkeletonEntityRenderState} (required by {@link SkeletonEntityModel}); the
 * skeleton-specific pose fields simply keep their defaults, which is fine for an ally
 * that never uses the vanilla bow-pull animation.
 */
public class TdSkeletonBipedRenderer
		extends BipedEntityRenderer<TdSkeletonArcher, SkeletonEntityRenderState, SkeletonEntityModel<SkeletonEntityRenderState>> {

	/** The vanilla skeleton skin. */
	private static final Identifier TEXTURE = Identifier.ofVanilla("textures/entity/skeleton/skeleton.png");

	public TdSkeletonBipedRenderer(EntityRendererFactory.Context ctx) {
		super(ctx, new SkeletonEntityModel<>(ctx.getPart(EntityModelLayers.SKELETON)), 0.5f);
	}

	@Override
	public SkeletonEntityRenderState createRenderState() {
		return new SkeletonEntityRenderState();
	}

	@Override
	public Identifier getTexture(SkeletonEntityRenderState state) {
		return TEXTURE;
	}
}
