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

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.geom.Size
import kotlinx.coroutines.delay

/** How long the arena window waits to reach its screen before going full screen there */
private const val PLACEMENT_WAIT_MILLIS = 3000L

/**
 * The projector arena's own window. It opens at [placement] and, once the window manager has put it on
 * that screen, goes full screen there (going full screen first would fill the screen it opened on).
 * F11 toggles full screen, as in the JavaFX app. Its size is the arena's size.
 */
@Composable
fun ArenaWindow(
    arena: ArenaModel,
    placement: ArenaPlacement,
    onCloseRequest: () -> Unit,
    onKey: (KeyEvent) -> Boolean = { false },
    overlay: @Composable (SurfaceTransform) -> Unit = {},
) {
    val state = rememberWindowState(
        position = WindowPosition(placement.position.x.dp, placement.position.y.dp),
        size = DpSize(640.dp, 480.dp),
    )
    Window(
        onCloseRequest = onCloseRequest,
        state = state,
        title = "Projector Arena",
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.F11) {
                state.placement = if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
                true
            } else {
                onKey(event)
            }
        },
    ) {
        LaunchedEffect(placement) {
            val screen = placement.screen ?: return@LaunchedEffect
            val waited = System.currentTimeMillis()
            while (System.currentTimeMillis() - waited < PLACEMENT_WAIT_MILLIS) {
                val on = window.graphicsConfiguration.bounds
                if (on.x.toDouble() == screen.minX && on.y.toDouble() == screen.minY) break
                delay(50)
            }
            state.placement = WindowPlacement.Fullscreen
        }
        LaunchedEffect(state) {
            snapshotFlow { state.placement == WindowPlacement.Fullscreen }.collect { arena.setFullScreen(it) }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            LaunchedEffect(maxWidth, maxHeight) { arena.setSize(Size(maxWidth.value.toDouble(), maxHeight.value.toDouble())) }
            ArenaCanvas(arena, Modifier.fillMaxSize(), overlay)
        }
    }
}
