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
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.config.SavedCalibration
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import java.util.Optional
import com.shootoff.compose.targets.ManualClock
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

    private val clock = ManualClock()
    private var app = AppFixture.appWithCamera(clock = clock)

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
    fun resetStandsFallenTargetsBackUp() {
        app.openStartCamera()
        showApp()
        val key = AppFixture.fallenPopper(app.feedTargets, clock)

        compose.onNodeWithTag("range-reset").assertExists().performClick()

        assertTrue(app.feedTargets.animations.isOnFirstFrame(key))
    }

    @Test
    fun theChipSaysWhatIsMissingAndOpensSetup() {
        app.openStartCamera()
        showApp()
        compose.onNodeWithTag("status-chip").assert(hasText("No arena"))

        compose.onNodeWithTag("status-chip").performClick()
        assertEquals(Destination.SETUP, app.destination.value)
    }

    // The owner's ask (Plan 7's hardware check): once all is set up the status strip at the bottom already says
    // it, so the chip goes; it comes back as soon as something needs attention or is under way
    @Test
    fun theChipIsHiddenOnceTheRangeIsReadyAndBackWhileCalibrating() {
        AppFixture.setUpForProjectorDrills(app)
        showApp()
        compose.onNodeWithTag("status-chip").assertDoesNotExist()

        assertTrue(app.startCalibration())
        compose.waitForIdle()
        compose.onNodeWithTag("status-chip").assert(hasText("Calibrating…"))
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
        awaitTrue { app.drill.buttons.value.any { it.label == "Pause" } }
        assertTrue(app.perform(Shortcut.PAUSE_DRILL))
        showApp()
        compose.onNodeWithTag("drill-button-Resume").assertIsEnabled()

        // Not F6/perform: that also navigates to Setup, and this drill's card only shows on Range
        assertTrue(app.startCalibration())
        compose.waitForIdle()

        assertTrue(app.calibration.value!!.state.value.calibrating)
        compose.onNodeWithTag("drill-button-Resume").assertIsNotEnabled()
    }

    // Task 3 review ruling: RangeControls' pauseEnabled = !calibrating && !check.showsPattern also guards
    // the drill card's Resume while a check's or an automatic calibration's pattern shows, not just while
    // calibrating; the test above only drives the calibrating half of that.
    @Test
    fun theResumeButtonIsDisabledWhileAnAutomaticCalibrationWaitsForTheProjector() {
        app.openStartCamera()
        assertTrue(app.startDrill(AppFixture.pausingFeedDrill))
        awaitTrue { app.drill.buttons.value.any { it.label == "Pause" } }
        assertTrue(app.perform(Shortcut.PAUSE_DRILL))

        app.setRememberCalibration(true)
        app.openArena()
        assertEquals(CheckState.WaitingToCalibrate, app.check.value)
        showApp()

        compose.onNodeWithTag("drill-button-Resume").assertIsNotEnabled()
    }

    @Test
    fun theResumeButtonIsDisabledWhileTheSavedCalibrationIsBeingChecked() {
        app.close()
        val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
        settings.setRememberCalibration(true)
        settings.setSavedCalibration(
            SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), true),
        )
        app = AppFixture.appWithCamera(settings)
        app.openStartCamera()
        assertTrue(app.startDrill(AppFixture.pausingFeedDrill))
        awaitTrue { app.drill.buttons.value.any { it.label == "Pause" } }
        assertTrue(app.perform(Shortcut.PAUSE_DRILL))

        app.openArena()
        AppFixture.putOnTheProjector(app)
        awaitTrue { app.check.value == CheckState.Checking }
        showApp()

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

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}
