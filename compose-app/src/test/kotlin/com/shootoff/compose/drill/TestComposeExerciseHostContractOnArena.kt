package com.shootoff.compose.drill

import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHostContract

/** ComposeExerciseHost on the projector arena keeps the host contract. */
class TestComposeExerciseHostContractOnArena : ExerciseHostContract() {
    override fun newHarness(exercise: Exercise, jarEntries: Map<String, ByteArray>): Harness =
        ComposeHostHarness.onArena(exercise, jarEntries, temp)
}
