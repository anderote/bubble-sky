package net.bubblesky.towerdefense.client.render;

import net.bubblesky.towerdefense.entity.TdSkeletonWarrior;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.SkeletonEntityModel;
import net.minecraft.client.render.entity.state.SkeletonEntityRenderState;
import net.minecraft.util.Identifier;

/**
 * Renderer for the Necromancer's raised {@link TdSkeletonWarrior}. The melee counterpart to
 * {@link TdSkeletonBipedRenderer}: although the entity is one of our friendly
 * {@link net.bubblesky.towerdefense.entity.TdAllyEntity} allies under the hood, we skin it
 * with the VANILLA skeleton look so it reads as a raised undead soldier —
 *
 * <ul>
 *   <li>the vanilla {@link SkeletonEntityModel} on the shared {@link EntityModelLayers#SKELETON}
 *       layer (a stock layer, so no custom model-layer registration is needed);</li>
 *   <li>the vanilla skeleton texture.</li>
 * </ul>
 *
 * <p>The base {@link BipedEntityRenderer} already draws the warrior's held sword, so no extra
 * feature layers are wired up here. The render state is a plain
 * {@link SkeletonEntityRenderState} (required by {@link SkeletonEntityModel}); its
 * skeleton-specific pose fields keep their defaults, which is fine for a melee ally that never
 * uses the vanilla bow-pull animation.
 */
public class TdSkeletonWarriorRenderer
		extends BipedEntityRenderer<TdSkeletonWarrior, SkeletonEntityRenderState, SkeletonEntityModel<SkeletonEntityRenderState>> {

	/** The vanilla skeleton skin. */
	private static final Identifier TEXTURE = Identifier.ofVanilla("textures/entity/skeleton/skeleton.png");

	public TdSkeletonWarriorRenderer(EntityRendererFactory.Context ctx) {
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
