package net.bubblesky.towerdefense.game;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.random.Random;

/**
 * Decides the wave-completion loot bundle: curated per-band item pools (chosen by wave
 * number), a random subset each wave, and counts that scale gently as waves grow. Boss
 * waves draw a larger bundle plus a guaranteed premium item. Numeric helpers are pure.
 */
public final class WaveRewards {
	private static final int[] BAND_MIN_WAVE = {1, 5, 10, 15};
	private static final int MIN_SUBSET = 3;
	private static final int MAX_SUBSET = 5;
	private static final int BOSS_BONUS_SUBSET = 2;

	private record Entry(Item item, int minCount, int maxCount) {
	}

	private static final Entry[][] BANDS = {
		{
			new Entry(Items.BREAD, 4, 8),
			new Entry(Items.COOKED_BEEF, 2, 5),
			new Entry(Items.OAK_LOG, 8, 16),
			new Entry(Items.COBBLESTONE, 16, 32),
			new Entry(Items.TORCH, 8, 16),
			new Entry(Items.ARROW, 8, 16),
		},
		{
			new Entry(Items.COOKED_BEEF, 4, 8),
			new Entry(Items.IRON_INGOT, 2, 5),
			new Entry(Items.COAL, 6, 12),
			new Entry(Items.APPLE, 3, 6),
			new Entry(Items.IRON_HELMET, 1, 1),
			new Entry(Items.IRON_SWORD, 1, 1),
		},
		{
			new Entry(Items.DIAMOND, 1, 3),
			new Entry(Items.GOLDEN_APPLE, 1, 2),
			new Entry(Items.REDSTONE, 6, 12),
			new Entry(Items.ENDER_PEARL, 1, 3),
			new Entry(Items.DIAMOND_CHESTPLATE, 1, 1),
			new Entry(Items.COOKED_BEEF, 8, 12),
		},
		{
			new Entry(Items.DIAMOND, 3, 6),
			new Entry(Items.NETHERITE_SCRAP, 1, 2),
			new Entry(Items.GOLDEN_APPLE, 2, 4),
			new Entry(Items.ENCHANTED_GOLDEN_APPLE, 1, 1),
			new Entry(Items.DIAMOND_SWORD, 1, 1),
			new Entry(Items.EXPERIENCE_BOTTLE, 4, 8),
		},
	};

	private WaveRewards() {
	}

	public static int bandForWave(int wave) {
		int band = 0;
		for (int i = 0; i < BAND_MIN_WAVE.length; i++) {
			if (wave >= BAND_MIN_WAVE[i]) {
				band = i;
			}
		}
		return band;
	}

	public static int subsetSize(boolean bossWave) {
		return (bossWave ? MAX_SUBSET + BOSS_BONUS_SUBSET : MAX_SUBSET);
	}

	/** Gentle linear growth: +3% per wave, always >= base. */
	public static int scaledCount(int baseCount, int wave) {
		return Math.max(baseCount, (int) Math.round(baseCount * (1.0 + 0.03 * wave)));
	}

	/** Assemble the loot stacks for a cleared wave. */
	public static List<ItemStack> rollDrops(int wave, boolean bossWave, Random rng) {
		Entry[] pool = BANDS[bandForWave(wave)];
		int target = Math.min(subsetSize(bossWave), pool.length);
		int n = Math.max(Math.min(MIN_SUBSET, pool.length), target);
		int[] idx = new int[pool.length];
		for (int i = 0; i < idx.length; i++) {
			idx[i] = i;
		}
		for (int i = idx.length - 1; i > 0; i--) {
			int j = rng.nextInt(i + 1);
			int tmp = idx[i];
			idx[i] = idx[j];
			idx[j] = tmp;
		}
		List<ItemStack> out = new ArrayList<>();
		for (int k = 0; k < n; k++) {
			Entry e = pool[idx[k]];
			int span = e.maxCount() - e.minCount();
			int base = e.minCount() + (span > 0 ? rng.nextInt(span + 1) : 0);
			int count = scaledCount(base, wave);
			out.add(new ItemStack(e.item(), Math.max(1, Math.min(count, e.item().getMaxCount()))));
		}
		if (bossWave) {
			Entry[] top = BANDS[BANDS.length - 1];
			out.add(new ItemStack(top[rng.nextInt(top.length)].item(), 1));
		}
		out.addAll(rollGunDrops(wave, bossWave, rng));
		return out;
	}

