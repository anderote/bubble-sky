package net.bubblesky.towerdefense.colony;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/**
 * Respawn-at-home: when a player dies, if they have a colony home flag in the same
 * dimension, put them back at the nearest one — their base, by their chests and
 * crafting table — instead of the far-off world spawn. Planting a {@code /colony flag}
 * among your base blocks therefore doubles as setting your respawn point (no bed needed,
 * which matters in TD where you can't sleep with wave monsters about).
 *
 * <p>Returning from the End ({@code alive == true}) is left alone; only a death respawn
 * relocates. If there's no colony flag in the respawn dimension, vanilla respawn stands.
 */
public final class ColonyRespawn {
	private ColonyRespawn() {
	}

	public static void register() {
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (alive) {
				return; // returned from the End, not a death — don't relocate
			}
			if (!(newPlayer.getWorld() instanceof ServerWorld world)) {
				return;
			}
			ColonyState state = ColonyState.get(world.getServer());
			String dim = world.getRegistryKey().getValue().toString();
			ColonyState.Flag flag = state.nearestFlag(oldPlayer.getBlockPos(), dim);
			if (flag == null) {
				return; // no colony home in this dimension — leave the vanilla respawn
			}
			BlockPos p = flag.pos();
			newPlayer.requestTeleport(p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5);
			newPlayer.sendMessage(Text.literal("Respawned at your colony home '" + flag.name() + "'.")
				.formatted(Formatting.GREEN), false);
		});
	}
}
