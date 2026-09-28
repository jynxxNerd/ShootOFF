package com.shootoff.compose.calibration

import androidx.compose.ui.graphics.ImageBitmap
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.config.SavedCalibration
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class TestCalibrationOnRequest {
    private val fixture = CalibrationFixture()
    private val controller = fixture.controller
    private val arena = fixture.arena

    @Test
    fun theArenaGoingFullScreenNeverStartsCalibration() {
        arena.setFullScreen(true)
        controller.fullScreenChanged(true)

        assertFalse(controller.state.value.calibrating)
        assertFalse(fixture.events.contains("stop exercise"))
        assertFalse(fixture.events.contains("auto on"))
    }

    @Test
    fun startedOnAnArenaAlreadyFullScreenItLooksAtOnceAndTimesOutToTheBox() {
        arena.setFullScreen(true)
        controller.fullScreenChanged(true)

        controller.start()
        assertEquals(Message.AUTO_CALIBRATING, controller.state.value.message)

        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)

        assertEquals(Message.MANUAL_REQUEST, controller.state.value.message)
        assertNotNull(controller.state.value.box)
    }

    @Test
    fun anUnattendedCalibrationThatDoesntFindThePatternPutsTheArenaBackWithoutTheBox() {
        var notFound = 0
        arena.setFullScreen(true)

        controller.startUnattended { notFound++ }
        assertEquals("pattern.png", arena.background.value!!.name)
        assertTrue(arena.covered.value)

        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED)

        assertEquals(1, notFound)
        assertFalse(controller.state.value.calibrating)
        assertEquals(null, controller.state.value.box)
        assertEquals(null, controller.state.value.message)
        assertFalse(fixture.events.contains("show feed"))
        assertEquals(null, arena.background.value)
        assertFalse(arena.covered.value)
        assertTrue(arena.needsCalibrationLabel.value)
        assertFalse(fixture.events.any { it.startsWith("succeeded") })
    }

    @Test
    fun cancelLeavesTheArenaAndTheCameraAsTheyWereAndRestartsNothing() {
        val restarts = mutableListOf<String>()
        fixture.restartExercise = Optional.of(Runnable { restarts += "restart" })
        val background = ArenaBackground(ImageBitmap(4, 4), "backgrounds/blackBG.png")
        arena.setBackground(background)
        arena.setProjection(Rect(100.0, 80.0, 400.0, 300.0))
        arena.setCalibrationLabelVisible(false)
        fixture.camera.bounds = Rect(100.0, 80.0, 400.0, 300.0)
        arena.setFullScreen(true)

        controller.start()
        assertEquals("pattern.png", arena.background.value!!.name)
        assertEquals(null, fixture.camera.bounds)

        controller.cancel()

        assertFalse(controller.state.value.calibrating)
        assertSame(background, arena.background.value)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), fixture.camera.bounds)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), arena.projection.value)
        assertFalse(arena.needsCalibrationLabel.value)
        assertEquals(emptyList<String>(), restarts)
        assertFalse(fixture.events.any { it.startsWith("succeeded") })
    }

    @Test
    fun cancellingOnAnUncalibratedArenaBringsBackItsLabel() {
        arena.setFullScreen(true)
        controller.start()
        assertFalse(arena.needsCalibrationLabel.value)

        controller.cancel()

        assertTrue(arena.needsCalibrationLabel.value)
    }

    @Test
    fun aCalibrationTheUserStartedReportsItsBoundsAndPaper() {
        arena.setFullScreen(true)
        controller.start()

        controller.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.of(Size(11.0, 8.5)), false, 0)

        assertTrue(fixture.events.contains("succeeded ${Rect(100.0, 80.0, 400.0, 300.0)} ${Optional.of(Size(11.0, 8.5))}"))
    }

    @Test
    fun aStaleAutoDetectionNeverLeaksItsPaperIntoTheManualBoxsSuccess() {
        // The camera's completion hops to the UI thread; queue it instead of running it, so a detection
        // can be "in flight" while the manual box independently finishes calibration first
        val uiTasks = mutableListOf<Runnable>()
        val fixture = CalibrationFixture(uiThread = { uiTasks += it })
        val controller = fixture.controller
        val arena = fixture.arena

        arena.setFullScreen(true)
        controller.start()

        // A pattern is found, with a paper size, but its completion just sits queued
        controller.calibrate(Rect(10.0, 10.0, 50.0, 50.0), Optional.of(Size(11.0, 8.5)), false, 0)
        assertEquals(1, uiTasks.size)

        // Auto-calibration times out to the manual box before that queued completion ever runs
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)
        uiTasks.removeAt(uiTasks.size - 1).run()
        assertNotNull(controller.state.value.box)

        // The user finishes with the box, which found no paper of its own
        controller.flow.stop()

        assertTrue(fixture.events.contains("succeeded ${CalibrationController.DEFAULT_BOX} ${Optional.empty<Size>()}"))

        // The stale detection's queued completion, run late, changes nothing (the flow is no longer calibrating)
        val before = fixture.events.toList()
        uiTasks.single().run()
        assertEquals(before, fixture.events)
        assertEquals(CalibrationController.DEFAULT_BOX, fixture.camera.bounds)
    }

    @Test
    fun aRememberedCalibrationIsAppliedWithoutCalibratingOrReportingASuccess() {
        val saved = SavedCalibration("C270", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), true)

        controller.applySaved(saved)

        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), fixture.camera.bounds)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), arena.projection.value)
        assertFalse(arena.needsCalibrationLabel.value)
        assertFalse(controller.state.value.calibrating)
        assertFalse(fixture.events.contains("stop exercise"))
        assertFalse(fixture.events.any { it.startsWith("succeeded") })
    }
}
