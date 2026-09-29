package com.shootoff.compose.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.drill.DrillButton
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.targets.ManualClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    fun f5ResetsAndStandsFallenTargetsBackUp() {
        val clock = ManualClock()
        val app = AppFixture.appWithCamera(clock = clock)
        try {
            app.openStartCamera()
            app.openArena()
            val key = AppFixture.fallenPopper(app.arenaLayout.targets, clock)

            assertTrue(app.handleKey(Key.F5, KeyEventType.KeyDown))

            assertTrue(app.arenaLayout.targets.animations.isOnFirstFrame(key))
        } finally {
            app.close()
        }
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
}
