package com.shootoff.compose.feed

import androidx.compose.ui.graphics.ImageBitmap
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class TestFeedState {
    private var now = 0L
    private val feed = FeedState(Size(640.0, 480.0)) { now }

    private fun frame() = FeedFrame(ImageBitmap(4, 3), Rect(0.0, 0.0, 640.0, 480.0))

    @Test
    fun onlyTheNewestFrameIsKept() {
        val first = frame()
        val second = frame()

        feed.showFrame(first)
        feed.showFrame(second)

        assertSame(second, feed.frame.value)
    }

    @Test
    fun fpsCountsTheFramesShownInTheLastSecond() {
        for (i in 0 until 30) {
            now = i * 33L
            feed.showFrame(frame())
        }
        assertEquals(30.0, feed.fps.value)

        // At 1.5 s only the frames after 0.5 s count: 14 of the first 30, and this one
        now = 1500
        feed.showFrame(frame())
        assertEquals(15.0, feed.fps.value)
    }

    @Test
    fun clearingTheFrameShowsNothingAndZeroFps() {
        feed.showFrame(frame())
        feed.clearFrame()

        assertNull(feed.frame.value)
        assertEquals(0.0, feed.fps.value)
    }

    @Test
    fun bannersComeAndGoInOrder() {
        val bright = feed.addBanner("Warning: Excessive brightness", BannerKind.WARNING)
        val calibrating = feed.addBanner("Looking for the calibration pattern", BannerKind.CALIBRATION)

        assertEquals(listOf(bright, calibrating), feed.banners.value)

        feed.removeBanner(bright)
        assertEquals(listOf(calibrating), feed.banners.value)
    }
}
