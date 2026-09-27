package com.shootoff.compose.calibration

import com.shootoff.calibration.CalibrationFlow
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.geom.Rect
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
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
        // Covered, not hidden: the target keeps its own visibility under the pattern
        assertTrue(arena.covered.value)
        assertTrue(arena.targets.set.get(target.id).get().isVisible)

        // The camera reports the pattern on its 640x480 feed, shown 1:1 on the canvas
        controller.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), arena.projection.value)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), fixture.camera.bounds)
        assertSame(drillBackground, arena.background.value)
        assertFalse(arena.covered.value)
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
        val drillBackground = ArenaBackground(ImageBitmap(4, 4), "backgrounds/blackBG.png")
        arena.setBackground(drillBackground)
        // Watches the arena's background for the moment it comes back, to place it in fixture.events
        // alongside the view restore and the restart, in the order they really happen
        val backgroundWatch = CoroutineScope(Dispatchers.Unconfined)
        backgroundWatch.launch {
            arena.background.drop(1).collect { background -> if (background === drillBackground) fixture.events += "background restore" }
        }

        fixture.restartExercise = Optional.of(Runnable { fixture.events += "restart" })
        controller.toggle()
        assertEquals("stop exercise", fixture.events.first())

        arena.setFullScreen(true)
        controller.fullScreenChanged(true)
        fixture.fire(CalibrationFlow.FULL_SCREEN_SETTLE_DELAY)
        controller.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertEquals(
            listOf("restore view", "background restore", "restart"),
            fixture.events.filter { it in setOf("restore view", "background restore", "restart") },
        )

        backgroundWatch.cancel()
    }

    @Test
    fun theSuccessPathCompletesOnTheUiThreadNotTheCallingThread() {
        val uiTasks = mutableListOf<Runnable>()
        val fixture = CalibrationFixture(uiThread = { uiTasks += it })
        val controller = fixture.controller
        val arena = fixture.arena

        controller.toggle()
        arena.setFullScreen(true)
        controller.fullScreenChanged(true)
        // The settle timer's own completion is itself posted to the UI thread; run what was queued
        fixture.fire(CalibrationFlow.FULL_SCREEN_SETTLE_DELAY)
        assertEquals(1, uiTasks.size)
        uiTasks.removeAt(0).run()
        assertEquals(Message.AUTO_CALIBRATING, controller.state.value.message)

        controller.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        // The calling ("camera") thread only queued the completion; nothing has happened yet
        assertTrue(controller.state.value.calibrating)
        assertNull(arena.projection.value)
        assertEquals(1, uiTasks.size)

        uiTasks.removeAt(0).run()

        assertFalse(controller.state.value.calibrating)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), arena.projection.value)
    }

    @Test
    fun theManualBoxIsClampedToTheCanvasAsItMoves() {
        startOnTheProjector()
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)

        // Dragged past the top-left corner, then resized far past the bottom-right (the default settings'
        // display size, 640x480, matches the fake camera's feed here)
        controller.moveBox(Rect(-500.0, -500.0, 150.0, 150.0))
        assertEquals(Rect(0.0, 0.0, 150.0, 150.0), controller.state.value.box)

        controller.moveBox(Rect(0.0, 0.0, 5000.0, 5000.0))
        assertEquals(Rect(0.0, 0.0, 640.0, 480.0), controller.state.value.box)

        controller.flow.stop()

        val bounds = fixture.camera.bounds!!
        assertTrue(bounds.minX >= 0.0 && bounds.minY >= 0.0)
        assertTrue(bounds.maxX <= fixture.camera.feedWidth.toDouble())
        assertTrue(bounds.maxY <= fixture.camera.feedHeight.toDouble())
    }

    @Test
    fun closingTheArenaWhileCalibratingEndsCalibrationAndForgetsTheProjection() {
        val order = mutableListOf<String>()
        fixture.restartExercise = Optional.of(Runnable { order += "restart" })
        startOnTheProjector()

        controller.arenaClosing()

        assertFalse(controller.state.value.calibrating)
        assertNull(arena.projection.value)
        assertNull(fixture.camera.bounds)
        assertTrue(order.isEmpty())

        // The camera is left in a normal, not-calibrating state: auto-cal off, and detection back on
        assertTrue(fixture.events.contains("auto off"))
        assertTrue(fixture.events.contains("camera calibrating false"))
        fixture.fire(CalibrationFlow.DETECTION_RESTART_DELAY)
        assertTrue(fixture.events.contains("camera detecting true"))

        // The cancelled auto-calibration timeout never brings the manual box back
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)
        assertNull(controller.state.value.box)
    }

    @Test
    fun closingTheArenaDuringManualCalibrationDropsTheBoxUnsavedAndDoesNotRestart() {
        val order = mutableListOf<String>()
        fixture.restartExercise = Optional.of(Runnable { order += "restart" })
        startOnTheProjector()
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)
        controller.moveBox(Rect(120.0, 90.0, 380.0, 280.0))

        controller.arenaClosing()

        assertFalse(controller.state.value.calibrating)
        assertNull(controller.state.value.box)
        assertNull(arena.projection.value)
        assertNull(fixture.camera.bounds)
        assertTrue(order.isEmpty())

        assertTrue(fixture.events.contains("auto off"))
        assertTrue(fixture.events.contains("camera calibrating false"))
        fixture.fire(CalibrationFlow.DETECTION_RESTART_DELAY)
        assertTrue(fixture.events.contains("camera detecting true"))
    }

    @Test
    fun closingThenReopeningStartsFreshOnlyWhenAskedWithNoStaleState() {
        val restarts = mutableListOf<String>()
        fixture.restartExercise = Optional.of(Runnable { restarts += "restart" })
        startOnTheProjector()
        assertEquals(1, fixture.events.count { it == "stop exercise" })

        controller.arenaClosing()

        // Reopening straight onto the (still full screen) projector doesn't calibrate by itself
        controller.fullScreenChanged(true)
        assertEquals(1, fixture.events.count { it == "stop exercise" })
        assertFalse(controller.state.value.calibrating)

        // Asked to, it goes through a fresh start(), not a stale "already calibrating" branch left over
        // from the cancelled session
        controller.start()

        assertEquals(2, fixture.events.count { it == "stop exercise" })
        assertTrue(controller.state.value.calibrating)
        assertEquals(Message.AUTO_CALIBRATING, controller.state.value.message)

        controller.toggle()
        assertEquals(listOf("restart"), restarts)
    }

    @Test
    fun aCalibratedRunnableQueuedBeforeTheArenaClosesDoesNothingAfter() {
        val uiTasks = mutableListOf<Runnable>()
        val fixture = CalibrationFixture(uiThread = { uiTasks += it })
        val controller = fixture.controller
        val arena = fixture.arena

        controller.toggle()
        arena.setFullScreen(true)
        controller.fullScreenChanged(true)
        fixture.fire(CalibrationFlow.FULL_SCREEN_SETTLE_DELAY)
        uiTasks.removeAt(0).run()
        assertEquals(Message.AUTO_CALIBRATING, controller.state.value.message)

        controller.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)
        assertEquals(1, uiTasks.size)
        val queuedCompletion = uiTasks.removeAt(0)

        controller.arenaClosing()

        // The stale completion, queued before the close, must do nothing now that it finally runs
        queuedCompletion.run()

        assertNull(arena.projection.value)
        assertNull(fixture.camera.bounds)
        assertFalse(controller.state.value.calibrating)
    }

    @Test
    fun closingMidCalibrationRestoresTheOriginalBackgroundAndShowsTargetsAndShots() {
        fixture.settings.setShowArenaShotMarkers(true)
        val originalBackground = ArenaBackground(ImageBitmap(4, 4), "backgrounds/blackBG.png")
        arena.setBackground(originalBackground)
        val target = arena.targets.add(
            TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 10.0, 10.0, "red", mapOf()))),
            ResourceResolver.files(),
        )
        startOnTheProjector()
        assertEquals("pattern.png", arena.background.value!!.name)
        assertTrue(arena.covered.value)
        assertFalse(arena.markers.visible.value)

        controller.arenaClosing()

        // The model is left exactly as it was before calibration started
        assertSame(originalBackground, arena.background.value)
        assertFalse(arena.covered.value)
        assertTrue(arena.targets.set.get(target.id).get().isVisible)
        assertTrue(arena.markers.visible.value)
    }

    @Test
    fun aTargetThePausedDrillHidWhileCalibratingStaysHiddenAfterwards() {
        val target = arena.targets.add(
            TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 10.0, 10.0, "red", mapOf()))),
            ResourceResolver.files(),
        )
        startOnTheProjector()

        // The drill pauses on its own thread once calibration has started, and hides its round's target
        arena.targets.set.setVisible(target.id, false)
        controller.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertFalse(arena.covered.value)
        assertFalse(arena.targets.set.get(target.id).get().isVisible)
    }

    @Test
    fun reopeningThenCalibratingSuccessfullyRestoresTheOriginalBackgroundNotThePattern() {
        val originalBackground = ArenaBackground(ImageBitmap(4, 4), "backgrounds/blackBG.png")
        arena.setBackground(originalBackground)
        startOnTheProjector()

        controller.arenaClosing()

        // Reopen the same model and let calibration succeed this time
        startOnTheProjector()
        controller.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertSame(originalBackground, arena.background.value)
    }

    @Test
    fun aDetectionQueuedAfterCancellationSetsNoProjection() {
        val uiTasks = mutableListOf<Runnable>()
        val fixture = CalibrationFixture(uiThread = { uiTasks += it })
        val controller = fixture.controller
        val arena = fixture.arena

        controller.toggle()
        arena.setFullScreen(true)
        controller.fullScreenChanged(true)
        fixture.fire(CalibrationFlow.FULL_SCREEN_SETTLE_DELAY)
        uiTasks.removeAt(0).run()
        assertEquals(Message.AUTO_CALIBRATING, controller.state.value.message)

        controller.arenaClosing()

        // A detection from a frame that was already in flight when the arena closed: calibrate() only
        // reads the generation once it (finally) runs here, after the close already bumped it, so the
        // generation alone matches; the flow no longer calibrating is what has to stop it
        controller.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)
        assertEquals(1, uiTasks.size)
        uiTasks.removeAt(0).run()

        assertNull(arena.projection.value)
        assertNull(fixture.camera.bounds)
    }
}
