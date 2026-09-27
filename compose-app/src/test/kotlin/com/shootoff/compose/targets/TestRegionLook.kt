package com.shootoff.compose.targets

import androidx.compose.ui.graphics.Color
import com.shootoff.geom.Point
import com.shootoff.targets.model.EllipseRegion
import com.shootoff.targets.model.ImageRegion
import com.shootoff.targets.model.PolygonRegion
import com.shootoff.targets.model.RectangleRegion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestRegionLook {
    @Test
    fun fillsAreTheJavaFxAppsColors() {
        assertEquals(Color(0xFF000000), regionFill("black"))
        assertEquals(Color(0xFF8B4513), regionFill("brown"))
        assertEquals(Color(0xFF505050), regionFill("gray"))
        assertEquals(Color(0xFFFFA500), regionFill("orange"))
        assertEquals(Color(0xFF12AB34), regionFill("#12ab34"))
        // Anything else is cornsilk, as in the JavaFX app
        assertEquals(Color(0xFFFFF8DC), regionFill("purple"))
        assertEquals(Color(0xFFFFF8DC), regionFill("#zzzzzz"))
    }

    @Test
    fun shapesAreHalfTransparentUnlessTaggedAndImagesAreOpaque() {
        assertEquals(0.5f, regionOpacity(RectangleRegion(0, 0.0, 0.0, 10.0, 10.0, "red", mapOf())))
        assertEquals(1f, regionOpacity(EllipseRegion(0, 5.0, 5.0, 5.0, 5.0, "white", mapOf("opacity" to "1"))))
        assertEquals(1f, regionOpacity(ImageRegion(0, 0.0, 0.0, "targets/IPSC.png", 10, 10, mapOf("opacity" to "0.2"))))
    }

    @Test
    fun centersAreTheMiddleOfEachRegionsBounds() {
        assertEquals(Point(15.0, 30.0), regionCenter(RectangleRegion(0, 10.0, 20.0, 10.0, 20.0, "red", mapOf())))
        assertEquals(Point(5.0, 6.0), regionCenter(EllipseRegion(0, 5.0, 6.0, 3.0, 4.0, "red", mapOf())))
        assertEquals(Point(2.0, 3.0), regionCenter(PolygonRegion(0, listOf(Point(0.0, 0.0), Point(4.0, 1.0), Point(1.0, 6.0)), "red", mapOf())))
    }
}
