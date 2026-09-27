package com.shootoff.compose.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.compose.targets.TargetLayer
import com.shootoff.compose.theme.RangeLight
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Optional

class TestPolish {
    @get:Rule
    val compose = createComposeRule()

    private val store = PrefsStore.Memory()
    private val app = AppState(
        Settings(ScratchConfig.emptyFile().path, arrayOf()),
        ExerciseCatalog(),
        CameraSource.None,
        { AppFixture.ownerScreens },
        ManualClock(),
        { it.run() },
        prefs = UiPrefs(store),
    )

    @After
    fun close() = app.close()

    private fun showApp() = compose.setContent {
        val dark by app.dark.collectAsState()
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            RangeTheme(dark = dark) { Box(Modifier.size(1000.dp, 700.dp).testTag("window")) { ShootOffApp(app) } }
        }
    }

    @Test
    fun theThemeSwitchGoesLightAndIsRemembered() {
        showApp()

        compose.onNodeWithTag("theme-switch").performClick()
        compose.waitForIdle()

        assertFalse(app.dark.value)
        assertFalse(UiPrefs(store).dark)
        // The rail's foot, below everything on it, is the light variant's rail color
        val pixels = compose.onNodeWithTag("window").captureToImage().toPixelMap()
        assertEquals(RangeLight.rail, pixels[4, 695])
    }

    @Test
    fun theTrayFoldsAndUnfolds() {
        showApp()
        compose.onNodeWithTag("shot-timer").assertExists()

        compose.onNodeWithTag("tray-fold").performClick()
        compose.onNodeWithTag("shot-timer").assertDoesNotExist()
        assertTrue(UiPrefs(store).trayCollapsed)

        compose.onNodeWithTag("tray-fold").performClick()
        compose.onNodeWithTag("shot-timer").assertExists()
    }

    @Test
    fun draggingTheTraysEdgeUpMakesItTallerWithinLimits() {
        showApp()

        compose.onNodeWithTag("tray-handle").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, -100f))
            release()
        }
        compose.waitForIdle()
        assertTrue(app.trayHeight.value > UiPrefs.DEFAULT_TRAY_HEIGHT + 50)

        app.setTrayHeight(5000f)
        assertEquals(MAX_TRAY_HEIGHT, app.trayHeight.value)
        assertEquals(MAX_TRAY_HEIGHT, UiPrefs(store).trayHeight)
    }

    @Test
    fun onAHiDpiScreenTheSurfaceFillsTheSameShareOfTheView() {
        val targets = SurfaceTargets(clock = ManualClock())
        targets.add(
            TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 40.0, 40.0, "red", mapOf("opacity" to "1")))),
            ResourceResolver.files(),
            Placement(100.0, 100.0, 1.0, 1.0, true),
        )
        // 320x240 dp at twice the density is 640x480 pixels
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                Box(Modifier.size(320.dp, 240.dp).background(Color.Black).testTag("hidpi")) {
                    TargetLayer(targets, SurfaceTransform.fit(Size(640.0, 480.0), 640f, 480f))
                }
            }
        }

        val pixels = compose.onNodeWithTag("hidpi").captureToImage().toPixelMap()
        assertEquals(640, pixels.width)
        assertEquals(Color.Red, pixels[120, 120])
    }
}
