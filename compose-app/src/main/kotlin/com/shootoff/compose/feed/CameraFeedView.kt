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

package com.shootoff.compose.feed

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.theme.Range
import kotlin.math.roundToInt

/**
 * A camera feed, fitted to the space it has, with [overlay] drawn over it in the same coordinates
 * (targets, shot markers, the calibration box).
 */
@Composable
fun CameraFeedView(
    feed: FeedState,
    modifier: Modifier = Modifier,
    overlay: @Composable (SurfaceTransform) -> Unit = {},
) {
    val frame by feed.frame.collectAsState()
    val colors = Range.colors
    BoxWithConstraints(
        modifier.background(Brush.radialGradient(listOf(colors.feedCenter, colors.feedEdge))).testTag("camera-feed"),
    ) {
        val transform = SurfaceTransform.fit(feed.displaySize, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        Canvas(Modifier.fillMaxSize()) {
            val shown = frame ?: return@Canvas
            val topLeft = transform.toView(shown.bounds.minX, shown.bounds.minY)
            drawImage(
                shown.image,
                dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                dstSize = IntSize(
                    (shown.bounds.width * transform.scale).roundToInt(),
                    (shown.bounds.height * transform.scale).roundToInt(),
                ),
            )
        }
        overlay(transform)
    }
}
