package net.bubblesky.towerdefense.item;

import net.bubblesky.towerdefense.tower.TowerKind;
import net.minecraft.item.Item;

/**
 * A one-shot "tower arrow": the ammo the shop ({@code /td buy <type>}) hands out
 * for a tower type. Loaded into the unified TD bow and fired; when the resulting
 * {@code TowerArrowEntity} lands, the tower structure builds where it hit. The item
 * itself just tags which {@link TowerKind} it will raise.
 */
public class TowerArrowItem extends Item {
	private final TowerKind kind;

	public TowerArrowItem(Settings settings, TowerKind kind) {
		super(settings);
		this.kind = kind;
	}

	/** The tower type this arrow builds on impact. */
	public TowerKind kind() {
		return kind;
	}
}
