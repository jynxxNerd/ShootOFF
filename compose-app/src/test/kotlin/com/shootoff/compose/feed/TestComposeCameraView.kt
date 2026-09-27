package com.shootoff.compose.feed

import com.shootoff.camera.CameraManager
import com.shootoff.camera.MockCamera
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.util.Optional

class TestComposeCameraView {
    private val feed = FeedState(Size(640.0, 480.0))
    private val events = mutableListOf<String>()
    private lateinit var view: ComposeCameraView

    @BeforeEach
    fun setUp() {
        Settings(arrayOf())
        view = ComposeCameraView("C270", feed, object : FeedShots {
            override fun add(shot: ScaledShot) {
                events += "shot ${shot.x},${shot.y}"
            }

            override fun clear() {
                events += "clear"
            }

            override fun reset() {
                events += "reset"
            }
        })
        CameraManager(MockCamera(), null, view)
    }

    @Test
    fun aFrameFillsTheCanvas() {
        view.updateBackground(BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB), Optional.empty())

        val frame = feed.frame.value!!
        assertEquals(Rect(0.0, 0.0, 640.0, 480.0), frame.bounds)
        assertEquals(640, frame.image.width)
    }

    @Test
    fun aFrameCroppedToTheProjectionGoesWhereTheProjectionIsOnTheCanvas() {
        // A 1280x960 camera shown on the 640x480 canvas: the projection is halved
        view.cameraManager!!.setFeedResolution(1280, 960)

        view.updateBackground(BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB), Optional.of(Rect(200.0, 100.0, 400.0, 300.0)))

        assertEquals(Rect(100.0, 50.0, 200.0, 150.0), feed.frame.value!!.bounds)
    }

    @Test
    fun noFrameShowsNothing() {
        view.updateBackground(BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB), Optional.empty())
        view.updateBackground(null, Optional.empty())

        assertNull(feed.frame.value)
    }

    @Test
    fun warningsAreBannersUntilTheCameraRemovesThem() {
        val warning = view.addDiagnosticWarning("Warning: Excessive brightness")
        assertEquals(listOf("Warning: Excessive brightness"), feed.banners.value.map { it.text })

        warning.remove()
        assertEquals(emptyList<Banner>(), feed.banners.value)
    }

    @Test
    fun shotsClearsAndResetsGoToTheFeedsShots() {
        view.addShot(ScaledShot(ShotColor.RED, 10.0, 20.0, 5))
        view.clearShots()
        view.reset()

        assertEquals(listOf("shot 10.0,20.0", "clear", "reset"), events)
    }
}
