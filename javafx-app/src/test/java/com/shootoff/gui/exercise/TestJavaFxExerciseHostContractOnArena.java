package com.shootoff.gui.exercise;

import java.util.Map;

import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHostContract;

/**
 * JavaFxExerciseHost on the projector arena keeps the host contract, on the arena window and on the
 * arena tab that mirrors it.
 */
class TestJavaFxExerciseHostContractOnArena extends ExerciseHostContract {
	@Override
	protected Harness newHarness(Exercise exercise, Map<String, byte[]> jarEntries) throws Exception {
		return JavaFxHostHarness.onArena(exercise, jarEntries, temp);
	}
}
