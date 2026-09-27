package com.shootoff.compose.feed

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage

class TestFeedViews {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theNewestFrameIsDrawnFittedToTheView() {
        val feed = FeedState(Size(640.0, 480.0))
        val red = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB).apply {
            for (x in 0 until 64) for (y in 0 until 48) setRGB(x, y, 0xFF0000)
        }
        feed.showFrame(FeedFrame(red.toComposeImageBitmap(), Rect(0.0, 0.0, 640.0, 480.0)))

        compose.setContent { RangeTheme(dark = true) { CameraFeedView(feed, Modifier.size(320.dp, 240.dp)) } }

        val pixels = compose.onNodeWithTag("camera-feed").captureToImage().toPixelMap()
        assertEquals(Color.Red, pixels[pixels.width / 2, pixels.height / 2])
    }

    @Test
    fun theStatusStripSaysCameraFpsResolutionCalibrationAndRecording() {
        val status = FeedStatus("C270", 30.0, 29.6, 640, 480, CalibrationStatus.CALIBRATED, recording = true)

        compose.setContent { RangeTheme(dark = true) { StatusStrip(status) } }

        compose.onNodeWithTag("status-strip").assertTextContains("C270 · 30 FPS · shown 30 · 640×480 · calibrated · ● REC")
    }

    @Test
    fun aBannerCanBeDismissed() {
        val feed = FeedState(Size(640.0, 480.0))
        feed.addBanner("The FPS from C270 has dropped", BannerKind.WARNING)

        compose.setContent { RangeTheme(dark = true) { FeedBanners(feed) } }
        compose.onNodeWithText("The FPS from C270 has dropped").assertExists()

        compose.onNodeWithTag("dismiss-${feed.banners.value.single().id}").performClick()
        compose.waitForIdle()

        assertEquals(0, feed.banners.value.size)
        compose.onNodeWithText("The FPS from C270 has dropped").assertDoesNotExist()
    }

    @Test
    fun aLongBannerKeepsItsCloseButtonInsideANarrowView() {
        val feed = FeedState(Size(640.0, 480.0))
        feed.addBanner("The camera is streaming frames that are very bright. ".repeat(12), BannerKind.WARNING)

        compose.setContent { RangeTheme(dark = true) { FeedBanners(feed, Modifier.width(400.dp)) } }

        val view = compose.onNodeWithTag("banner-${feed.banners.value.single().id}").getBoundsInRoot()
        val close = compose.onNodeWithTag("dismiss-${feed.banners.value.single().id}").assertIsDisplayed().getBoundsInRoot()
        assertTrue("close button ${close} must be inside ${view}", close.right <= view.right && close.left >= view.left)
        assertTrue("the banner ${view} must fit the 400dp view", view.right - view.left <= 400.dp)
    }
}
