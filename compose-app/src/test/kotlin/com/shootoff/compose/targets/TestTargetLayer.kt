package com.shootoff.compose.targets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetDefinitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.nio.file.Paths
import java.util.Optional

class TestTargetLayer {
    @get:Rule
    val compose = createComposeRule()

    private val clock = ManualClock()
    private val targets = SurfaceTargets(clock = clock)

    private fun square(fill: String, tags: Map<String, String> = mapOf("opacity" to "1")) =
        TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 40.0, 40.0, fill, tags)))

    // The surface drawn 1:1 on a black 640x480 view
    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.size(640.dp, 480.dp).background(Color.Black).testTag("surface")) {
                    TargetLayer(targets, SurfaceTransform.fit(Size(640.0, 480.0), 640f, 480f))
                }
            }
        }
    }

    private fun pixels(): PixelMap = compose.onNodeWithTag("surface").captureToImage().toPixelMap()

    @Test
    fun aShapeIsDrawnWhereItsTargetIsInItsFill() {
        targets.add(square("red"), ResourceResolver.files(), Placement(100.0, 100.0, 1.0, 1.0, true))
        show()

        val pixels = pixels()
        assertEquals(Color.Red, pixels[120, 120])
        assertEquals(Color.Black, pixels[95, 95])
    }

    @Test
    fun shapesWithoutAnOpacityTagAreHalfTransparent() {
        targets.add(square("white", mapOf()), ResourceResolver.files(), Placement(100.0, 100.0, 1.0, 1.0, true))
        show()

        val gray = pixels()[120, 120]
        assertEquals(0.5f, gray.red, 0.02f)
    }

    @Test
    fun hiddenTargetsAndHiddenRegionsAreNotDrawn() {
        val hidden = targets.add(square("red"), ResourceResolver.files(), Placement(100.0, 100.0, 1.0, 1.0, false))
        val regionHidden = targets.add(square("blue"), ResourceResolver.files(), Placement(300.0, 100.0, 1.0, 1.0, true))
        targets.set.setRegionVisible(regionHidden.id, 0, false)
        show()

        val pixels = pixels()
        assertEquals(Color.Black, pixels[120, 120])
        assertEquals(Color.Black, pixels[320, 120])

        targets.set.setVisible(hidden.id, true)
        compose.waitForIdle()
        assertEquals(Color.Red, pixels()[120, 120])
    }

    @Test
    fun aScaledTargetGrowsAboutItsCenter() {
        targets.add(square("red"), ResourceResolver.files(), Placement(100.0, 100.0, 2.0, 2.0, true))
        show()

        // 100..140 doubled about 120 is 80..160
        val pixels = pixels()
        assertEquals(Color.Red, pixels[85, 85])
        assertEquals(Color.Red, pixels[155, 155])
        assertEquals(Color.Black, pixels[75, 75])
    }

    @Test
    fun anAnimatedImageShowsItsCurrentFrame() {
        val popper = targets.add(TargetDefinitions.load(Paths.get("targets/Pepper_Popper.target")), ResourceResolver.files())
        show()
        val first = pixels()

        targets.animations.play(RegionKey(popper.id, 0))
        clock.runAll()
        compose.waitForIdle()

        // The popper has fallen: the view isn't what it was
        assertFalse(first.buffer.contentEquals(pixels().buffer))
    }
}
