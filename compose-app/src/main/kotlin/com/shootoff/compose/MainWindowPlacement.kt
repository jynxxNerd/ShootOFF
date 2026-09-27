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

package com.shootoff.compose

import com.shootoff.compose.app.WindowBounds
import com.shootoff.geom.Rect

/**
 * Whether the main window's saved place and size still make sense on the screens attached now.
 * Screens can change between runs (a monitor unplugged, or a different one attached), so [saved]
 * is only trusted when it still overlaps one of [screens].
 *
 * @return [saved], clamped onto the screen it overlaps, or null if it no longer reaches any
 *   screen at all, so the caller should fall back to its own default placement
 */
fun placeMainWindow(saved: WindowBounds, screens: List<Rect>): WindowBounds? {
    val screen = screens.firstOrNull { overlaps(saved, it) } ?: return null

    val width = saved.width.coerceAtMost(screen.width.toFloat())
    val height = saved.height.coerceAtMost(screen.height.toFloat())
    val maxX = (screen.maxX.toFloat() - width).coerceAtLeast(screen.minX.toFloat())
    val maxY = (screen.maxY.toFloat() - height).coerceAtLeast(screen.minY.toFloat())
    return WindowBounds(
        saved.x.coerceIn(screen.minX.toFloat(), maxX),
        saved.y.coerceIn(screen.minY.toFloat(), maxY),
        width,
        height,
    )
}

private fun overlaps(bounds: WindowBounds, screen: Rect): Boolean {
    val maxX = bounds.x + bounds.width
    val maxY = bounds.y + bounds.height
    return bounds.x < screen.maxX && maxX > screen.minX && bounds.y < screen.maxY && maxY > screen.minY
}
