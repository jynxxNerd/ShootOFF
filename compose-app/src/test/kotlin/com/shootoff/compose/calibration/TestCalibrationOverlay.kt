package com.shootoff.compose.calibration

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class TestCalibrationOverlay {
    @get:Rule
    val compose = createComposeRule()

    private val fixture = CalibrationFixture()
    private val controller = fixture.controller

    // A 320x240 canvas drawn at twice its size, so view pixels and canvas pixels differ
    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RangeTheme(dark = true) {
                    Box(Modifier.size(640.dp, 480.dp)) {
                        CalibrationOverlay(controller, SurfaceTransform.fit(Size(320.0, 240.0), 640f, 480f))
                    }
                }
            }
        }
    }

    private fun startOnTheProjector() {
        controller.toggle()
        fixture.arena.setFullScreen(true)
        controller.fullScreenChanged(true)
        fixture.fire(CalibrationFlow.FULL_SCREEN_SETTLE_DELAY)
    }

    @Test
    fun whileLookingForThePatternTheUserCanCancel() {
        startOnTheProjector()
        show()

        compose.onNodeWithText("Looking for the calibration pattern…").assertExists()
        compose.onNodeWithTag("calibration-cancel").performClick()

        assertFalse(controller.state.value.calibrating)
    }

    @Test
    fun theManualBoxCanBeCancelledTooLeavingNoProjection() {
        startOnTheProjector()
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)
        show()

        compose.onNodeWithTag("calibration-done").assertExists()
        compose.onNodeWithTag("calibration-cancel").performClick()

        assertFalse(controller.state.value.calibrating)
        assertEquals(null, fixture.arena.projection.value)
        compose.onNodeWithTag("calibration-box").assertDoesNotExist()
    }

    @Test
    fun theBoxMovesAndResizesWithTheMouseAtTheFeedsScale() {
        startOnTheProjector()
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)
        show()

        // Dragging 10 view pixels moves the box 5 canvas pixels
        compose.onNodeWithTag("calibration-box").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(10f, 5f))
            release()
        }
        compose.waitForIdle()
        assertEquals(Rect(80.0, 77.5, 150.0, 150.0), controller.state.value.box)

        compose.onNodeWithTag("calibration-corner-bottom-right").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(20f, 10f))
            release()
        }
        compose.waitForIdle()
        assertEquals(Rect(80.0, 77.5, 160.0, 155.0), controller.state.value.box)

        compose.onNodeWithTag("calibration-done").performClick()
        assertEquals(Rect(80.0, 77.5, 160.0, 155.0), fixture.arena.projection.value)
    }

    // The owner's box (Plan 8's hardware check): the handles moved about a tenth as far as the mouse. A real mouse
    // sends many small moves between two frames, and every one of them must count
    @Test
    fun aCornerFollowsEveryMoveOfTheMouseNotOnlyTheLastBeforeTheNextFrame() {
        startOnTheProjector()
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)
        show()

        // Nothing is drawn (so nothing recomposes) between the moves, as with a fast mouse
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("calibration-corner-bottom-right").performMouseInput {
            moveTo(center)
            press()
            repeat(10) { moveBy(Offset(2f, 1f), delayMillis = 0) }
            release()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        // 20 x 10 view pixels are 10 x 5 canvas pixels
        assertEquals(Rect(75.0, 75.0, 160.0, 155.0), controller.state.value.box)
    }

    @Test
    fun theBoxFollowsEveryMoveOfTheMouseToo() {
        startOnTheProjector()
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)
        show()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("calibration-box").performMouseInput {
            moveTo(center)
            press()
            repeat(10) { moveBy(Offset(2f, 1f), delayMillis = 0) }
            release()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertEquals(Rect(85.0, 80.0, 150.0, 150.0), controller.state.value.box)
    }

    // Held at the canvas's edge while the pointer goes on, the box is back under the pointer when it returns
    @Test
    fun theBoxStaysUnderThePointerAfterBeingHeldAtTheCanvasEdge() {
        startOnTheProjector()
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)
        show()

        compose.onNodeWithTag("calibration-box").performMouseInput {
            moveTo(center)
            press()
            // 100 canvas pixels left: the box stops at the edge, 75 canvas pixels along
            moveBy(Offset(-200f, 0f))
            moveBy(Offset(200f, 0f))
            release()
        }
        compose.waitForIdle()

        assertEquals(Rect(75.0, 75.0, 150.0, 150.0), controller.state.value.box)
    }
}
