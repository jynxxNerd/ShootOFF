package com.shootoff.compose.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.shootoff.compose.calibration.ProjectionOutline
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.theme.RangeDark
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalTime
import java.util.Optional

class TestSetupScreen {
    @get:Rule
    val compose = createComposeRule()

    private var app = AppFixture.app()

    @After
    fun close() = app.close()

    private fun showApp() = compose.setContent { RangeTheme(dark = true) { ShootOffApp(app) } }

    private fun step(step: Step, state: StepState) =
        compose.onNodeWithTag("step-${step.name}").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state.name))

    @Test
    fun theRailLeadsToSetupWhereWithoutACameraTheCameraStepIsNext() {
        showApp()

        compose.onNodeWithTag("rail-SETUP").performClick()

        compose.onNodeWithTag("setup-screen").assertExists()
        step(Step.CAMERA, StepState.NEXT)
        step(Step.PROJECTOR, StepState.WAITING)
        step(Step.CALIBRATE, StepState.WAITING)
        compose.onNodeWithTag("camera-missing").assertExists()
        compose.onNodeWithTag("setup-calibrate").assertExists()
    }

    @Test
    fun openingTheArenaOnSetupTicksTheStepAndShowsItsPreview() {
        app.navigate(Destination.SETUP)
        showApp()

        compose.onNodeWithTag("open-arena").performClick()

        compose.onNodeWithTag("arena-preview").assertExists()
        step(Step.PROJECTOR, StepState.DONE)
        compose.onNodeWithText("No arena").assertDoesNotExist()
    }

    @Test
    fun calibratingOnSetupShowsCancelOnTheStepAndOverTheFeed() {
        app.close()
        app = AppFixture.appWithCamera()
        app.openStartCamera()
        app.openArena()
        app.navigate(Destination.SETUP)
        showApp()

        compose.onNodeWithTag("setup-calibrate").performClick()

        compose.onNodeWithTag("setup-cancel").assertExists()
        compose.onNodeWithTag("calibration-cancel").assertExists()
        compose.onNodeWithText("Calibrating…").assertExists()

        compose.onNodeWithTag("setup-cancel").performClick()
        compose.onNodeWithTag("setup-calibrate").assertExists()
    }

    @Test
    fun aFinishedCalibrationStaysOnSetupAndSaysCalibrationComplete() {
        app.close()
        app = AppFixture.appWithCamera(wallClock = { LocalTime.of(14, 5) })
        app.openStartCamera()
        app.openArena()
        app.arena.value!!.setFullScreen(true)
        app.navigate(Destination.SETUP)
        showApp()

        compose.onNodeWithTag("setup-calibrate").performClick()
        compose.runOnIdle { app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0) }

        compose.onNodeWithTag("setup-screen").assertExists()
        compose.onNodeWithTag("calibration-complete").assertTextEquals("Calibration complete ✓ 14:05")
        step(Step.CALIBRATE, StepState.DONE)
    }

    @Test
    fun rememberAndShowGridAreOnTheCalibrateStep() {
        app.openArena()
        app.navigate(Destination.SETUP)
        showApp()

        compose.onNodeWithTag("remember-calibration").performClick()
        compose.onNodeWithTag("show-grid").performClick()

        assertTrue(app.rememberCalibration.value)
        assertTrue(app.arena.value!!.grid.value)
    }

    @Test
    fun theCalibratedProjectionIsOutlinedOverTheFeed() {
        // A 640x480 canvas shown at 320x240
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RangeTheme(dark = true) {
                    Box(Modifier.size(320.dp, 240.dp).testTag("feed")) {
                        ProjectionOutline(Rect(100.0, 80.0, 400.0, 300.0), SurfaceTransform.fit(Size(640.0, 480.0), 320f, 240f))
                    }
                }
            }
        }

        val pixels = compose.onNodeWithTag("feed").captureToImage().toPixelMap()
        // The left edge at x = 50, halfway down the outline; inside it, nothing
        assertEquals(RangeDark.accent, pixels[50, 115])
        assertEquals(0f, pixels[120, 115].alpha)
    }
}
