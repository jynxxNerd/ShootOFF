package com.shootoff.plugins;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Rule;
import org.junit.Test;

import com.shootoff.gui.JavaFXThreadingRule;
import com.shootoff.plugins.engine.ExerciseEntry;

public class TestBuiltInExercises {
	@Rule public JavaFXThreadingRule javafxRule = new JavaFXThreadingRule();

	@Test
	public void tenBuiltInsInMenuOrder() {
		final List<ExerciseEntry> entries = BuiltInExercises.entries();

		assertEquals(List.of("ISSFStandardPistol", "RandomShoot", "ShootForScore", "TimedHolsterDrill", "ParForScore",
				"ParRandomShot", "BouncingTargets", "DuelingTree", "ShootDontShoot", "SteelChallenge"),
				entries.stream().map(entry -> entry.exerciseClass().getSimpleName()).toList());
		assertEquals(List.of(false, false, false, false, false, false, true, true, true, true),
				entries.stream().map(ExerciseEntry::isProjectorOnly).toList());
	}
}
