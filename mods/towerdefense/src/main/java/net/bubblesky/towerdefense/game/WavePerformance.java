package net.bubblesky.towerdefense.game;

/** Pure, Minecraft-independent scoring rules for wave reports and adaptive gate milestones. */
public final class WavePerformance {
	private WavePerformance() {
	}

	public static String grade(int spawned, int durationTicks, double closestApproach,
			int idolDamage, int leaked, float heroHealthLost, int heroDeaths) {
		int score = 100;
		score -= Math.min(45, idolDamage * 3);
		score -= Math.min(30, leaked * 10);
		score -= Math.min(30, heroDeaths * 15);
		score -= Math.min(20, Math.round(heroHealthLost / 2.0f));
		if (closestApproach >= 0.0) {
			if (closestApproach <= 6.0) score -= 15;
			else if (closestApproach <= 16.0) score -= 7;
		}
		double seconds = durationTicks / 20.0;
		double parSeconds = Math.max(30.0, spawned * 1.8);
		if (seconds > parSeconds * 1.75) score -= 12;
		else if (seconds > parSeconds * 1.25) score -= 6;
		return score >= 92 ? "S" : score >= 82 ? "A" : score >= 70 ? "B" : score >= 55 ? "C" : "D";
	}

	public static boolean dominant(String grade, double closestApproach, int idolDamage,
			int leaked, float heroHealthLost, int heroDeaths) {
		return ("S".equals(grade) || "A".equals(grade))
			&& idolDamage == 0 && leaked == 0 && heroDeaths == 0
			&& heroHealthLost <= 8.0f && closestApproach > 16.0;
	}

	public static int requiredGates(int streak) {
		if (streak >= 8) return 4;
		if (streak >= 5) return 3;
		if (streak >= 2) return 2;
		return 1;
	}
}
