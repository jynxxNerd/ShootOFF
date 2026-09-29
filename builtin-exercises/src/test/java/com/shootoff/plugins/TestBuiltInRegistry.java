package com.shootoff.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.shootoff.JavaFxReferenceScanner;
import com.shootoff.plugins.engine.V2ExerciseEntry;

class TestBuiltInRegistry {
	@Test
	void theStandardExercisesAreListedInTheTrainingMenusOrder() {
		final List<V2ExerciseEntry> entries = BuiltInRegistry.entries();

		assertEquals(List.of("Random Shoot", "Shoot for Score"),
				entries.stream().map(entry -> entry.metadata().getName()).toList());
		assertEquals(List.of(false, false), entries.stream().map(V2ExerciseEntry::isProjectorOnly).toList());
	}

	@Test
	void eachRunIsAFreshInstanceThatDescribesItselfAsListed() throws Exception {
		for (final V2ExerciseEntry entry : BuiltInRegistry.entries()) {
			assertNotSame(entry.newInstance(), entry.newInstance());
			assertEquals(entry.metadata(), entry.newInstance().metadata());
			assertFalse(entry.metadata().getDescription().isEmpty());
		}
	}

	@Test
	void theBuiltInExercisesDoNotReferenceJavaFx() throws Exception {
		assertEquals(List.of(), JavaFxReferenceScanner.findJavaFxReferencesNextTo(BuiltInRegistry.class));
	}
}
