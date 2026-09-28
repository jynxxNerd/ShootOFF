package com.shootoff.compose.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shootoff.calibration.CalibrationCheck
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.config.SavedCalibration
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.util.Optional

/** Spec §8 "Nothing blocks", the rules seen on screen. */
class TestNothingBlocksViews {
    @get:Rule
    val compose = createComposeRule()

    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf()).apply {
        setRememberCalibration(true)
        setSavedCalibration(SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), true))
    }
    private val app = AppFixture.appWithCamera(settings)

    @After
    fun close() = app.close()

    @Test
    fun rule5CancelIsOnScreenWhileTheCheckRunsAndWhileCalibrating() {
        app.openStartCamera()
        app.openArena()
        app.navigate(Destination.SETUP)
        compose.setContent { RangeTheme(dark = true) { ShootOffApp(app) } }
        assertEquals(CheckState.Checking, app.check.value)

        compose.onNodeWithTag("setup-cancel").performClick()
        compose.waitForIdle()
        assertEquals(CheckState.NotVerified(CalibrationCheck.Reason.CANCELLED), app.check.value)

        compose.onNodeWithTag("setup-calibrate").performClick()
        compose.onNodeWithTag("calibration-cancel").performClick()
        compose.waitForIdle()
        assertFalse(app.calibration.value!!.state.value.calibrating)
    }

    @Test
    fun rule4WithNoCameraAndNoProjectorEachSaysSoAndTheRestOfTheAppWorks() {
        val bare = AppFixture.app(listOf(Rect(0.0, 0.0, 1920.0, 1080.0)))
        try {
            compose.setContent { RangeTheme(dark = true) { ShootOffApp(bare) } }

            // Range: the no-camera panel, and a drill that needs no projector can still be picked
            compose.onNodeWithTag("no-camera").assertExists()
            compose.onNodeWithTag("drill-picker").assertIsEnabled()

            compose.onNodeWithTag("rail-SETUP").performClick()
            compose.onNodeWithTag("camera-missing").assertExists()
            compose.onNodeWithTag("no-projector").assertExists()
            // The arena still opens, as a window
            compose.onNodeWithTag("open-arena").performClick()
            compose.onNodeWithTag("arena-preview").assertExists()

            compose.onNodeWithTag("rail-DRILLS").performClick()
            compose.onNodeWithText("Feed drill").assertExists()
            compose.onNodeWithTag("rail-SETTINGS").performClick()
            compose.onNodeWithText("SHOT MARKER SIZE").assertExists()
        } finally {
            bare.close()
        }
    }
}
