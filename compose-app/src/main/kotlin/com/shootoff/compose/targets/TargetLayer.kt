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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntSize
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.targets.model.EllipseRegion
import com.shootoff.targets.model.ImageRegion
import com.shootoff.targets.model.PolygonRegion
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.Region

/**
 * Draws a surface's targets from the model: shapes in their fills and opacity, images on their current
 * animation frame, hidden targets and regions left out, each target placed and scaled as the JavaFX app
 * places it (an unresizable region keeps its size).
 */
@Composable
fun TargetLayer(targets: SurfaceTargets, transform: SurfaceTransform, modifier: Modifier = Modifier) {
    val drawn by targets.drawn.collectAsState()
    val frames by targets.animations.frames.collectAsState()
    Canvas(modifier.fillMaxSize()) {
        translate(transform.offsetX, transform.offsetY) {
            scale(transform.scale, transform.scale, pivot = Offset.Zero) {
                for (target in drawn) {
                    if (!target.placement.visible()) continue
                    drawTarget(target, targets, frames)
                }
            }
        }
    }
}

private fun DrawScope.drawTarget(target: DrawnTarget, targets: SurfaceTargets, frames: Map<RegionKey, Int>) {
    val sx = target.placement.scaleX().toFloat()
    val sy = target.placement.scaleY().toFloat()
    translate(target.origin.x.toFloat(), target.origin.y.toFloat()) {
        scale(sx, sy, pivot = Offset.Zero) {
            for (region in target.definition.regions()) {
                if (!target.regionVisible[region.index()]) continue
                if (!region.isResizable && (sx != 1f || sy != 1f)) {
                    val center = regionCenter(region)
                    scale(1 / sx, 1 / sy, pivot = Offset(center.x.toFloat(), center.y.toFloat())) {
                        drawRegion(region, target, targets, frames)
                    }
                } else {
                    drawRegion(region, target, targets, frames)
                }
            }
        }
    }
}

private fun DrawScope.drawRegion(region: Region, target: DrawnTarget, targets: SurfaceTargets, frames: Map<RegionKey, Int>) {
    val alpha = regionOpacity(region)
    when (region) {
        is EllipseRegion -> drawOval(
            regionFill(region.fill()),
            topLeft = Offset((region.centerX() - region.radiusX()).toFloat(), (region.centerY() - region.radiusY()).toFloat()),
            size = Size((2 * region.radiusX()).toFloat(), (2 * region.radiusY()).toFloat()),
            alpha = alpha,
        )
        is RectangleRegion -> drawRect(
            regionFill(region.fill()),
            topLeft = Offset(region.x().toFloat(), region.y().toFloat()),
            size = Size(region.width().toFloat(), region.height().toFloat()),
            alpha = alpha,
        )
        is PolygonRegion -> {
            val path = Path()
            region.points().forEachIndexed { i, point ->
                if (i == 0) path.moveTo(point.x.toFloat(), point.y.toFloat()) else path.lineTo(point.x.toFloat(), point.y.toFloat())
            }
            path.close()
            drawPath(path, regionFill(region.fill()), alpha = alpha)
        }
        is ImageRegion -> {
            val image = targets.image(target.id, region.index()) ?: return
            val frame = frames[RegionKey(target.id, region.index())] ?: 0
            translate(region.x().toFloat(), region.y().toFloat()) {
                drawImage(
                    image.frames[frame.coerceIn(0, image.frames.size - 1)],
                    dstSize = IntSize(region.imageWidth(), region.imageHeight()),
                )
            }
        }
    }
}
