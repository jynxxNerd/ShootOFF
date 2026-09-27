package com.shootoff.compose.drill

import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHostContract

/** ComposeExerciseHost on a camera feed keeps the host contract. */
class TestComposeExerciseHostContract : ExerciseHostContract() {
    override fun newHarness(exercise: Exercise, jarEntries: Map<String, ByteArray>): Harness =
        ComposeHostHarness.onCameraFeed(exercise, jarEntries, temp)
}
