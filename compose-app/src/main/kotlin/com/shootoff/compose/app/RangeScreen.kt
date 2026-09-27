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

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.arena.ArenaView
import com.shootoff.compose.calibration.CalibrationOverlay
import com.shootoff.compose.drill.DrillCard
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

/**
 * The Range screen: the big view (the camera feed or the arena) with the Camera | Arena switch, the
 * drill card and the status strip over it, and the tray with the shot timer and the drill's settings.
 */
@Composable
fun RangeScreen(app: AppState, modifier: Modifier = Modifier) {
    val trayHeight by app.trayHeight.collectAsState()
    val collapsed by app.trayCollapsed.collectAsState()
    val density = LocalDensity.current
    Column(modifier.fillMaxSize().padding(end = 8.dp, top = 8.dp, bottom = 8.dp)) {
        BigViewArea(app, Modifier.weight(1f).fillMaxWidth())
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
        Tray(app, collapsed, Modifier.fillMaxWidth().animateContentSize().height(if (collapsed) 36.dp else trayHeight.dp))
    }
}

@Composable
private fun BigViewArea(app: AppState, modifier: Modifier) {
    val view by app.view.collectAsState()
    val arena by app.arena.collectAsState()
    val running by app.runner.running.collectAsState()
    val message by app.drill.message.collectAsState()
    val calibration by app.calibration.collectAsState()
    val camera by app.camera.collectAsState()
    val failure by app.runner.failure.collectAsState()
    val colors = Range.colors
    val projectorDrill = running?.host?.isProjector == true

    Surface(shape = RoundedCornerShape(16.dp), color = colors.feedEdge, modifier = modifier) {
        Box(Modifier.fillMaxSize()) {
            val shownArena = arena
            val shown = if (view == BigView.ARENA && shownArena != null) BigView.ARENA else BigView.CAMERA
            Crossfade(shown, label = "big view") { current ->
                if (current == BigView.ARENA && shownArena != null) {
                    ArenaView(shownArena, Modifier.fillMaxSize()) { transform ->
                        if (projectorDrill) ExerciseOverlay(app.drill, transform)
                    }
                } else if (camera == null) {
                    NoCameraPanel(app)
                } else {
                    CameraFeedView(app.feed, Modifier.fillMaxSize()) { transform ->
                        TargetLayer(app.feedTargets, transform)
                        MarkerLayer(app.feedMarkers, transform)
                        if (running != null && !projectorDrill) ExerciseOverlay(app.drill, transform)
                        calibration?.let { CalibrationOverlay(it, transform) }
                    }
                }
            }

            Column(Modifier.align(Alignment.TopStart).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ViewSwitch(app)
                RangeActions(app)
            }
            DrillCard(app.drill, Modifier.align(Alignment.TopEnd).padding(10.dp))
            Column(Modifier.align(Alignment.TopCenter).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                failure?.let { BannerView(Banner(-1, it, BannerKind.ERROR), onDismiss = app.runner::dismissFailure) }
                message?.let { BannerView(Banner(0, it, BannerKind.INFO), onDismiss = { app.drill.setMessage(null) }) }
                FeedBanners(app.feed)
            }
            StatusLine(app, Modifier.align(Alignment.BottomStart).padding(10.dp))
        }
    }
}

/** The Camera | Arena segmented switch. Arena is disabled, with a hint, until the arena is open. */
@Composable
fun ViewSwitch(app: AppState) {
    val view by app.view.collectAsState()
    val arena by app.arena.collectAsState()
    val colors = Range.colors
    // Looking for the projector asks AWT about every screen: once per arena change, not on every recomposition
    val projectorFound = remember(arena) { arena == null && app.projectorScreenFound() }
    val arenaHint = when {
        arena != null -> null
        projectorFound -> "Open the arena first"
        else -> "No projector screen found"
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.background.copy(alpha = 0.87f),
            border = BorderStroke(1.dp, colors.chipBorder),
        ) {
            Row(Modifier.padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Segment("Camera", view == BigView.CAMERA, enabled = true, tag = "view-camera") { app.showView(BigView.CAMERA) }
                Segment("Arena", view == BigView.ARENA, enabled = arena != null, tag = "view-arena", hint = arenaHint) {
                    app.showView(BigView.ARENA)
                }
            }
        }
        arenaHint?.let { Text(it, color = colors.muted, fontSize = 11.sp, modifier = Modifier.testTag("arena-hint")) }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, enabled: Boolean, tag: String, hint: String? = null, onClick: () -> Unit) {
    val colors = Range.colors
    val text = when {
        selected -> colors.onAccentSoft
        enabled -> colors.mutedStrong
        else -> colors.muted.copy(alpha = 0.45f)
    }
    Text(
        label,
        color = text,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Tab
                this.selected = selected
                if (hint != null) stateDescription = hint
            }
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .testTag(tag),
    )
}

/** Open or close the arena, calibrate, reset and clear the shots */
@Composable
private fun RangeActions(app: AppState) {
    val arena by app.arena.collectAsState()
    val calibration by app.calibration.collectAsState()
    val calibrating = calibration?.state?.collectAsState()?.value?.calibrating == true

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (arena == null) {
            FilledTonalButton(onClick = { app.openArena() }, modifier = Modifier.testTag("open-arena")) { Text("Open arena") }
        } else {
            FilledTonalButton(onClick = { app.closeArena() }, modifier = Modifier.testTag("close-arena")) { Text("Close arena") }
            FilledTonalButton(onClick = app::toggleCalibration, enabled = calibration != null, modifier = Modifier.testTag("calibrate")) {
                Text(if (calibrating) "Stop calibrating" else "Calibrate")
            }
        }
        FilledTonalButton(onClick = app::reset, modifier = Modifier.testTag("reset")) { Text("Reset") }
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
