package com.shootoff.compose.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestRangeTheme {
    @Test
    fun darkIsTheOwnersPickFromTheMockup() {
        assertEquals(Color(0xFF12161A), RangeDark.background)
        assertEquals(Color(0xFF171C21), RangeDark.rail)
        assertEquals(Color(0xFFF08A4B), RangeDark.accent)
        assertEquals(Color(0xFF3A261B), RangeDark.accentSoft)
        assertEquals(Color(0xFFF5B38B), RangeDark.onAccentSoft)
        assertEquals(Color(0xFF1A2026), RangeDark.card)
        assertEquals(Color(0xFF242C33), RangeDark.cardBorder)
        assertEquals(Color(0xFF241A14), RangeDark.highlightCard)
        assertEquals(Color(0xFF5A3520), RangeDark.highlightBorder)
        assertEquals(Color(0xFFF5A26B), RangeDark.bigNumber)
        assertEquals(Color(0xFF8D99A3), RangeDark.muted)
        assertEquals(Color(0xFFAAB5BD), RangeDark.mutedStrong)
        assertEquals(Color(0xFF6CC592), RangeDark.good)
    }

    @Test
    fun bothVariantsKeepTheirTextReadable() {
        for (colors in listOf(RangeDark, RangeLight)) {
            assertTrue(contrast(colors.text, colors.background) >= 7.0, "text on ${colors.isDark}")
            assertTrue(contrast(colors.onAccent, colors.accent) >= 4.5, "button text on ${colors.isDark}")
            assertTrue(contrast(colors.onAccentSoft, colors.accentSoft) >= 4.5, "selection on ${colors.isDark}")
            assertTrue(contrast(colors.bigNumber, colors.highlightCard) >= 4.5, "numbers on ${colors.isDark}")
            assertTrue(contrast(colors.muted, colors.card) >= 4.5, "muted text on ${colors.isDark}")
        }
    }

    @Test
    fun theColorSchemeCarriesTheAccent() {
        assertEquals(RangeDark.accent, RangeDark.colorScheme().primary)
        assertEquals(RangeLight.accent, RangeLight.colorScheme().primary)
        assertEquals(RangeLight.background, RangeLight.colorScheme().background)
    }

    // WCAG contrast ratio
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }
}
