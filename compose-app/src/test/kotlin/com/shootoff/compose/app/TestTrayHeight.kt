package com.shootoff.compose.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestTrayHeight {
    @Test
    fun aShortWindowShrinksTheTrayToLeaveTheViewARoom() {
        // 400 available, minus the 8dp handle and a 160dp minimum view leaves 232 for the tray
        assertEquals(232f, clampedTrayHeight(300f, 400f))
    }

    @Test
    fun aTallEnoughWindowLeavesTheTrayAsTheUserSetIt() {
        assertEquals(220f, clampedTrayHeight(220f, 900f))
    }
}