	/**
	 * Guns++ loot on top of the vanilla bundle: ammo (and sometimes a gun) scaled by the
	 * same {@link #bandForWave(int)} band. Every band always drops ammo appropriate to its
	 * tier; guns come out on a per-band chance roll. Boss waves additionally GUARANTEE one
	 * band-appropriate gun plus a generous matching-ammo stack (on top of the chance rolls).
	 */
	public static List<ItemStack> rollGunDrops(int wave, boolean bossWave, Random rng) {
		List<ItemStack> out = new ArrayList<>();
		int band = bandForWave(wave);
		switch (band) {
			case 0 -> {
				// Waves 1-4: pistol ammo always; sometimes a Glock to go with it.
				out.add(GunsPlusPlus.bullets(8 + rng.nextInt(9)));
				if (rng.nextInt(100) < 35) {
					out.add(GunsPlusPlus.glock());
				}
			}
			case 1 -> {
				// Waves 5-9: medium + pistol ammo always; sometimes a rifle (AR-15 or AK-47).
				out.add(GunsPlusPlus.mediumRounds(12 + rng.nextInt(13)));
				out.add(GunsPlusPlus.bullets(8 + rng.nextInt(9)));
				if (rng.nextInt(100) < 40) {
					out.add(rng.nextBoolean() ? GunsPlusPlus.ar15() : GunsPlusPlus.ak47());
				}
			}
			case 2 -> {
				// Waves 10-14: medium + heavy ammo always; chance of a hunting rifle and/or a Deagle.
				out.add(GunsPlusPlus.mediumRounds(16 + rng.nextInt(17)));
				out.add(GunsPlusPlus.heavyRounds(4 + rng.nextInt(5)));
				if (rng.nextInt(100) < 35) {
					out.add(GunsPlusPlus.huntingRifle());
				}
				if (rng.nextInt(100) < 25) {
					out.add(GunsPlusPlus.deagle());
					out.add(GunsPlusPlus.fiftyCal(6 + rng.nextInt(7)));
				}
			}
			default -> {
				// Waves 15+: full spread of ammo always; chance of a top-tier gun.
				out.add(GunsPlusPlus.mediumRounds(24));
				out.add(GunsPlusPlus.heavyRounds(8));
				out.add(GunsPlusPlus.fiftyCal(6));
				if (rng.nextInt(100) < 45) {
					out.add(switch (rng.nextInt(3)) {
						case 0 -> GunsPlusPlus.awp();
						case 1 -> GunsPlusPlus.deagle();
						default -> GunsPlusPlus.ak47();
					});
				}
			}
		}
		if (bossWave) {
			// Guaranteed band-appropriate gun + a generous matching-ammo stack.
			switch (band) {
				case 0 -> {
					out.add(GunsPlusPlus.glock());
					out.add(GunsPlusPlus.bullets(24 + rng.nextInt(9)));
				}
				case 1 -> {
					out.add(rng.nextBoolean() ? GunsPlusPlus.ar15() : GunsPlusPlus.ak47());
					out.add(GunsPlusPlus.mediumRounds(24 + rng.nextInt(9)));
				}
				case 2 -> {
					out.add(GunsPlusPlus.huntingRifle());
					out.add(GunsPlusPlus.heavyRounds(24 + rng.nextInt(9)));
				}
				default -> {
					out.add(GunsPlusPlus.awp());
					out.add(GunsPlusPlus.heavyRounds(24 + rng.nextInt(9)));
				}
			}
		}
		return out;
	}
}
