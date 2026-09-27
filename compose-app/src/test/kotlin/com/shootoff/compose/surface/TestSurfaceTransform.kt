package com.shootoff.compose.surface

import androidx.compose.ui.geometry.Offset
import com.shootoff.geom.Point
import com.shootoff.geom.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestSurfaceTransform {
    @Test
    fun aWideViewLetterboxesTheSurfaceLeftAndRight() {
        val transform = SurfaceTransform.fit(Size(640.0, 480.0), 1000f, 480f)

        assertEquals(SurfaceTransform(1f, 180f, 0f), transform)
        assertEquals(Offset(180f, 0f), transform.toView(0.0, 0.0))
        assertEquals(Offset(820f, 480f), transform.toView(640.0, 480.0))
    }

    @Test
    fun viewPointsMapBackToTheSurface() {
        val transform = SurfaceTransform.fit(Size(1280.0, 720.0), 640f, 480f)

        assertEquals(0.5f, transform.scale)
        assertEquals(Point(640.0, 360.0), transform.toSurface(transform.toView(640.0, 360.0)))
    }
}
