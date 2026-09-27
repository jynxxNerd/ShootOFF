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

package com.shootoff.compose.app

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.drill.DrillSettings
import com.shootoff.compose.drill.ExerciseOverlay
import com.shootoff.compose.drill.ShotTimerTable
import com.shootoff.compose.feed.Banner
import com.shootoff.compose.feed.BannerKind
import com.shootoff.compose.feed.BannerView
import com.shootoff.compose.feed.CameraFeedView
import com.shootoff.compose.feed.FeedBanners
import com.shootoff.compose.feed.FeedStatus
import com.shootoff.compose.feed.StatusStrip
import com.shootoff.compose.shots.MarkerLayer
import com.shootoff.compose.targets.TargetLayer
import com.shootoff.compose.theme.Range
import kotlinx.coroutines.delay
import java.awt.Cursor

/** The big view never shrinks under this, however tall the tray is asked to be */
private const val MIN_VIEW_HEIGHT = 160f
private const val TRAY_HANDLE_HEIGHT = 8f

/**
 * The tray's height as drawn: [trayHeight] (what the user set, and what's saved), unless
 * [availableHeight] is too short to give the big view [MIN_VIEW_HEIGHT] dp above it. Layout only —
 * the stored preference is never touched by this.
 */
fun clampedTrayHeight(trayHeight: Float, availableHeight: Float): Float =
    trayHeight.coerceAtMost((availableHeight - TRAY_HANDLE_HEIGHT - MIN_VIEW_HEIGHT).coerceAtLeast(0f))

/**
 * The Range screen, for training only (spec §8): the camera feed with the status chip, Clear shots, the
 * drill picker and card (Start, the drill's own buttons, Stop), the not-ready prompt and the status strip
 * over it, and the tray with the shot timer and the drill's settings. Setup is where the camera, the
 * projector and calibration are set up.
 */
@Composable
fun RangeScreen(app: AppState, modifier: Modifier = Modifier) {
    val trayHeight by app.trayHeight.collectAsState()
    val collapsed by app.trayCollapsed.collectAsState()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val shownTrayHeight = clampedTrayHeight(trayHeight, maxHeight.value)
        Column(Modifier.fillMaxSize().padding(end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            FeedArea(app, Modifier.weight(1f).fillMaxWidth())
            // Drag the tray's edge to size it
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
                    .draggable(
                        orientation = Orientation.Vertical,
                        enabled = !collapsed,
                        state = rememberDraggableState { delta -> app.setTrayHeight(app.trayHeight.value - with(density) { delta.toDp().value }) },
                    )
                    .testTag("tray-handle"),
            )
            Tray(app, collapsed, Modifier.fillMaxWidth().animateContentSize().height(if (collapsed) 36.dp else shownTrayHeight.dp))
        }
    }
}

@Composable
private fun FeedArea(app: AppState, modifier: Modifier) {
    val running by app.runner.running.collectAsState()
    val message by app.drill.message.collectAsState()
    val camera by app.camera.collectAsState()
    val failure by app.runner.failure.collectAsState()
    val colors = Range.colors
    val projectorDrill = running?.host?.isProjector == true

    Surface(shape = RoundedCornerShape(16.dp), color = colors.feedEdge, modifier = modifier) {
        Box(Modifier.fillMaxSize()) {
            if (camera == null) {
                NoCameraPanel(app)
            } else {
                CameraFeedView(app.feed, Modifier.fillMaxSize()) { transform ->
                    TargetLayer(app.feedTargets, transform)
                    MarkerLayer(app.feedMarkers, transform)
                    if (running != null && !projectorDrill) ExerciseOverlay(app.drill, transform)
                }
                NotReadyPrompt(app, Modifier.align(Alignment.Center))
            }

            Row(
                Modifier.align(Alignment.TopStart).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(app)
                FilledTonalButton(onClick = app::clearShots, modifier = Modifier.testTag("clear-shots")) { Text("Clear shots") }
            }
            DrillControls(app, Modifier.align(Alignment.TopEnd).padding(10.dp))
            Column(Modifier.align(Alignment.TopCenter).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                failure?.let { BannerView(Banner(-1, it, BannerKind.ERROR), onDismiss = app.runner::dismissFailure) }
                message?.let { BannerView(Banner(0, it, BannerKind.INFO), onDismiss = { app.drill.setMessage(null) }) }
                FeedBanners(app.feed)
            }
            StatusLine(app, Modifier.align(Alignment.BottomStart).padding(10.dp))
        }
    }
}

@Composable
private fun StatusLine(app: AppState, modifier: Modifier) {
    val camera by app.camera.collectAsState()
    val shownFps by app.feed.fps.collectAsState()
    // Calibration's status, collected so the strip follows each change
    val arena by app.arena.collectAsState()
    val calibration by app.calibration.collectAsState()
    val calibrating = calibration?.state?.collectAsState()?.value?.calibrating == true
    val calibrated = arena?.projection?.collectAsState()?.value != null
    var cameraFps by remember { mutableStateOf(0.0) }
    // The camera's own frame rate, read once a second
    LaunchedEffect(camera) {
        while (true) {
            cameraFps = camera?.fps ?: 0.0
            delay(1000)
        }
    }
    val manager = camera ?: return
    StatusStrip(
        FeedStatus(
            app.settings.getWebcamsUserName(manager.camera).orElse(manager.name),
            cameraFps,
            shownFps,
            manager.feedWidth,
            manager.feedHeight,
            calibrationStatus(arena != null, calibrating, calibrated),
            app.settings.sessionRecorder.isPresent,
        ),
        modifier,
    )
}

/** The tray: the shot timer, and the running drill's settings. Folded, it keeps its titles. */
@Composable
private fun Tray(app: AppState, collapsed: Boolean, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrayPanel("SHOT TIMER", collapsed, Modifier.weight(1f)) { ShotTimerTable(app.timer, Modifier.fillMaxSize()) }
        TrayPanel("SETTINGS", collapsed, Modifier.width(380.dp), action = {
            TextButton(onClick = { app.setTrayCollapsed(!collapsed) }, modifier = Modifier.height(28.dp).testTag("tray-fold")) {
                Text(if (collapsed) "Show" else "Hide", fontSize = 11.sp)
            }
        }) {
            Column(Modifier.verticalScroll(rememberScrollState())) { DrillSettings(app.drill) }
        }
    }
}

@Composable
private fun TrayPanel(
    title: String,
    collapsed: Boolean,
    modifier: Modifier,
    action: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = Range.colors
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.cardBorder),
        modifier = modifier.fillMaxSize(),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = colors.mutedStrong, fontSize = 10.sp, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
                action()
            }
            if (!collapsed) content()
        }
    }
}
