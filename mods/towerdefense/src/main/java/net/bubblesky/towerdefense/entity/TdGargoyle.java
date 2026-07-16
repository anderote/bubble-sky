package net.bubblesky.towerdefense.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.control.FlightMoveControl;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.pathing.BirdNavigation;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.world.World;

/**
 * The Gargoyle — a winged FLYER and the roster's dedicated anti-turtle unit. Where a
 * ground enemy has to path around (or a sapper tunnel through) the defender's walls, the
 * Gargoyle simply flies <em>over</em> them, bee-lining through the air straight at the
 * Idol. A moderately-tough, lightly-armoured harasser that punishes a player who has
 * boxed the Idol in with a tall wall and no anti-air coverage.
 *
 * <p><b>How it flies (and bypasses walls).</b> Like the vanilla bee/parrot pattern, it
 * swaps the default ground brain for airborne movement:
 * <ul>
 *   <li>its move control is a {@link FlightMoveControl} (which drives the body through
 *       3D space, cutting its own gravity while airborne);</li>
 *   <li>its navigation is a {@link BirdNavigation} (a 3D path-finder that routes up-and-over
 *       obstacles rather than being blocked by them).</li>
 * </ul>
 * During a managed wave the {@code WaveManager} recognises the flyer via
 * {@link #isFlyer()} and, instead of ever making it dig, lets this flight navigation carry
 * it over the wall to the Idol. Its cruising pace comes from the {@code generic.flying_speed}
 * attribute (registered in {@code ModEntities}), so — unlike the ground horde's fixed slow
 * zombie-march — it actually flies at a brisk clip.
 *
 * <p>It remains a fully ordinary {@code td_enemy}: tagged, red-glowing, telemetry-counted,
 * escalation-scaled, and killable by towers/players like any other roster member — a
 * defender just needs a tower that can reach it in the air.
 */
public class TdGargoyle extends TdEnemyEntity {
	public TdGargoyle(EntityType<? extends TdGargoyle> type, World world) {
		super(type, world);
		// Airborne move control: flies in 3D toward its target and suspends gravity while aloft
		// (maxPitchChange 20, noGravity true — the vanilla flying-mob tuning).
		this.moveControl = new FlightMoveControl(this, 20, true);
	}

	/**
	 * A {@link BirdNavigation} so the Gargoyle path-finds through the AIR — routing over walls
	 * and terrain toward the Idol rather than being stopped by them. Doors are left shut and
	 * swimming disabled (it is an air unit); the follow range is widened so it can path to the
	 * Idol from across the arena (the wave manager also bumps {@code generic.follow_range}).
	 */
	@Override
	protected EntityNavigation createNavigation(World world) {
		BirdNavigation nav = new BirdNavigation(this, world);
		nav.setCanOpenDoors(false);
		nav.setCanSwim(false);
		nav.setMaxFollowRange(64.0f);
		return nav;
	}

	/**
	 * Loose-spawn AI only (a spawn egg / creative test): fly up and melee whatever the base
	 * target goals acquire. During a managed wave the manager strips these and steers the body
	 * itself, so this never fights the wave-time flight steering.
	 */
	@Override
	protected void initGoals() {
		super.initGoals();
		this.goalSelector.add(2, new MeleeAttackGoal(this, 1.0, false));
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The Gargoyle is the roster's flyer: the wave manager routes it over walls instead of
	 * ever making it dig.
	 */
	@Override
	public boolean isFlyer() {
		return true;
	}
}
