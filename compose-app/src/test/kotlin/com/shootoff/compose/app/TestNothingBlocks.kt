package com.shootoff.compose.app

import com.shootoff.calibration.CalibrationCheck
import com.shootoff.compose.calibration.CheckState
import com.shootoff.config.SavedCalibration
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

    @Test
    fun rule3TheCheckRunsOffTheUiThreadAndGivesUpQuietlyAfterThreeSeconds() {
        val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
        settings.setRememberCalibration(true)
        settings.setSavedCalibration(SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty()))
        val now = AtomicLong(0)
        val lookedOn = AtomicReference<Thread>()
        // The projector is off: the pattern is never seen
        val app = AppFixture.appWithCamera(settings, detector = {
            lookedOn.set(Thread.currentThread())
            Optional.empty()
        }, checkClock = now::get)
        try {
            app.openStartCamera()
            app.openArena()
            app.arena.value!!.setFullScreen(true)
            awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }
            app.cameraView.updateBackground(BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR), Optional.empty())
            awaitTrue { lookedOn.get() != null }

            now.set(CalibrationCheck.DEFAULT_TIME_LIMIT)
            awaitTrue { app.check.value != CheckState.Checking }

            assertEquals(CheckState.NotVerified(CalibrationCheck.Reason.PATTERN_NOT_SEEN), app.check.value)
            assertNotSame(Thread.currentThread(), lookedOn.get())
            assertNull(app.arena.value!!.projection.value)
            assertNull(app.arena.value!!.background.value)
        } finally {
            app.close()
        }
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}
