/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.compose.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * The Range look: slate with an orange accent and high-contrast numbers. [RangeDark] is the owner's
 * pick (the design session's theme.html option B); [RangeLight] is its light variant.
 */
@Immutable
data class RangeColors(
    val background: Color,
    val rail: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val card: Color,
    val cardBorder: Color,
    val highlightCard: Color,
    val highlightBorder: Color,
    val bigNumber: Color,
    val text: Color,
    val muted: Color,
    val mutedStrong: Color,
    val chipBorder: Color,
    val good: Color,
    val warning: Color,
    val error: Color,
    val feedCenter: Color,
    val feedEdge: Color,
    val isDark: Boolean,
)

val RangeDark = RangeColors(
    background = Color(0xFF12161A),
    rail = Color(0xFF171C21),
    accent = Color(0xFFF08A4B),
    onAccent = Color(0xFF1A0D05),
    accentSoft = Color(0xFF3A261B),
    onAccentSoft = Color(0xFFF5B38B),
    card = Color(0xFF1A2026),
    cardBorder = Color(0xFF242C33),
    highlightCard = Color(0xFF241A14),
    highlightBorder = Color(0xFF5A3520),
    bigNumber = Color(0xFFF5A26B),
    text = Color(0xFFE8ECEF),
    muted = Color(0xFF8D99A3),
    mutedStrong = Color(0xFFAAB5BD),
    chipBorder = Color(0xFF2C343B),
    good = Color(0xFF6CC592),
    warning = Color(0xFFE5C07B),
    error = Color(0xFFEF6F6C),
    feedCenter = Color(0xFF2A3036),
    feedEdge = Color(0xFF0B0D0F),
    isDark = true,
)

// The same slate and orange on a light ground: the accent is darkened so text on it and next to it
// keeps its contrast on white
val RangeLight = RangeColors(
    background = Color(0xFFF3F5F7),
    rail = Color(0xFFE6EBEF),
    accent = Color(0xFFB85518),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFFBE2D2),
    onAccentSoft = Color(0xFF7A3510),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFD4DBE1),
    highlightCard = Color(0xFFFFF1E6),
    highlightBorder = Color(0xFFF0B58E),
    bigNumber = Color(0xFFB4501A),
    text = Color(0xFF1B2126),
    muted = Color(0xFF5C6873),
    mutedStrong = Color(0xFF45515B),
    chipBorder = Color(0xFFC7CFD6),
    good = Color(0xFF2E8B57),
    warning = Color(0xFF9A6B00),
    error = Color(0xFFC0392B),
    feedCenter = Color(0xFF2A3036),
    feedEdge = Color(0xFF0B0D0F),
    isDark = false,
)

val LocalRangeColors = staticCompositionLocalOf { RangeDark }

/** Numbers (times, scores, FPS) are set in a monospaced face, as in the mockups. */
val NumberStyle = TextStyle(fontFamily = FontFamily.Monospace)

fun RangeColors.colorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = onAccentSoft,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accentSoft,
        onSecondaryContainer = onAccentSoft,
        background = background,
        onBackground = text,
        surface = background,
        onSurface = text,
        surfaceVariant = card,
        onSurfaceVariant = mutedStrong,
        surfaceContainerLowest = background,
        surfaceContainerLow = rail,
        surfaceContainer = card,
        surfaceContainerHigh = card,
        surfaceContainerHighest = cardBorder,
        inverseSurface = text,
        inverseOnSurface = background,
        outline = chipBorder,
        outlineVariant = cardBorder,
        error = error,
    )
}

@Composable
fun RangeTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) RangeDark else RangeLight
    CompositionLocalProvider(LocalRangeColors provides colors) {
        MaterialTheme(
            colorScheme = colors.colorScheme(),
            typography = Typography(),
            content = content,
        )
    }
}

object Range {
    val colors: RangeColors
        @Composable get() = LocalRangeColors.current

    val bigNumber = NumberStyle.copy(fontSize = 22.sp)
}
