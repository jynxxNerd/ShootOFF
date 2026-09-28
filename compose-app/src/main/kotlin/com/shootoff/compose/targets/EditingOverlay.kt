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
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.theme.Range
import kotlin.math.roundToInt

/** A resize handle's size, in dp */
private const val HANDLE_DP = 12

/**
 * The Targets screen's editing layer over a surface's view: it outlines the selected target with a handle
 * at each corner, dims an exercise's targets and badges them, and turns the mouse and keys into
 * [TargetEditor] gestures. A click selects (or, on empty space, deselects); dragging a target moves it;
 * dragging a handle resizes it, keeping its shape with Ctrl held. With the layer focused and a target
 * selected, arrows move it by one unit, Shift+arrows resize it, Delete removes it and Esc deselects it.
 */
@Composable
fun EditingOverlay(targets: SurfaceTargets, editor: TargetEditor, transform: SurfaceTransform, modifier: Modifier = Modifier) {
    val drawn by targets.drawn.collectAsState()
    val selected by editor.selected.collectAsState()
    val colors = Range.colors
    val focus = remember { FocusRequester() }

    Box(
        modifier
            .fillMaxSize()
            .testTag("editing-surface")
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event -> event.type == KeyEventType.KeyDown && editKey(editor, event) }
            .pointerInput(transform) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    focus.requestFocus()
                    editor.click(transform.toSurface(down.position))
                    if (!editor.startDrag()) return@awaitEachGesture
                    down.consume()
                    followDrag(down.id, transform) { dx, dy -> editor.dragMove(dx, dy) }
                    editor.endDrag()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            for (target in drawn) {
                if (!target.placement.visible()) continue
                val bounds = target.bounds
                val topLeft = transform.toView(bounds.minX, bounds.minY)
                val size = Size((bounds.width * transform.scale).toFloat(), (bounds.height * transform.scale).toFloat())
                if (target.owner == TargetOwner.EXERCISE) drawRect(Color.Black.copy(alpha = 0.55f), topLeft, size)
                if (target.id == selected) drawRect(colors.accent, topLeft, size, style = Stroke(2.dp.toPx()))
            }
        }
        for (target in drawn) {
            if (!target.placement.visible()) continue
            val bounds = target.bounds
            if (target.owner == TargetOwner.EXERCISE) {
                val topLeft = transform.toView(bounds.minX, bounds.minY)
                Text(
                    "exercise",
                    color = colors.text,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                        .background(colors.card.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp)
                        .testTag("exercise-badge"),
                )
            }
            if (target.id == selected) {
                for (corner in Corner.entries) {
                    key(corner) {
                        val at = transform.toView(if (corner.left) bounds.minX else bounds.maxX, if (corner.top) bounds.minY else bounds.maxY)
                        Box(
                            Modifier
                                .offset { IntOffset(at.x.roundToInt() - (HANDLE_DP.dp.toPx() / 2).roundToInt(), at.y.roundToInt() - (HANDLE_DP.dp.toPx() / 2).roundToInt()) }
                                .size(HANDLE_DP.dp)
                                .background(colors.accent)
                                .testTag("handle-${corner.name}")
                                .pointerInput(transform, corner) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        down.consume()
                                        focus.requestFocus()
                                        if (!editor.startDrag()) return@awaitEachGesture
                                        followDrag(down.id, transform) { dx, dy ->
                                            editor.dragResize(corner, dx, dy, keepAspect = currentEvent.keyboardModifiers.isCtrlPressed)
                                        }
                                        editor.endDrag()
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

// Follows a drag to its end, giving [moved] the pointer's whole movement since it went down, in surface units:
// a fast mouse sends several moves between two frames, and each is placed from where the drag began
private suspend fun AwaitPointerEventScope.followDrag(
    pointer: PointerId,
    transform: SurfaceTransform,
    moved: AwaitPointerEventScope.(dx: Double, dy: Double) -> Unit,
) {
    var total = Offset.Zero
    drag(pointer) { change ->
        total += change.positionChange()
        change.consume()
        moved((total.x / transform.scale).toDouble(), (total.y / transform.scale).toDouble())
    }
}

/**
 * A key on the editing layer: arrows move the selected target, Shift+arrows resize it, Delete (or
 * Backspace) removes it and Esc deselects it.
 *
 * @return false if the key isn't one of these, or nothing is selected
 */
fun editKey(editor: TargetEditor, event: KeyEvent): Boolean {
    if (editor.selected.value == null) return false
    val arrow = when (event.key) {
        Key.DirectionLeft -> Arrow.LEFT
        Key.DirectionRight -> Arrow.RIGHT
        Key.DirectionUp -> Arrow.UP
        Key.DirectionDown -> Arrow.DOWN
        else -> null
    }
    when {
        arrow != null -> editor.nudge(arrow, resize = event.isShiftPressed)
        event.key == Key.Delete || event.key == Key.Backspace -> editor.delete()
        event.key == Key.Escape -> editor.deselect()
        else -> return false
    }
    return true
}
