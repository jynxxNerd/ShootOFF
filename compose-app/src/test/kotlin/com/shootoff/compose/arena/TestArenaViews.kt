package com.shootoff.compose.arena

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.shootoff.compose.shots.RegionCommandRunner
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.util.Optional

class TestArenaViews {
    @get:Rule
    val compose = createComposeRule()

    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    private lateinit var arena: ArenaModel

    @Before
    fun setUp() {
        arena = ArenaModel(settings, { ShotReceiver.None }, { RegionCommandRunner(arena.targets, settings, {}, { null }) }, ManualClock())
        arena.setSize(Size(1280.0, 720.0))
    }

    // The projector window's view (640x360) and the in-app view (320x180) of the one arena
    private fun showBothViews() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Column {
                    ArenaCanvas(arena, Modifier.size(640.dp, 360.dp).testTag("projector"))
                    ArenaView(arena, Modifier.size(320.dp, 180.dp).testTag("in-app"))
                }
            }
        }
    }

    private fun pixels(tag: String): PixelMap = compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    @Test
    fun bothViewsDrawTheOneArenasTargets() {
        val target = arena.targets.add(
            TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 80.0, 80.0, "red", mapOf("opacity" to "1")))),
            ResourceResolver.files(),
            Placement(600.0, 300.0, 1.0, 1.0, true),
        )
        arena.setCalibrationLabelVisible(false)
        showBothViews()

        // The target's center (640, 340) at each view's scale
        assertEquals(Color.Red, pixels("projector")[320, 170])
        assertEquals(Color.Red, pixels("in-app")[160, 85])

        arena.targets.set.setVisible(target.id, false)
        compose.waitForIdle()

        assertEquals(Color(0xFF333333), pixels("projector")[320, 170])
        assertEquals(Color(0xFF333333), pixels("in-app")[160, 85])
    }

    @Test
    fun theBackgroundIsStretchedOverTheWholeArena() {
        val blue = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB).apply {
            for (x in 0 until 10) for (y in 0 until 10) setRGB(x, y, 0x0000FF)
        }
        arena.setBackground(ArenaBackground(blue.toComposeImageBitmap(), "blue"))
        arena.setCalibrationLabelVisible(false)
        showBothViews()

        val pixels = pixels("projector")
        assertEquals(Color.Blue, pixels[2, 2])
        assertEquals(Color.Blue, pixels[637, 357])
    }

    @Test
    fun theArenaSaysItNeedsCalibrationUntilItIsCalibrated() {
        showBothViews()
        compose.onAllNodesWithTag("needs-calibration").assertCountEquals(2)

        arena.setCalibrationLabelVisible(false)
        compose.waitForIdle()
        compose.onAllNodesWithTag("needs-calibration").assertCountEquals(0)
    }

    @Test
    fun whileCoveredOnlyTheBackgroundShowsAndEverythingComesBackAsItWas() {
        val target = arena.targets.add(
            TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 80.0, 80.0, "red", mapOf("opacity" to "1")))),
            ResourceResolver.files(),
            Placement(600.0, 300.0, 1.0, 1.0, true),
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ArenaCanvas(arena, Modifier.size(640.dp, 360.dp).testTag("projector")) {
                    Text("Score: 3", Modifier.testTag("drill-text"))
                }
            }
        }
        compose.onNodeWithTag("drill-text").assertExists()

        arena.cover(true)
        compose.waitForIdle()

        // The pattern (the background) alone: no target, no label, none of the drill's texts
        assertEquals(Color(0xFF333333), pixels("projector")[320, 170])
        compose.onAllNodesWithTag("needs-calibration").assertCountEquals(0)
        compose.onAllNodesWithTag("drill-text").assertCountEquals(0)
        assertTrue(arena.targets.set.get(target.id).get().isVisible)

        arena.cover(false)
        compose.waitForIdle()

        assertEquals(Color.Red, pixels("projector")[320, 170])
        compose.onAllNodesWithTag("needs-calibration").assertCountEquals(1)
        compose.onNodeWithTag("drill-text").assertExists()
    }
}
