package com.shootoff.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TestExerciseValues {
	@Test
	void delayRangeRejectsAMinimumAboveTheMaximum() {
		assertEquals(5, new DelayRange(5, 5).maxSeconds());
		assertThrows(IllegalArgumentException.class, () -> new DelayRange(5, 4));
		assertThrows(IllegalArgumentException.class, () -> new DelayRange(-1, 4));
	}
}
