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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.arena.ArenaView
import com.shootoff.compose.calibration.CalibrationOverlay
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.calibration.ProjectionOutline
import com.shootoff.compose.feed.CameraFeedView
import com.shootoff.compose.theme.Range
import kotlinx.coroutines.delay

/**
 * Setup (spec §8): the camera, the projector and calibration, as three steps in order, each with its
 * state, beside the camera feed on which calibration shows its box and the calibrated projection is
 * outlined. Nothing here waits on the hardware: each step says what is missing and the rest still works.
 */
@Composable
fun SetupScreen(app: AppState, modifier: Modifier = Modifier) {
    val camera by app.camera.collectAsState()
    val arena by app.arena.collectAsState()
    val controller by app.calibration.collectAsState()
    val calibrating = controller?.state?.collectAsState()?.value?.calibrating == true
    val projection = arena?.projection?.collectAsState()?.value
    val steps = setupSteps(camera != null, arena != null, projection != null)
    val colors = Range.colors

    Row(modifier.fillMaxSize().padding(8.dp).testTag("setup-screen"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Setup", fontSize = 22.sp, color = colors.text)
            StepCard(Step.CAMERA, steps.camera) { CameraStep(app) }
            StepCard(Step.PROJECTOR, steps.projector) { ProjectorStep(app) }
            StepCard(Step.CALIBRATE, steps.calibrate) { CalibrateStep(app) }
        }
        Surface(shape = RoundedCornerShape(16.dp), color = colors.feedEdge, modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (camera == null) {
                NoCameraPanel(app)
            } else {
                CameraFeedView(app.feed, Modifier.fillMaxSize()) { transform ->
                    if (projection != null && !calibrating) ProjectionOutline(projection, transform)
                    controller?.let { CalibrationOverlay(it, transform) }
                }
            }
        }
    }
}

@Composable
private fun StepCard(step: Step, state: StepState, content: @Composable () -> Unit) {
    val colors = Range.colors
    val number = step.ordinal + 1
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (state == StepState.NEXT) colors.highlightCard else colors.card,
        border = BorderStroke(1.dp, if (state == StepState.NEXT) colors.highlightBorder else colors.cardBorder),
        modifier = Modifier.fillMaxWidth().testTag("step-${step.name}").semantics { stateDescription = state.name },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                (if (state == StepState.DONE) "✓ " else "$number  ") + step.label,
                fontSize = 16.sp,
                color = when (state) {
                    StepState.DONE -> colors.good
                    StepState.NEXT -> colors.text
                    StepState.WAITING -> colors.muted
                },
            )
            content()
        }
    }
}

/** The camera: the open one with its frame rate and size, or why there is none, and the cameras to pick from */
@Composable
private fun CameraStep(app: AppState) {
    val camera by app.camera.collectAsState()
    val problem by app.cameraProblem.collectAsState()
    val opening by app.openingCamera.collectAsState()
    val cameras by app.cameraList.collectAsState()
    val colors = Range.colors
    var fps by remember { mutableStateOf(0.0) }
    // Listed off the UI thread: it can take seconds
    LaunchedEffect(Unit) { app.refreshCameras() }
    LaunchedEffect(camera) {
        while (true) {
            fps = camera?.fps ?: 0.0
            delay(1000)
        }
    }

    val open = camera
    if (open != null) {
        Text(
            "${app.settings.getWebcamsUserName(open.camera).orElse(open.name)} · ${"%.0f".format(fps)} FPS · ${open.feedWidth}×${open.feedHeight}",
            color = colors.mutedStrong,
            modifier = Modifier.testTag("camera-details"),
        )
    } else {
        Text(problem ?: "No camera", color = colors.mutedStrong, modifier = Modifier.testTag("camera-missing"))
    }
    opening?.let { Text("Opening camera $it…", color = colors.muted) }
    val found = cameras
    when {
        found == null -> Text("Looking for cameras…", color = colors.muted)
        found.isEmpty() -> Text("No cameras found. Plug one in and come back.", color = colors.muted)
    }
    for (choice in found.orEmpty()) {
        if (open?.camera == choice) continue
        FilledTonalButton(
            onClick = { app.pickCamera(choice) },
            enabled = opening == null,
            modifier = Modifier.testTag("setup-camera-${choice.name}"),
        ) { Text("Use ${choice.name}") }
    }
}

/** The projector: open or close the arena, with a small live preview of it */
@Composable
private fun ProjectorStep(app: AppState) {
    val arena by app.arena.collectAsState()
    val colors = Range.colors
    // Looking for the projector asks AWT about every screen: once per arena change, not on every recomposition
    val projectorFound = remember(arena) { arena == null && app.projectorScreenFound() }

    val open = arena
    if (open == null) {
        if (!projectorFound) {
            Text("No projector screen found: the arena opens as a window", color = colors.warning, modifier = Modifier.testTag("no-projector"))
        }
        FilledTonalButton(onClick = app::openArena, modifier = Modifier.testTag("open-arena")) { Text("Open arena") }
    } else {
        ArenaView(open, Modifier.size(240.dp, 135.dp).testTag("arena-preview"))
        FilledTonalButton(onClick = app::closeArena, modifier = Modifier.testTag("close-arena")) { Text("Close arena") }
    }
}

/** Calibration: where it stands, Calibrate or Cancel, Remember calibration and Show grid */
@Composable
private fun CalibrateStep(app: AppState) {
    val camera by app.camera.collectAsState()
    val arena by app.arena.collectAsState()
    val controller by app.calibration.collectAsState()
    val calibrating = controller?.state?.collectAsState()?.value?.calibrating == true
    val calibrated = arena?.projection?.collectAsState()?.value != null
    val calibratedAt by app.calibratedAt.collectAsState()
    val check by app.check.collectAsState()
    val remember by app.rememberCalibration.collectAsState()
    val grid = arena?.grid?.collectAsState()?.value == true
    val running by app.runner.running.collectAsState()
    val colors = Range.colors

    Text(
        calibrationSummary(camera != null, arena != null, calibrating, calibrated, calibratedAt, check),
        color = if (calibrated && !calibrating) colors.good else colors.mutedStrong,
        modifier = Modifier.testTag("calibration-summary"),
    )
    // Cancel is always there while something runs (spec §8 rule 5)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            calibrating -> FilledTonalButton(onClick = app::cancelCalibration, modifier = Modifier.testTag("setup-cancel")) { Text("Cancel") }
            check == CheckState.Checking -> FilledTonalButton(onClick = app::cancelCheck, modifier = Modifier.testTag("setup-cancel")) { Text("Cancel") }
            else -> Button(onClick = { app.startCalibration() }, enabled = controller != null, modifier = Modifier.testTag("setup-calibrate")) {
                Text(if (calibrated) "Recalibrate" else "Calibrate")
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = remember, onCheckedChange = app::setRememberCalibration, modifier = Modifier.testTag("remember-calibration"))
        Text("Remember calibration", color = colors.text)
    }
    val projectorDrill = running?.host?.isProjector == true
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(
            checked = grid,
            onCheckedChange = app::showGrid,
            enabled = arena != null && !calibrating && check != CheckState.Checking && !projectorDrill,
            modifier = Modifier.testTag("show-grid"),
        )
        Text("Show grid", color = colors.text)
    }
    if (projectorDrill) Text("Stop the drill to show the grid", color = colors.muted, fontSize = 12.sp)
}
