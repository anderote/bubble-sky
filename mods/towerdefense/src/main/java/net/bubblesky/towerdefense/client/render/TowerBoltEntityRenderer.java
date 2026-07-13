package net.bubblesky.towerdefense.client.render;

import net.bubblesky.towerdefense.entity.TowerBoltEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.ProjectileEntityRenderer;
import net.minecraft.client.render.entity.state.ProjectileEntityRenderState;
import net.minecraft.util.Identifier;

/**
 * Renders {@link TowerBoltEntity} as an ordinary vanilla arrow in flight — so the
 * ARROW and BALL towers visibly fire arrows (distinct from the frost/cannon towers'
 * snowballs). Mirrors {@code TowerArrowEntityRenderer}; the bolt only differs from a
 * real arrow in that it vanishes on impact instead of sticking.
 */
public class TowerBoltEntityRenderer
		extends ProjectileEntityRenderer<TowerBoltEntity, ProjectileEntityRenderState> {

	private static final Identifier TEXTURE =
		Identifier.ofVanilla("textures/entity/projectiles/arrow.png");

	public TowerBoltEntityRenderer(EntityRendererFactory.Context context) {
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
