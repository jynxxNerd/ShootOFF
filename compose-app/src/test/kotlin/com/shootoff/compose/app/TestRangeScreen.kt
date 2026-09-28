package com.shootoff.compose.app

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.RangeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TestRangeScreen {
    @get:Rule
    val compose = createComposeRule()

    private val app = AppFixture.appWithCamera()

    @After
    fun close() = app.close()

    private fun showApp() = compose.setContent { RangeTheme(dark = true) { ShootOffApp(app) } }

    @Test
    fun theRangeIsForTrainingTheFeedWithTheChipAndClearShotsAndNoViewSwitch() {
        app.openStartCamera()
        showApp()

        compose.onNodeWithTag("camera-feed").assertExists()
        compose.onNodeWithTag("clear-shots").assertExists()
        compose.onNodeWithTag("view-camera").assertDoesNotExist()
        compose.onNodeWithTag("view-arena").assertDoesNotExist()
        compose.onNodeWithTag("arena-view").assertDoesNotExist()
        compose.onNodeWithTag("reset").assertDoesNotExist()
        compose.onNodeWithTag("open-arena").assertDoesNotExist()
    }

    @Test
    fun theChipSaysWhatIsMissingOrWhenItWasCalibratedAndOpensSetup() {
        app.openStartCamera()
        showApp()
        compose.onNodeWithTag("status-chip").assert(hasText("No arena"))

        AppFixture.setUpForProjectorDrills(app)
        compose.waitForIdle()
        compose.onNodeWithTag("status-chip").assert(hasText("✓ Calibrated", substring = true))

        compose.onNodeWithTag("status-chip").performClick()
        assertEquals(Destination.SETUP, app.destination.value)
    }

    @Test
    fun clearShotsEmptiesTheTimerAndTheMarkers() {
        app.openStartCamera()
        app.timer.appendShotRow(ScaledShot(ShotColor.RED, 1.0, 1.0, 1000), false, false)
        app.feedMarkers.add(1.0, 1.0, ShotColor.RED, 4)
        showApp()

        compose.onNodeWithTag("clear-shots").performClick()

        assertTrue(app.timer.rows.value.isEmpty())
        assertTrue(app.feedMarkers.markers.value.isEmpty())
    }

    @Test
    fun thePickedDrillStartsAndStopsOnItsCard() {
        app.openStartCamera()
        showApp()
        compose.onNodeWithTag("drill-picker").assert(hasText("Feed drill"))

        compose.onNodeWithTag("drill-start").performClick()
        assertNotNull(app.runner.running.value)
        compose.onNodeWithTag("drill-picker").assertIsNotEnabled()

        compose.onNodeWithTag("drill-stop").performClick()
        assertNull(app.runner.running.value)
        compose.onNodeWithTag("drill-start").assertExists()
    }

    @Test
    fun aProjectorDrillWaitsForSetupAndThenStarts() {
        app.openStartCamera()
        showApp()

        compose.onNodeWithTag("drill-picker").performClick()
        compose.onNodeWithTag("pick-Projector drill").performClick()
        compose.onNodeWithTag("drill-start").assertIsNotEnabled()
        compose.onNodeWithText(SET_UP_FIRST).assertExists()

        AppFixture.setUpForProjectorDrills(app)
        compose.waitForIdle()

        compose.onNodeWithTag("drill-start").assertIsEnabled().performClick()
        assertEquals(AppFixture.projectorDrill, app.runner.running.value!!.entry)
    }

    // Review fix (Task 8 round 1): Resume must not be pressable while a pattern shows, since pressing it
    // resumes shot detection and the drill's rounds under the cover, not through CalibratingCamera.
    @Test
    fun theResumeButtonIsDisabledWhileAPatternShows() {
        AppFixture.setUpForProjectorDrills(app)
        assertTrue(app.startDrill(AppFixture.pausingDrill))
        assertTrue(app.perform(Shortcut.PAUSE_DRILL))
        showApp()
        compose.onNodeWithTag("drill-button-Resume").assertIsEnabled()

        // Not F6/perform: that also navigates to Setup, and this drill's card only shows on Range
        assertTrue(app.startCalibration())
        compose.waitForIdle()

        assertTrue(app.calibration.value!!.state.value.calibrating)
        compose.onNodeWithTag("drill-button-Resume").assertIsNotEnabled()
    }

    @Test
    fun theNotReadyPromptOffersSetupAndSkipAndComesBackWhenTheArenaCloses() {
        app.openStartCamera()
        showApp()

        compose.onNodeWithTag("not-ready").assertExists()
        compose.onNodeWithTag("prompt-skip").performClick()
        compose.onNodeWithTag("not-ready").assertDoesNotExist()

        app.openArena()
        app.closeArena()
        compose.waitForIdle()
        compose.onNodeWithTag("not-ready").assertExists()

        compose.onNodeWithTag("prompt-setup").performClick()
        assertEquals(Destination.SETUP, app.destination.value)
    }
}
