package com.shootoff.compose.app

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec §8 "Nothing blocks": one test per rule. */
class TestNothingBlocks {
    @Test
    fun rule2CalibrationRunsOnlyWhenAskedNeverBecauseTheArenaOpened() {
        val background = TestProblems.QueueDispatcher()
        val app = AppFixture.appWithCamera(background = background)
        try {
            app.openStartCamera()
            app.openArena()
            // The arena window reaching the projector and going full screen
            app.arena.value!!.setFullScreen(true)
            background.drain()

            assertFalse(app.calibration.value!!.state.value.calibrating)
            assertNull(app.arena.value!!.background.value)

            // F6, or Calibrate on Setup
            assertTrue(app.startCalibration())
            assertTrue(app.calibration.value!!.state.value.calibrating)
        } finally {
            app.close()
        }
    }
}
