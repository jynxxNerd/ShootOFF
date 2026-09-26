package com.shootoff.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TestExerciseMetadata {
	@Test
	void fourArgumentMetadataIsNotProjectorOnly() {
		assertFalse(new ExerciseMetadata("Drill", "1.0", "me", "A drill").isProjectorOnly());
		assertTrue(new ExerciseMetadata("Drill", "2.0", "me", "A drill", true).isProjectorOnly());
	}

	@Test
	void projectorOnlyTakesPartInEquality() {
		final ExerciseMetadata v1 = new ExerciseMetadata("Drill", "1.0", "me", "A drill");

		assertEquals(v1, new ExerciseMetadata("Drill", "1.0", "me", "A drill", false));
		assertEquals(v1.hashCode(), new ExerciseMetadata("Drill", "1.0", "me", "A drill", false).hashCode());
		assertNotEquals(v1, new ExerciseMetadata("Drill", "1.0", "me", "A drill", true));
	}
}
