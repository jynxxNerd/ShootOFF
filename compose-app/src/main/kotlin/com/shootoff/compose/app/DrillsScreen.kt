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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.theme.Range
import com.shootoff.plugins.engine.V2ExerciseEntry

const val NEEDS_ARENA = "Needs the projector arena"
const val CALIBRATING_HINT = "Calibrating… finish calibration first"

/**
 * The Drills screen: the v2 exercises the app can run. A projector drill can start only while the arena
 * is open and not calibrating; until then it says so (a v2 drill draws over the calibration pattern and
 * detection is off while calibrating, so it would look dead if it were allowed to start). Starting one
 * stops the running one and goes to the Range screen.
 */
@Composable
fun DrillsScreen(app: AppState, modifier: Modifier = Modifier) {
    val entries by app.catalog.entries.collectAsState()
    val arena by app.arena.collectAsState()
    val running by app.runner.running.collectAsState()
    val calibrationController by app.calibration.collectAsState()
    val calibrating = calibrationController?.state?.collectAsState()?.value?.calibrating == true
    val colors = Range.colors

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Drills", fontSize = 22.sp, color = colors.text)
        if (entries.isEmpty()) {
            Text("No drills found in the exercises folder.", color = colors.muted)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.exerciseClass().name }) { entry ->
                val hint = when {
                    !entry.isProjectorOnly -> null
                    arena == null -> NEEDS_ARENA
                    calibrating -> CALIBRATING_HINT
                    else -> null
                }
                DrillRow(
                    entry,
                    isRunning = running?.entry == entry,
                    canStart = hint == null,
                    hint = hint,
                    onStart = { app.startDrill(entry) },
                    onStop = app::stopDrill,
                )
            }
        }
    }
}

@Composable
private fun DrillRow(entry: V2ExerciseEntry, isRunning: Boolean, canStart: Boolean, hint: String?, onStart: () -> Unit, onStop: () -> Unit) {
    val colors = Range.colors
    val metadata = entry.metadata()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isRunning) colors.highlightCard else colors.card,
        border = BorderStroke(1.dp, if (isRunning) colors.highlightBorder else colors.cardBorder),
        modifier = Modifier.fillMaxWidth().testTag("drill-${metadata.name}"),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(metadata.name, color = colors.text, fontSize = 16.sp)
                Text("${metadata.version} · ${metadata.creator}" + if (entry.isProjectorOnly) " · Projector" else "", color = colors.muted, fontSize = 12.sp)
                Text(metadata.description, color = colors.mutedStrong, fontSize = 13.sp)
                if (hint == NEEDS_ARENA) {
                    Text(NEEDS_ARENA, color = colors.warning, fontSize = 12.sp, modifier = Modifier.testTag("needs-arena"))
                } else if (hint == CALIBRATING_HINT) {
                    Text(CALIBRATING_HINT, color = colors.warning, fontSize = 12.sp, modifier = Modifier.testTag("calibrating-hint"))
                }
            }
            if (isRunning) {
                FilledTonalButton(onClick = onStop, modifier = Modifier.testTag("stop-drill")) { Text("Stop") }
            } else {
                Button(onClick = onStart, enabled = canStart, modifier = Modifier.testTag("start-drill")) { Text("Start") }
            }
        }
    }
}
