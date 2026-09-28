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

package com.shootoff.compose.arena

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.shootoff.compose.shots.MarkerLayer
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.targets.TargetLayer
import com.shootoff.geom.Size as GeomSize
import kotlin.math.roundToInt

private val ARENA_GRAY = Color(0xFF333333)
private val CALIBRATION_ORANGE = Color(0xFFF5A807)

/**
 * The arena as both of its views draw it, fitted to the space it is given: the background (calibration's or
 * an exercise's, else the shooter's) stretched over the arena, its targets and shot markers, the "Needs calibration" label, then [overlay] (the exercise's
 * texts and markers) in arena coordinates. While the arena is covered (a calibration pattern showing), only
 * the background is drawn.
 */
@Composable
fun ArenaCanvas(arena: ArenaModel, modifier: Modifier = Modifier, overlay: @Composable (SurfaceTransform) -> Unit = {}) {
    val size by arena.size.collectAsState()
    val background by arena.background.collectAsState()
    val shooters by arena.layout.background.collectAsState()
    val label by arena.needsCalibrationLabel.collectAsState()
    val grid by arena.grid.collectAsState()
    val covered by arena.covered.collectAsState()
    val density = LocalDensity.current

    BoxWithConstraints(modifier.background(Color.Black)) {
        val transform = SurfaceTransform.fit(size, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        // The arena's own area: gray until it has a background
        Canvas(Modifier.fillMaxSize().testTag("arena-canvas")) {
            val topLeft = transform.toView(0.0, 0.0)
            val areaSize = Size((size.width * transform.scale).toFloat(), (size.height * transform.scale).toFloat())
            drawRect(ARENA_GRAY, topLeft, areaSize)
            // Calibration's or an exercise's own background, else the shooter's, which a calibration pattern covers
            val image = (background ?: shooters.takeUnless { covered })?.image ?: return@Canvas
            drawImage(
                image,
                dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                dstSize = IntSize(areaSize.width.roundToInt(), areaSize.height.roundToInt()),
            )
        }
        if (!covered) {
            TargetLayer(arena.targets, transform)
            MarkerLayer(arena.markers, transform)
        }
        if (label && !covered) {
            // The JavaFX arena's label: 48 px orange, centered in a 628x90 box at (6, 6)
            val topLeft = transform.toView(6.0, 6.0)
            with(density) {
                Box(
                    Modifier.offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                        .size((628 * transform.scale).toDp(), (90 * transform.scale).toDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Needs Calibration",
                        color = CALIBRATION_ORANGE,
                        style = TextStyle(fontSize = (48 * transform.scale).toSp()),
                        modifier = Modifier.testTag("needs-calibration"),
                    )
                }
            }
        }
        if (!covered) overlay(transform)
        if (grid) AlignmentGrid(size, transform)
    }
}

// How many cells the grid has across and down
private const val GRID_CELLS = 10
// The corner and center marks' arm length and the lines' width, in arena pixels
private const val MARK_LENGTH = 40.0
private const val LINE_WIDTH = 2f

/**
 * Setup's alignment grid, over everything else on the arena: black, with evenly spaced white lines, and
 * the arena's corners and center marked in orange, so its fit to the calibrated rectangle can be judged.
 */
@Composable
fun AlignmentGrid(size: GeomSize, transform: SurfaceTransform) {
    Canvas(Modifier.fillMaxSize().testTag("arena-grid")) {
        val topLeft = transform.toView(0.0, 0.0)
        val bottomRight = transform.toView(size.width, size.height)
        drawRect(Color.Black, topLeft, Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y))
        val stroke = (LINE_WIDTH * transform.scale).coerceAtLeast(1f)
        for (i in 1 until GRID_CELLS) {
            val x = transform.toView(size.width * i / GRID_CELLS, 0.0).x
            val y = transform.toView(0.0, size.height * i / GRID_CELLS).y
            drawLine(Color.White, Offset(x, topLeft.y), Offset(x, bottomRight.y), stroke)
            drawLine(Color.White, Offset(topLeft.x, y), Offset(bottomRight.x, y), stroke)
        }
        val arm = (MARK_LENGTH * transform.scale).toFloat()
        val mark = stroke * 3
        // Each corner's L, pointing into the arena
        for ((corner, direction) in listOf(
            topLeft to Offset(1f, 1f),
            Offset(bottomRight.x, topLeft.y) to Offset(-1f, 1f),
            Offset(topLeft.x, bottomRight.y) to Offset(1f, -1f),
            bottomRight to Offset(-1f, -1f),
        )) {
            drawLine(CALIBRATION_ORANGE, corner, corner + Offset(direction.x * arm, 0f), mark)
            drawLine(CALIBRATION_ORANGE, corner, corner + Offset(0f, direction.y * arm), mark)
        }
        val center = transform.toView(size.width / 2, size.height / 2)
        drawLine(CALIBRATION_ORANGE, center - Offset(arm, 0f), center + Offset(arm, 0f), mark)
        drawLine(CALIBRATION_ORANGE, center - Offset(0f, arm), center + Offset(0f, arm), mark)
    }
}

/** Setup's small live preview of the arena, fitted into the space it is given. */
@Composable
fun ArenaView(arena: ArenaModel, modifier: Modifier = Modifier, overlay: @Composable (SurfaceTransform) -> Unit = {}) {
    ArenaCanvas(arena, modifier.testTag("arena-view"), overlay)
}
