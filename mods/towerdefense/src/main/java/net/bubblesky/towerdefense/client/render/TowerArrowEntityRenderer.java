package net.bubblesky.towerdefense.client.render;

import net.bubblesky.towerdefense.entity.TowerArrowEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.ProjectileEntityRenderer;
import net.minecraft.client.render.entity.state.ProjectileEntityRenderState;
import net.minecraft.util.Identifier;

/**
 * Renders {@link TowerArrowEntity} as an ordinary arrow in flight (it only differs on
 * impact, where it raises a tower instead of sticking). Mirrors {@code FlagArrowEntityRenderer}.
 */
public class TowerArrowEntityRenderer
		extends ProjectileEntityRenderer<TowerArrowEntity, ProjectileEntityRenderState> {

	private static final Identifier TEXTURE =
		Identifier.ofVanilla("textures/entity/projectiles/arrow.png");

	public TowerArrowEntityRenderer(EntityRendererFactory.Context context) {
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
