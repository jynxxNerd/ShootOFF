package com.shootoff.compose

import com.shootoff.compose.app.WindowBounds
import com.shootoff.geom.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TestMainWindowPlacement {
    private val screen = Rect(0.0, 0.0, 1920.0, 1080.0)

    @Test
    fun aSavedPositionOnAScreenThatNoLongerExistsFallsBackToTheDefault() {
        val saved = WindowBounds(5000f, 0f, 1280f, 860f)

        assertNull(placeMainWindow(saved, listOf(screen)))
    }

    @Test
    fun aPartiallyVisibleWindowIsClampedOntoAScreen() {
        val saved = WindowBounds(-100f, 50f, 1280f, 860f)

        assertEquals(WindowBounds(0f, 50f, 1280f, 860f), placeMainWindow(saved, listOf(screen)))
    }

    @Test
    fun aValidSavedPositionIsKept() {
        val saved = WindowBounds(100f, 100f, 1280f, 860f)

        assertEquals(saved, placeMainWindow(saved, listOf(screen)))
    }
}
