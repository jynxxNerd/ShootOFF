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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.calibration.showsPattern
import com.shootoff.compose.calibration.text
import com.shootoff.compose.drill.DrillCard
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.Range

const val SET_UP_FIRST = "Set up the projector first"

/**
 * What is missing, or under way, on a chip that opens Setup. Once the range is ready (a camera, the arena
 * calibrated, nothing under way and nothing to say) the chip goes: the status strip says it already.
 */
@Composable
fun StatusChip(app: AppState, modifier: Modifier = Modifier) {
    val camera by app.camera.collectAsState()
    val arena by app.arena.collectAsState()
    val controller by app.calibration.collectAsState()
    val calibrating = controller?.state?.collectAsState()?.value?.calibrating == true
    val calibrated = arena?.projection?.collectAsState()?.value != null
    val calibratedAt by app.calibratedAt.collectAsState()
    val check by app.check.collectAsState()
    val colors = Range.colors
    val ready = camera != null && calibrated && !calibrating
    if (ready && check.text() == null) return

    Surface(
        onClick = { app.navigate(Destination.SETUP) },
        shape = RoundedCornerShape(16.dp),
        color = colors.background.copy(alpha = 0.87f),
        border = BorderStroke(1.dp, colors.chipBorder),
        modifier = modifier.testTag("status-chip"),
    ) {
        Text(
            calibrationSummary(camera != null, arena != null, calibrating, calibrated, calibratedAt, check),
            color = if (ready) colors.good else colors.mutedStrong,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 420.dp).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/**
 * The drill picker and the drill's card: Start for the picked drill (a projector drill only once setup is
 * done), and while it runs its texts, its own buttons (Pause) and Stop.
 */
@Composable
fun DrillControls(app: AppState, modifier: Modifier = Modifier) {
    val entries by app.catalog.entries.collectAsState()
    val choice by app.drillChoice.collectAsState()
    val running by app.runner.running.collectAsState()
    // Whether a projector drill may start follows the camera, the arena, calibration and the check
    val camera by app.camera.collectAsState()
    val arena by app.arena.collectAsState()
    val controller by app.calibration.collectAsState()
    val calibrating = controller?.state?.collectAsState()?.value?.calibrating == true
    val calibrated = arena?.projection?.collectAsState()?.value != null
    val check by app.check.collectAsState()
    val colors = Range.colors
    val picked = remember(entries, choice) { app.pickedDrill(entries) }

    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DrillPicker(entries.map { it.metadata().name }, picked?.metadata()?.name, enabled = running == null) { name ->
            entries.firstOrNull { it.metadata().name == name }?.let(app::pickDrill)
        }
        if (running != null) {
            // Pause/Resume does nothing while calibrating or a pattern shows (Task 8 review fix round 1)
            DrillCard(app.drill, pauseEnabled = !calibrating && !check.showsPattern) {
                OutlinedButton(onClick = app::stopDrill, modifier = Modifier.testTag("drill-stop")) { Text("Stop") }
            }
        } else if (picked != null) {
            // As AppState.projectorReady decides it
            val ready = !picked.isProjectorOnly || (camera != null && calibrated && !calibrating && !check.showsPattern)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.highlightCard.copy(alpha = 0.93f),
                border = BorderStroke(1.dp, colors.highlightBorder),
                modifier = Modifier.widthIn(min = 180.dp, max = 300.dp).testTag("drill-idle-card"),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(picked.metadata().name.uppercase(), color = colors.mutedStrong, fontSize = 10.sp, letterSpacing = 0.6.sp)
                    if (!ready) Text(SET_UP_FIRST, color = colors.warning, fontSize = 12.sp, modifier = Modifier.testTag("drill-needs-setup"))
                    Button(
                        onClick = { app.startDrill(picked) },
                        enabled = ready,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                        modifier = Modifier.testTag("drill-start"),
                    ) { Text("Start") }
                }
            }
        }
    }
}

@Composable
private fun DrillPicker(names: List<String>, picked: String?, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }, enabled = enabled && names.isNotEmpty(), modifier = Modifier.testTag("drill-picker")) {
            Text(picked ?: "No drills", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
            Text("  ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (name in names) {
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        onPick(name)
                    },
                    modifier = Modifier.testTag("pick-$name"),
                )
            }
        }
    }
}

/**
 * Range's not-ready prompt (spec §8), over the feed while there is a camera but the arena isn't open and
 * calibrated: the setup steps and their state, Set up (opens Setup) and Skip (hides it for camera-only use
 * until the camera drops or the arena closes). With no camera, the no-camera panel says so instead.
 */
@Composable
fun NotReadyPrompt(app: AppState, modifier: Modifier = Modifier) {
    val camera by app.camera.collectAsState()
    val arena by app.arena.collectAsState()
    val controller by app.calibration.collectAsState()
    val calibrating = controller?.state?.collectAsState()?.value?.calibrating == true
    val calibrated = arena?.projection?.collectAsState()?.value != null
    val calibratedAt by app.calibratedAt.collectAsState()
    val check by app.check.collectAsState()
    val skipped by app.promptSkipped.collectAsState()
    val colors = Range.colors
    val steps = setupSteps(camera != null, arena != null, calibrated)
    if (camera == null || steps.ready || skipped) return

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.cardBorder),
        modifier = modifier.widthIn(max = 420.dp).testTag("not-ready"),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("The projector isn't set up", fontSize = 18.sp, color = colors.text)
            for (step in Step.entries) {
                val state = steps[step]
                val detail = when (step) {
                    Step.CAMERA -> ""
                    Step.PROJECTOR -> if (arena == null) " — not open" else ""
                    Step.CALIBRATE -> if (state == StepState.DONE) "" else " — " + notReadyCalibrateDetail(arena != null, calibrating, calibrated, calibratedAt, check)
                }
                Text(
                    (if (state == StepState.DONE) "✓ " else "•  ") + step.label + detail,
                    color = if (state == StepState.DONE) colors.good else colors.mutedStrong,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { app.navigate(Destination.SETUP) }, modifier = Modifier.testTag("prompt-setup")) { Text("Set up") }
                TextButton(onClick = app::skipPrompt, modifier = Modifier.testTag("prompt-skip")) { Text("Skip") }
            }
        }
    }
}
