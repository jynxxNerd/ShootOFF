package com.shootoff.compose.arena

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.shootoff.compose.shots.RegionCommandRunner
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Size
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TestArenaGrid {
    @get:Rule
    val compose = createComposeRule()

    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    private val arena: ArenaModel = ArenaModel(settings, { ShotReceiver.None }, { RegionCommandRunner(arena.targets, settings, {}, { null }) }, ManualClock())
        .also { it.setSize(Size(640.0, 480.0)) }

    private fun pixels(): PixelMap = compose.onNodeWithTag("arena").captureToImage().toPixelMap()

    @Test
    fun theGridCoversTheArenaWithLinesCornersAndCenterUntilItIsTurnedOff() {
        arena.setCalibrationLabelVisible(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ArenaCanvas(arena, Modifier.size(640.dp, 480.dp).testTag("arena"))
            }
        }

        arena.showGrid(true)
        compose.waitForIdle()
        var pixels = pixels()
        // A tenth of the way across and down: a line; between lines: black
        assertEquals(Color.White, pixels[64, 100])
        assertEquals(Color.White, pixels[100, 48])
        assertEquals(Color.Black, pixels[32, 24])
        // The top left corner's mark and the center's
        assertEquals(Color(0xFFF5A807), pixels[20, 1])
        assertEquals(Color(0xFFF5A807), pixels[320, 240])

        arena.showGrid(false)
        compose.waitForIdle()
        pixels = pixels()
        assertEquals(Color(0xFF333333), pixels[64, 100])
    }
}
