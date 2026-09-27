package com.shootoff.compose.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TestProblemViews {
    @get:Rule
    val compose = createComposeRule()

    private val source = object : CameraSource {
        override fun cameras() = listOf(AppFixture.TestCamera())

        override fun startCamera(settings: Settings) = null
    }
    private val screenLooks = AtomicInteger()
    private val app = AppState(
        Settings(ScratchConfig.emptyFile().path, arrayOf()),
        ExerciseCatalog(),
        source,
        {
            screenLooks.incrementAndGet()
            AppFixture.ownerScreens
        },
        ManualClock(),
        { it.run() },
    )

    @After
    fun close() = app.close()

    private fun show() = compose.setContent { RangeTheme(dark = true) { ShootOffApp(app) } }

    @Test
    fun withNoCameraTheFeedOffersTheCamerasToPick() {
        show()
        compose.onNodeWithTag("no-camera").assertExists()
        // The cameras are listed, and the picked one opened, off the UI thread
        compose.waitUntil(5000) { compose.onAllNodesWithTag("pick-Test camera").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("pick-Test camera").performClick()
        compose.waitUntil(5000) { app.camera.value != null }

        assertNotNull(app.camera.value)
        compose.onNodeWithTag("no-camera").assertDoesNotExist()
    }

    @Test
    fun aNoticeIsASnackbarUntilDismissed() {
        show()
        app.notices.showError("Missing Target", "Missing Required Target File", "targets/IPSC.target is missing")
        compose.onNodeWithText("targets/IPSC.target is missing").assertExists()

        compose.onNodeWithTag("dismiss-notice-${app.notices.notices.value.single().id}").performClick()

        compose.onNodeWithText("targets/IPSC.target is missing").assertDoesNotExist()
    }

    @Test
    fun aDrillThatThrowsLeavesABannerSayingWhy() {
        show()
        app.startDrill(AppFixture.feedDrill)
        val host = app.runner.running.value!!.host

        app.runner.failed(host, IllegalStateException("boom"))
        compose.waitUntil(5000) { app.runner.running.value == null }

        compose.onNodeWithText("Feed drill stopped: IllegalStateException: boom").assertExists()
        assertNull(app.runner.running.value)
    }

    @Test
    fun whileACameraOpensThePanelSaysSo() {
        show()
        val slow = TestProblems.SlowCamera()

        app.openCameraInBackground(slow)

        compose.onNodeWithTag("opening-camera").assertExists()
        compose.onNodeWithText("Opening camera Slow camera…").assertExists()
        slow.release.countDown()
        compose.waitUntil(5000) { app.camera.value != null }
        compose.onNodeWithTag("no-camera").assertDoesNotExist()
    }

    @Test
    fun theStatusLineFollowsCalibration() {
        show()
        app.openCamera(AppFixture.TestCamera())
        app.openArena()
        compose.onNodeWithTag("status-strip").assert(hasText("· calibrating", substring = true))

        // Only the calibration controller's own state changes
        app.toggleCalibration()

        compose.waitUntil(5000) { !statusStrip().contains("· calibrating") }

        // Only the arena's projection changes
        app.arena.value!!.setProjection(Rect(10.0, 10.0, 200.0, 150.0))

        compose.waitUntil(5000) { statusStrip().endsWith("· calibrated") }
    }

    private fun statusStrip(): String =
        compose.onNodeWithTag("status-strip").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    @Test
    fun theProjectorScreenIsNotLookedForOnEveryRecomposition() {
        var dark by mutableStateOf(true)
        compose.setContent { RangeTheme(dark = dark) { ShootOffApp(app) } }
        compose.waitForIdle()
        val looks = screenLooks.get()

        // Everything that reads the theme recomposes
        dark = false
        compose.waitForIdle()
        dark = true
        compose.waitForIdle()

        assertEquals(looks, screenLooks.get())
    }
}
