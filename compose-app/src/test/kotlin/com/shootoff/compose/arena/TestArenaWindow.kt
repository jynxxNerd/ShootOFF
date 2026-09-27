package com.shootoff.compose.arena

import com.shootoff.geom.Size
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestArenaWindow {
    private val projector = Size(1280.0, 720.0)

    @Test
    fun theArenaIsFullScreenOnlyOnceItFillsItsScreen() {
        // Asked for, but the window manager hasn't resized the window yet
        assertFalse(fillsItsScreen(true, Size(640.0, 480.0), projector))
        assertTrue(fillsItsScreen(true, Size(1280.0, 720.0), projector))
        // Rounding in the window's size in dp
        assertTrue(fillsItsScreen(true, Size(1279.5, 720.0), projector))
        // A window as big as the screen, but not asked to be full screen (a maximized one)
        assertFalse(fillsItsScreen(false, Size(1280.0, 720.0), projector))
    }
}
