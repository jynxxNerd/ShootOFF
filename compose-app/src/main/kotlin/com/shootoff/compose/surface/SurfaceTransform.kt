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

package com.shootoff.compose.surface

import androidx.compose.ui.geometry.Offset
import com.shootoff.geom.Point
import com.shootoff.geom.Size

/**
 * How a surface (a camera feed's canvas, the arena) is drawn in a view: scaled to fit, keeping its
 * aspect ratio, and centered. Surfaces are in their own units (the feed canvas's display size, the arena
 * window's size in dp); views are in pixels.
 */
data class SurfaceTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun toView(x: Double, y: Double): Offset = Offset(offsetX + x.toFloat() * scale, offsetY + y.toFloat() * scale)

    fun toSurface(view: Offset): Point = Point(((view.x - offsetX) / scale).toDouble(), ((view.y - offsetY) / scale).toDouble())

    companion object {
        fun fit(surface: Size, viewWidth: Float, viewHeight: Float): SurfaceTransform {
            if (surface.width <= 0 || surface.height <= 0 || viewWidth <= 0 || viewHeight <= 0) {
                return SurfaceTransform(1f, 0f, 0f)
            }
            val scale = minOf(viewWidth / surface.width.toFloat(), viewHeight / surface.height.toFloat())
            return SurfaceTransform(
                scale,
                (viewWidth - surface.width.toFloat() * scale) / 2f,
                (viewHeight - surface.height.toFloat() * scale) / 2f,
            )
        }
    }
}
