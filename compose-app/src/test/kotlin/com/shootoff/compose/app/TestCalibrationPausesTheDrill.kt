package com.shootoff.compose.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.shell.Destination
import com.shootoff.geom.Rect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

/** Spec §8 Revision 2, decision 2: calibration pauses the drill, and never resets it. */
class TestCalibrationPausesTheDrill {
    private val app = AppFixture.appWithCamera()

    @AfterEach
    fun close() = app.close()

    private fun pauseLabel(): String? = app.drill.buttons.value.firstOrNull { it.label == "Pause" || it.label == "Resume" }?.label

    private fun startThePausingDrill() {
        AppFixture.setUpForProjectorDrills(app)
        assertTrue(app.startDrill(AppFixture.pausingDrill))
        awaitTrue { pauseLabel() == "Pause" }
    }

    private fun calibrateWithTheCamera() = app.calibration.value!!.calibrate(Rect(102.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

    @Test
    fun f6PausesTheDrillAndItStaysPausedWhereItWasAfterCalibrating() {
        startThePausingDrill()
        val host = app.runner.running.value!!.host

        app.handleKey(Key.F6, KeyEventType.KeyDown)

        awaitTrue { pauseLabel() == "Resume" }
        assertSame(host, app.runner.running.value!!.host)
        assertTrue(app.calibration.value!!.state.value.calibrating)
        assertTrue(app.arena.value!!.covered.value)

        calibrateWithTheCamera()

        // The same drill, still paused, with the arena as it was: never stopped, never restarted
        assertSame(host, app.runner.running.value!!.host)
        assertEquals("Resume", pauseLabel())
        assertFalse(app.arena.value!!.covered.value)
        assertEquals(Destination.SETUP, app.destination.value)

        assertTrue(app.perform(Shortcut.PAUSE_DRILL))
        awaitTrue { pauseLabel() == "Pause" }
    }

    @Test
    fun aDrillAlreadyPausedStaysPausedThroughACancelledCalibration() {
        startThePausingDrill()
        app.perform(Shortcut.PAUSE_DRILL)
        awaitTrue { pauseLabel() == "Resume" }

        assertTrue(app.startCalibration())
        app.cancelCalibration()

        // Calibration didn't press Resume
        Thread.sleep(100)
        assertEquals("Resume", pauseLabel())
        assertFalse(app.arena.value!!.covered.value)
        assertTrue(app.runner.running.value!!.host.isProjector)
    }

    @Test
    fun aPausedDrillKeepsShotDetectionOffAfterCalibrationUntilItResumes() {
        startThePausingDrill()
        val camera = app.camera.value!!

        app.startCalibration()
        awaitTrue { pauseLabel() == "Resume" }
        calibrateWithTheCamera()

        // Calibration turns detection back on once the pattern is well gone; not under a paused drill
        Thread.sleep(CalibrationFlow.DETECTION_RESTART_DELAY + 400)
        assertFalse(camera.isDetecting)

        app.perform(Shortcut.PAUSE_DRILL)
        awaitTrue { camera.isDetecting }
    }

    @Test
    fun aProjectorDrillWithNoPauseButtonIsStoppedAndStartedAfreshAsBefore() {
        AppFixture.setUpForProjectorDrills(app)
        assertTrue(app.startDrill(AppFixture.unpausableDrill))
        val first = app.runner.running.value!!.host

        app.startCalibration()
        assertNull(app.runner.running.value)

        calibrateWithTheCamera()
        assertNotSame(first, app.runner.running.value!!.host)
        assertEquals("Unpausable drill", app.runner.running.value!!.host.name)
    }

    // With Remember on nothing follows a calibration any more (spec §8 Revision 3): a drill with no Pause
    // button starts afresh as soon as the calibration ends, as it did before Plan 7
    @Test
    fun withRememberOnAnUnpausableProjectorDrillRestartsAsSoonAsCalibrationEnds() {
        AppFixture.setUpForProjectorDrills(app)
        assertTrue(app.startDrill(AppFixture.unpausableDrill))
        val first = app.runner.running.value!!.host
        app.setRememberCalibration(true)

        app.startCalibration()
        assertNull(app.runner.running.value)

        calibrateWithTheCamera()

        assertEquals(CheckState.Idle, app.check.value)
        assertNotSame(first, app.runner.running.value!!.host)
        assertEquals("Unpausable drill", app.runner.running.value!!.host.name)
    }

    // Review fix (Task 8 round 1): F3 must not resume shot detection, or the drill's rounds, while
    // calibration itself is under way (before any pattern the check or measurement shows).
    @Test
    fun f3DuringCalibrationDoesNothing() {
        startThePausingDrill()

        app.handleKey(Key.F6, KeyEventType.KeyDown)
        awaitTrue { pauseLabel() == "Resume" }

        assertFalse(app.perform(Shortcut.PAUSE_DRILL))
        assertEquals("Resume", pauseLabel())
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}
