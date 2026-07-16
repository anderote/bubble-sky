package net.bubblesky.towerdefense.client.render;

import net.bubblesky.towerdefense.entity.ShellEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.FlyingItemEntityRenderer;

/**
 * Renders the cannon's {@link ShellEntity} as a spinning, dark artillery round in flight —
 * the {@code FIRE_CHARGE} item the shell carries, drawn a touch larger than default and lit
 * so the round glows as it whistles overhead. Reuses vanilla's {@link FlyingItemEntityRenderer}
 * exactly as thrown items (snowballs, ender pearls) are drawn; the shell's cinematic lives on
 * the server-spawned particle trail and impact burst, not in a bespoke model.
 */
public class ShellEntityRenderer extends FlyingItemEntityRenderer<ShellEntity> {

	/** Scale factor: the shell renders slightly enlarged so it reads clearly across the arena. */
	private static final float SHELL_SCALE = 1.25f;

	public ShellEntityRenderer(EntityRendererFactory.Context context) {
		// lit = true so the dark fire-charge round stays visible in flight (it's a "hot" shell).
		super(context, SHELL_SCALE, true);
	}
}
