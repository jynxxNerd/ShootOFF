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
import kotlin.math.roundToInt

private val ARENA_GRAY = Color(0xFF333333)
private val CALIBRATION_ORANGE = Color(0xFFF5A807)

/**
 * The arena as both of its views draw it, fitted to the space it is given: the background stretched over
 * the arena, its targets and shot markers, the "Needs calibration" label, then [overlay] (the exercise's
 * texts and markers) in arena coordinates.
 */
@Composable
fun ArenaCanvas(arena: ArenaModel, modifier: Modifier = Modifier, overlay: @Composable (SurfaceTransform) -> Unit = {}) {
    val size by arena.size.collectAsState()
    val background by arena.background.collectAsState()
    val label by arena.needsCalibrationLabel.collectAsState()
    val density = LocalDensity.current

    BoxWithConstraints(modifier.background(Color.Black)) {
        val transform = SurfaceTransform.fit(size, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        // The arena's own area: gray until it has a background
        Canvas(Modifier.fillMaxSize().testTag("arena-canvas")) {
            val topLeft = transform.toView(0.0, 0.0)
            val areaSize = Size((size.width * transform.scale).toFloat(), (size.height * transform.scale).toFloat())
            drawRect(ARENA_GRAY, topLeft, areaSize)
            val image = background?.image ?: return@Canvas
            drawImage(
                image,
                dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                dstSize = IntSize(areaSize.width.roundToInt(), areaSize.height.roundToInt()),
            )
        }
        TargetLayer(arena.targets, transform)
        MarkerLayer(arena.markers, transform)
        if (label) {
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
        overlay(transform)
    }
}

/** The in-app Arena view: the arena fitted into the Range screen's big view. */
@Composable
fun ArenaView(arena: ArenaModel, modifier: Modifier = Modifier, overlay: @Composable (SurfaceTransform) -> Unit = {}) {
    ArenaCanvas(arena, modifier.testTag("arena-view"), overlay)
}
