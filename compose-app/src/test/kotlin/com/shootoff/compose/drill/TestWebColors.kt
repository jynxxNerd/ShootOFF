package com.shootoff.compose.drill

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestWebColors {
    @Test
    fun namesAndCodesReadAsJavaFxReadsThem() {
        assertEquals(Color(0xFFFF7F50), webColor("coral"))
        assertEquals(Color(0xFFFF7F50), webColor(" Coral "))
        assertEquals(Color.Transparent, webColor("transparent"))
        assertEquals(Color(0xFF12AB34), webColor("#12ab34"))
        assertEquals(Color(0xFFFFAA00), webColor("#fa0"))
        assertEquals(Color(0x8012AB34), webColor("#12ab3480"))
        assertEquals(Color(0xFF808080), webColor("no such color"))
    }
}
