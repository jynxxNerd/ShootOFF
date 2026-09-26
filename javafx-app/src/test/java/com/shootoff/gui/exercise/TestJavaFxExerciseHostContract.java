package com.shootoff.gui.exercise;

import java.util.Map;

import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHostContract;

/**
 * JavaFxExerciseHost on a camera feed keeps the host contract.
 */
class TestJavaFxExerciseHostContract extends ExerciseHostContract {
	@Override
	protected Harness newHarness(Exercise exercise, Map<String, byte[]> jarEntries) throws Exception {
		return JavaFxHostHarness.onCameraFeed(exercise, jarEntries, temp);
	}
}
