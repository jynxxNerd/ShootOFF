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

package com.shootoff.compose.drill

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import com.shootoff.compose.shots.MarkerLayer
import com.shootoff.compose.surface.SurfaceTransform
import kotlin.math.roundToInt

/**
 * The running exercise's texts and markers on its surface, at the surface's scale: a text's top left
 * is at its (x, y), in its font size and colors.
 *
 * @param showMarkers false to draw the texts only
 */
@Composable
fun ExerciseOverlay(drill: DrillState, transform: SurfaceTransform, modifier: Modifier = Modifier, showMarkers: Boolean = true) {
    val texts by drill.texts.collectAsState()
    val density = LocalDensity.current
    Box(modifier.fillMaxSize()) {
        if (showMarkers) MarkerLayer(drill.markers, transform)
        for (text in texts) {
            val topLeft = transform.toView(text.x, text.y)
            Text(
                text.text,
                color = webColor(text.style.textColor()),
                fontSize = with(density) { (text.style.fontSize() * transform.scale).toFloat().toSp() },
                lineHeight = with(density) { (text.style.fontSize() * transform.scale * 1.2).toFloat().toSp() },
                modifier = Modifier
                    .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                    .background(webColor(text.style.backgroundColor()))
                    .testTag("exercise-text-${text.id}"),
            )
        }
    }
}

/**
 * What the running exercise draws on the arena. A projector exercise: its texts and markers. A camera
 * exercise that runs everywhere (exercise port spec §5): its texts, at the same (x, y) as on the camera
 * feed, and its banner message at the top left, white, as the JavaFX app showed it; its markers stay on the
 * camera feed, whose coordinates they are in.
 */
@Composable
fun ArenaExerciseOverlay(drill: DrillState, projector: Boolean, everywhere: Boolean, transform: SurfaceTransform) {
    when {
        projector -> ExerciseOverlay(drill, transform)
        everywhere -> {
            ExerciseOverlay(drill, transform, showMarkers = false)
            ArenaMessage(drill, transform)
        }
    }
}

@Composable
private fun ArenaMessage(drill: DrillState, transform: SurfaceTransform) {
    val message by drill.message.collectAsState()
    val density = LocalDensity.current
    val shown = message?.takeIf { it.isNotEmpty() } ?: return
    val topLeft = transform.toView(MESSAGE_INSET, MESSAGE_INSET)
    Text(
        shown,
        color = Color.White,
        fontSize = with(density) { (MESSAGE_FONT_SIZE * transform.scale).toFloat().toSp() },
        lineHeight = with(density) { (MESSAGE_FONT_SIZE * transform.scale * 1.2).toFloat().toSp() },
        modifier = Modifier
            .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
            .testTag("arena-exercise-message"),
    )
}

// In arena coordinates
private const val MESSAGE_INSET = 10.0
private const val MESSAGE_FONT_SIZE = 24.0
