package net.bubblesky.towerdefense.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WarlordDirectorTest {
	@Test
	void flawlessFastDistantClearEarnsScreenshotRankAndCountsAsDominant() {
		String grade = WavePerformance.grade(20, 500, 24.0, 0, 0, 0.0f, 0);
		assertEquals("S", grade);
		assertTrue(WavePerformance.dominant(grade, 24.0, 0, 0, 0.0f, 0));
	}

	@Test
	void partyAndIdolLossPreventFalseDominance() {
		String grade = WavePerformance.grade(20, 900, 3.0, 12, 2, 28.0f, 1);
		assertEquals("D", grade);
		assertFalse(WavePerformance.dominant(grade, 3.0, 12, 2, 28.0f, 1));
	}

	@Test
	void sustainedDominanceAddsPlayerChosenFrontsAtBoundedMilestones() {
		assertEquals(1, WavePerformance.requiredGates(1));
		assertEquals(2, WavePerformance.requiredGates(2));
		assertEquals(3, WavePerformance.requiredGates(5));
		assertEquals(4, WavePerformance.requiredGates(8));
		assertEquals(4, WavePerformance.requiredGates(100));
	}
}
