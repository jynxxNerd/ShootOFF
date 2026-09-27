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

package com.shootoff.compose.calibration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.theme.Range
import com.shootoff.geom.Rect

/** The calibrated projection, outlined in orange over the camera feed it was found on. */
@Composable
fun ProjectionOutline(projection: Rect, transform: SurfaceTransform, modifier: Modifier = Modifier) {
    val color = Range.colors.accent
    Canvas(modifier.fillMaxSize().testTag("projection-outline")) {
        val topLeft = transform.toView(projection.minX, projection.minY)
        val size = Size((projection.width * transform.scale).toFloat(), (projection.height * transform.scale).toFloat())
        drawRect(color, topLeft, size, style = Stroke(2.dp.toPx()))
    }
}
