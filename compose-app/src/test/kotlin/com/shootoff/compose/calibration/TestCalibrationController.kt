package com.shootoff.compose.calibration

import com.shootoff.calibration.CalibrationFlow
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.geom.Rect
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import androidx.compose.ui.graphics.ImageBitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class TestCalibrationController {
    private val fixture = CalibrationFixture()
    private val controller = fixture.controller
    private val arena = fixture.arena

    // The arena window opens windowed, then reaches the projector and goes full screen
    private fun startOnTheProjector() {
        controller.toggle()
        arena.setFullScreen(true)
        controller.fullScreenChanged(true)
        fixture.fire(CalibrationFlow.FULL_SCREEN_SETTLE_DELAY)
    }

    @Test
    fun calibrationAsksForFullScreenThenShowsThePatternAndLooks() {
        controller.toggle()
        assertEquals(Message.FULL_SCREEN_REQUEST, controller.state.value.message)
        assertTrue(controller.state.value.calibrating)

        arena.setFullScreen(true)
        controller.fullScreenChanged(true)
        fixture.fire(CalibrationFlow.FULL_SCREEN_SETTLE_DELAY)

        assertEquals(Message.AUTO_CALIBRATING, controller.state.value.message)
        assertEquals("pattern.png", arena.background.value!!.name)
        assertTrue(fixture.events.contains("auto on"))
        assertFalse(arena.needsCalibrationLabel.value)
    }

    @Test
    fun whenTheCameraFindsThePatternTheProjectionIsSetAndTheBackgroundComesBack() {
        val drillBackground = ArenaBackground(ImageBitmap(4, 4), "backgrounds/blackBG.png")
        arena.setBackground(drillBackground)
        val target = arena.targets.add(
            TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 10.0, 10.0, "red", mapOf()))),
            ResourceResolver.files(),
        )
        startOnTheProjector()
        assertFalse(arena.targets.set.get(target.id).get().isVisible)

        // The camera reports the pattern on its 640x480 feed, shown 1:1 on the canvas
        controller.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), arena.projection.value)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), fixture.camera.bounds)
        assertSame(drillBackground, arena.background.value)
        assertTrue(arena.targets.set.get(target.id).get().isVisible)
        assertFalse(controller.state.value.calibrating)
        assertNull(controller.state.value.message)
    }

    @Test
    fun whenAutoCalibrationTimesOutTheBoxShowsOnTheFeed() {
        startOnTheProjector()

        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)

        assertEquals(Message.MANUAL_REQUEST, controller.state.value.message)
        assertEquals(CalibrationController.DEFAULT_BOX, controller.state.value.box)
        assertTrue(fixture.events.contains("show feed"))
    }

    @Test
    fun doneCalibratesToTheBoxWhereTheUserLeftIt() {
        startOnTheProjector()
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)

        controller.moveBox(Rect(120.0, 90.0, 380.0, 280.0))
        controller.flow.stop()

        assertEquals(Rect(120.0, 90.0, 380.0, 280.0), arena.projection.value)
        assertNull(controller.state.value.box)
        assertTrue(fixture.events.contains("restore view"))
        assertNull(arena.background.value)
    }

    @Test
    fun aRunningProjectorDrillStopsFirstAndStartsAgainAfter() {
        val order = mutableListOf<String>()
        fixture.restartExercise = Optional.of(Runnable { order += "restart" })
        controller.toggle()
        assertEquals("stop exercise", fixture.events.first())

        arena.setFullScreen(true)
        controller.fullScreenChanged(true)
        fixture.fire(CalibrationFlow.FULL_SCREEN_SETTLE_DELAY)
        controller.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertEquals(listOf("restart"), order)
    }

    @Test
    fun closingTheArenaWhileCalibratingEndsCalibrationAndForgetsTheProjection() {
        startOnTheProjector()

        controller.arenaClosing()

        assertFalse(controller.state.value.calibrating)
        assertNull(arena.projection.value)
    }
}
