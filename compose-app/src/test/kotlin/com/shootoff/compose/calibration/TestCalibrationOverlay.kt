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
}
