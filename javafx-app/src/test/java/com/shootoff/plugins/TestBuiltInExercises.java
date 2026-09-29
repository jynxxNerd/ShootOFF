package com.shootoff.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Rule;
import org.junit.Test;

import com.shootoff.gui.JavaFXThreadingRule;
import com.shootoff.plugins.engine.ExerciseEntry;
import com.shootoff.plugins.engine.LegacyExerciseEntry;

public class TestBuiltInExercises {
	@Rule public JavaFXThreadingRule javafxRule = new JavaFXThreadingRule();

	@Test
	public void tenBuiltInsInMenuOrder() {
		final List<ExerciseEntry> entries = BuiltInExercises.entries();

		assertEquals(List.of("ShootForScore", "ISSFStandardPistol", "RandomShoot", "TimedHolsterDrill", "ParForScore",
				"ParRandomShot", "BouncingTargets", "DuelingTree", "ShootDontShoot", "SteelChallenge"),
				entries.stream().map(entry -> entry.exerciseClass().getSimpleName()).toList());
		assertEquals(List.of(false, false, false, false, false, false, true, true, true, true),
				entries.stream().map(ExerciseEntry::isProjectorOnly).toList());
		// The ported ones are the v2 exercises both apps list
		assertEquals(BuiltInRegistry.entries(), entries.subList(0, 1));
		assertTrue(entries.subList(1, entries.size()).stream().allMatch(LegacyExerciseEntry.class::isInstance));
	}
}
