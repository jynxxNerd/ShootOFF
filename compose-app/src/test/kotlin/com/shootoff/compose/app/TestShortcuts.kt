package com.shootoff.compose.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.drill.DrillButton
import com.shootoff.compose.shell.Destination
import com.shootoff.geom.Rect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class TestShortcuts {
    private val app = AppFixture.app()

    @AfterEach
    fun close() = app.close()

    private fun press(key: Key) = app.handleKey(key, KeyEventType.KeyDown)

    @Test
    fun f3PressesTheDrillsPauseOrResumeButton() {
        val pressed = mutableListOf<String>()
        app.drill.addButton(DrillButton(1, "Clear Shots") { pressed += "clear" })
        app.drill.addButton(DrillButton(2, "Resume") { pressed += "resume" })

        assertTrue(app.perform(Shortcut.PAUSE_DRILL))

        assertEquals(listOf("resume"), pressed)
    }

    @Test
    fun f4ClearsTheShots() {
        app.timer.appendShotRow(ScaledShot(ShotColor.RED, 1.0, 1.0, 1000), false, false)
        app.feedMarkers.add(1.0, 1.0, ShotColor.RED, 4)

        press(Key.F4)

        assertTrue(app.timer.rows.value.isEmpty())
        assertTrue(app.feedMarkers.markers.value.isEmpty())
    }

    @Test
    fun withNothingToDoAShortcutDoesNothing() {
        // No drill button to press, no camera to calibrate with
        assertFalse(app.perform(Shortcut.PAUSE_DRILL))
        assertFalse(app.perform(Shortcut.CALIBRATE))
        // Only a key going down counts, and only a shortcut's
        assertFalse(app.handleKey(Key.F3, KeyEventType.KeyUp))
        // F2 switched the view in Plan 5; there is no view to switch now
        assertFalse(app.handleKey(Key.F2, KeyEventType.KeyDown))
        assertFalse(app.handleKey(Key.A, KeyEventType.KeyDown))
    }

    @Test
    fun f6OpensSetupAndStartsCalibrating() {
        val app = AppFixture.appWithCamera()
        try {
            app.openStartCamera()
            app.openArena()

            assertTrue(app.handleKey(Key.F6, KeyEventType.KeyDown))

            assertEquals(Destination.SETUP, app.destination.value)
            assertTrue(app.calibration.value!!.state.value.calibrating)
        } finally {
            app.close()
        }
    }

    @Test
    fun f6DuringAProjectorDrillStopsItCalibratesAndStartsItAfresh() {
        val app = AppFixture.appWithCamera()
        try {
            app.openStartCamera()
            app.openArena()
            app.arena.value!!.setFullScreen(true)
            app.startCalibration()
            app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)
            assertTrue(app.startDrill(AppFixture.projectorDrill))
            val first = app.runner.running.value!!.host

            app.handleKey(Key.F6, KeyEventType.KeyDown)
            assertNull(app.runner.running.value)
            assertTrue(app.calibration.value!!.state.value.calibrating)

            app.calibration.value!!.calibrate(Rect(102.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

            assertNotSame(first, app.runner.running.value!!.host)
            assertEquals(Destination.RANGE, app.destination.value)
        } finally {
            app.close()
        }
    }
}
