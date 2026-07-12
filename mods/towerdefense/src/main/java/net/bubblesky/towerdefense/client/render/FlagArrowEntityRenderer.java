package net.bubblesky.towerdefense.client.render;

import net.bubblesky.towerdefense.entity.FlagArrowEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.ProjectileEntityRenderer;
import net.minecraft.client.render.entity.state.ProjectileEntityRenderState;
import net.minecraft.util.Identifier;

/**
 * Renders {@link FlagArrowEntity} using the vanilla arrow model + arrow texture — the
 * flag arrow looks like an ordinary arrow in flight (it only differs on impact, where
 * it plants a flag instead of sticking). Mirrors vanilla {@code ArrowEntityRenderer}
 * but is typed to our entity and skips the tipped-arrow tint.
 */
public class FlagArrowEntityRenderer
		extends ProjectileEntityRenderer<FlagArrowEntity, ProjectileEntityRenderState> {

	private static final Identifier TEXTURE =
		Identifier.ofVanilla("textures/entity/projectiles/arrow.png");

	public FlagArrowEntityRenderer(EntityRendererFactory.Context context) {
		super(context);
	}

	@Override
	protected Identifier getTexture(ProjectileEntityRenderState state) {
		return TEXTURE;
	}

	@Override
	public ProjectileEntityRenderState createRenderState() {
		return new ProjectileEntityRenderState();
	}
}
