package com.shootoff.compose.arena

import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestArenaScreens {
    // AWT's order on the owner's desk: DP-1 (ShootOFF), DP-2, then the projector on HDMI-1
    private val owner = listOf(Rect(1920.0, 0.0, 2560.0, 1440.0), Rect(0.0, 0.0, 1920.0, 1080.0), Rect(4480.0, 0.0, 1280.0, 720.0))

    @Test
    fun theOwnersProjectorGetsTheArenaTenPixelsIn() {
        assertEquals(ArenaPlacement(owner[2], Point(4490.0, 10.0)), ArenaScreens.place(owner, 0, null))
    }

    @Test
    fun aSavedPositionOnAnotherScreenIsUsedAsIs() {
        assertEquals(ArenaPlacement(owner[1], Point(100.0, 200.0)), ArenaScreens.place(owner, 0, Point(100.0, 200.0)))
    }

    @Test
    fun withOneScreenTheArenaOpensInAWindowOnIt() {
        assertEquals(ArenaPlacement(null, Point(10.0, 10.0)), ArenaScreens.place(listOf(Rect(0.0, 0.0, 1920.0, 1080.0)), 0, null))
    }

    @Test
    fun theMainWindowsScreenIsTheOneItsCornerIsOn() {
        assertEquals(0, ArenaScreens.screenAt(owner, Point(2000.0, 50.0)))
        assertEquals(2, ArenaScreens.screenAt(owner, Point(4500.0, 50.0)))
        assertEquals(0, ArenaScreens.screenAt(owner, Point(-500.0, 50.0)))
    }
}
