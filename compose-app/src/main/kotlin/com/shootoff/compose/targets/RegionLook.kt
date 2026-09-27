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
 * A shape's fill as the JavaFX app draws it: the eight names of the target editor, or a "#rrggbb" code.
 */
fun regionFill(name: String): Color {
    NAMED_FILLS[name]?.let { return it }
    if (name.startsWith("#") && name.length == 7) {
        name.substring(1).toLongOrNull(16)?.let { return Color(0xFF000000 or it) }
    }
    return UNKNOWN_FILL
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
