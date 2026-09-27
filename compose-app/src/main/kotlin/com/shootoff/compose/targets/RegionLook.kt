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

package com.shootoff.compose.targets

import androidx.compose.ui.graphics.Color
import com.shootoff.geom.Point
import com.shootoff.targets.model.EllipseRegion
import com.shootoff.targets.model.ImageRegion
import com.shootoff.targets.model.PolygonRegion
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.Region

/** Shapes without an opacity tag are drawn half transparent, as in the JavaFX app. */
const val DEFAULT_OPACITY = 0.5f

private val NAMED_FILLS = mapOf(
    "black" to Color(0xFF000000),
    "blue" to Color(0xFF0000FF),
    // JavaFX's SADDLEBROWN
    "brown" to Color(0xFF8B4513),
    "gray" to Color(0xFF505050),
    "green" to Color(0xFF008000),
    "orange" to Color(0xFFFFA500),
    "red" to Color(0xFFFF0000),
    "white" to Color(0xFFFFFFFF),
)

// JavaFX's CORNSILK, for fills the JavaFX app doesn't know either
private val UNKNOWN_FILL = Color(0xFFFFF8DC)

/**
 * A shape's fill as the JavaFX app draws it: the eight names of the target editor, or a "#rgb",
 * "#rrggbb" or "#rrggbbaa" hex code, as JavaFX's Color.web parses one.
 */
fun regionFill(name: String): Color {
    NAMED_FILLS[name]?.let { return it }
    parseHexColor(name)?.let { return it }
    return UNKNOWN_FILL
}

// "#rgb" (each digit doubled, opaque), "#rrggbb" (opaque) or "#rrggbbaa" (the trailing byte is
// alpha). Anything else, including an unparseable hex code, falls through to the caller's default.
private fun parseHexColor(name: String): Color? {
    if (!name.startsWith("#")) return null
    val hex = name.substring(1)
    val rgba = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("") + "ff"
        6 -> hex + "ff"
        8 -> hex
        else -> return null
    }
    val value = rgba.toLongOrNull(16) ?: return null
    val rgb = value shr 8
    val alpha = value and 0xFF
    return Color((alpha shl 24) or rgb)
}

/** A region's opacity: its opacity tag, else [DEFAULT_OPACITY]; images are opaque. */
fun regionOpacity(region: Region): Float {
    if (region is ImageRegion) return 1f
    return region.tag(Region.TAG_OPACITY).map { it.toFloatOrNull() ?: DEFAULT_OPACITY }.orElse(DEFAULT_OPACITY)
}

/** The center of a region's bounds in target coordinates, which an unresizable region keeps its size about. */
fun regionCenter(region: Region): Point = when (region) {
    is EllipseRegion -> Point(region.centerX(), region.centerY())
    is RectangleRegion -> Point(region.x() + region.width() / 2, region.y() + region.height() / 2)
    is ImageRegion -> Point(region.x() + region.imageWidth() / 2.0, region.y() + region.imageHeight() / 2.0)
    is PolygonRegion -> {
        val xs = region.points().map { it.x }
        val ys = region.points().map { it.y }
        Point((xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2)
    }
}
