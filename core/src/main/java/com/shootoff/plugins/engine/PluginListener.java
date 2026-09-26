package com.shootoff.plugins.engine;

/**
 * Hears which exercises the Training menu should list.
 */
public interface PluginListener {
	void registerExercise(ExerciseEntry exercise);

	void registerProjectorExercise(ExerciseEntry exercise);

	void unregisterExercise(ExerciseEntry exercise);
}
