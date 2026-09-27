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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.theme.Range
import com.shootoff.geom.Rect
import kotlin.math.roundToInt

// Also used by CalibrationController to clamp the box to the canvas as it moves and resizes
internal const val MIN_BOX = 20.0

/**
 * Calibration over the calibrating camera's feed: what the flow asks of the user, always with Cancel
 * (and Done for the manual box), and the box itself, which the user drags and resizes by its corners.
 */
@Composable
fun CalibrationOverlay(controller: CalibrationController, transform: SurfaceTransform, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    Box(modifier.fillMaxSize()) {
        state.box?.let { ManualBox(it, transform, controller::moveBox) }

        val message = state.message
        if (state.calibrating && message != null) {
            val colors = Range.colors
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.highlightCard,
                border = BorderStroke(1.dp, colors.highlightBorder),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp).testTag("calibration-message"),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    if (message == Message.AUTO_CALIBRATING) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp)
                    }
                    Text(message.text(), color = colors.text)
                    if (message == Message.MANUAL_REQUEST) {
                        Button(onClick = { controller.flow.stop() }, modifier = Modifier.testTag("calibration-done")) { Text("Done") }
                    }
                    FilledTonalButton(onClick = controller::cancel, modifier = Modifier.testTag("calibration-cancel")) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun ManualBox(box: Rect, transform: SurfaceTransform, onMove: (Rect) -> Unit) {
    val colors = Range.colors
    val current by rememberUpdatedState(box)
    val topLeft = transform.toView(box.minX, box.minY)
    val density = LocalDensity.current
    val width = with(density) { (box.width * transform.scale).toFloat().toDp() }
    val height = with(density) { (box.height * transform.scale).toFloat().toDp() }

    Box(
        Modifier
            .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
            .size(width, height)
            .background(colors.accent.copy(alpha = 0.18f))
            .border(2.dp, colors.accent)
            .testTag("calibration-box")
            .pointerInput(transform) {
                dragWithoutSlop { drag ->
                    val b = current
                    onMove(Rect(b.minX + drag.x / transform.scale, b.minY + drag.y / transform.scale, b.width, b.height))
                }
            },
    ) {
        // Each corner resizes the box from that corner
        for ((alignment, corner) in listOf(
            Alignment.TopStart to Corner(left = true, top = true),
            Alignment.TopEnd to Corner(left = false, top = true),
            Alignment.BottomStart to Corner(left = true, top = false),
            Alignment.BottomEnd to Corner(left = false, top = false),
        )) {
            Box(
                Modifier
                    .align(alignment)
                    .size(14.dp)
                    .background(colors.accent)
                    .testTag("calibration-corner-${corner.name}")
                    .pointerInput(transform) {
                        dragWithoutSlop { drag ->
                            onMove(corner.resize(current, (drag.x / transform.scale).toDouble(), (drag.y / transform.scale).toDouble()))
                        }
                    },
            )
        }
    }
}

// The box follows the pointer from the first pixel: a slop would leave it behind the pointer
private suspend fun PointerInputScope.dragWithoutSlop(onDrag: (Offset) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        drag(down.id) { change ->
            onDrag(change.positionChange())
            change.consume()
        }
    }
}

private data class Corner(val left: Boolean, val top: Boolean) {
    val name: String get() = (if (top) "top" else "bottom") + "-" + (if (left) "left" else "right")

    fun resize(box: Rect, dx: Double, dy: Double): Rect {
        var minX = box.minX
        var minY = box.minY
        var maxX = box.maxX
        var maxY = box.maxY
        if (left) minX = minOf(minX + dx, maxX - MIN_BOX) else maxX = maxOf(maxX + dx, minX + MIN_BOX)
        if (top) minY = minOf(minY + dy, maxY - MIN_BOX) else maxY = maxOf(maxY + dy, minY + MIN_BOX)
        return Rect(minX, minY, maxX - minX, maxY - minY)
    }
}
