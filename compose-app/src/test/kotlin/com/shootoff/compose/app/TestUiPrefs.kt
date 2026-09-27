package com.shootoff.compose.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestUiPrefs {
    private val store = PrefsStore.Memory()

    @Test
    fun aFirstRunIsRangeDarkOnTheCameraWithTheTrayOpen() {
        val prefs = UiPrefs(store)

        assertTrue(prefs.dark)
        assertEquals(UiPrefs.DEFAULT_TRAY_HEIGHT, prefs.trayHeight)
        assertFalse(prefs.trayCollapsed)
        assertNull(prefs.window)
    }

    @Test
    fun whatTheUserLeftIsThereNextTime() {
        UiPrefs(store).apply {
            dark = false
            trayHeight = 300f
            trayCollapsed = true
            window = WindowBounds(2600f, 400f, 1330f, 910f)
        }

        val next = UiPrefs(store)
        assertFalse(next.dark)
        assertEquals(300f, next.trayHeight)
        assertTrue(next.trayCollapsed)
        assertEquals(WindowBounds(2600f, 400f, 1330f, 910f), next.window)
    }

    @Test
    fun unreadableValuesFallBackToTheDefaults() {
        store.put("tray.height", "tall")
        store.put("window", "1,2,3")

        val prefs = UiPrefs(store)
        assertEquals(UiPrefs.DEFAULT_TRAY_HEIGHT, prefs.trayHeight)
        assertNull(prefs.window)
    }
}
