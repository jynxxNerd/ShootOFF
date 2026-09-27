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

package com.shootoff.compose.shots

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.surface.SurfaceTransform

/** A marker's fill, as the JavaFX app paints it */
fun markerColor(color: ShotColor): Color = when (color) {
    ShotColor.RED -> Color(0xFFFF0000)
    ShotColor.GREEN -> Color(0xFF008000)
    ShotColor.INFRARED -> Color(0xFFFFA500)
}

@Composable
fun MarkerLayer(markers: ShotMarkers, transform: SurfaceTransform, modifier: Modifier = Modifier) {
    val shown by markers.markers.collectAsState()
    val visible by markers.visible.collectAsState()
    if (!visible) return
    Canvas(modifier.fillMaxSize()) {
        for (marker in shown) {
            drawCircle(markerColor(marker.color), marker.radius * transform.scale, transform.toView(marker.x, marker.y))
        }
    }
}
