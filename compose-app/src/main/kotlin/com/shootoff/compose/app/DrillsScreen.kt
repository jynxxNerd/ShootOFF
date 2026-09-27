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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.theme.Range
import com.shootoff.plugins.engine.V2ExerciseEntry

const val NEEDS_ARENA = "Needs the projector arena"

/**
 * The Drills screen: a library of the v2 exercises the app can run, each with its name, version, creator
 * and description, and whether it needs the projector arena. Drills start from Range's picker (spec §8).
 */
@Composable
fun DrillsScreen(app: AppState, modifier: Modifier = Modifier) {
    val entries by app.catalog.entries.collectAsState()
    val colors = Range.colors

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Drills", fontSize = 22.sp, color = colors.text)
        Text("Pick a drill to run on the Range screen.", color = colors.muted)
        if (entries.isEmpty()) {
            Text("No drills found in the exercises folder.", color = colors.muted)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.exerciseClass().name }) { entry -> DrillRow(entry) }
        }
    }
}

@Composable
private fun DrillRow(entry: V2ExerciseEntry) {
    val colors = Range.colors
    val metadata = entry.metadata()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.cardBorder),
        modifier = Modifier.fillMaxWidth().testTag("drill-${metadata.name}"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(metadata.name, color = colors.text, fontSize = 16.sp)
            Text("${metadata.version} · ${metadata.creator}", color = colors.muted, fontSize = 12.sp)
            Text(metadata.description, color = colors.mutedStrong, fontSize = 13.sp)
            if (entry.isProjectorOnly) {
                Text(NEEDS_ARENA, color = colors.warning, fontSize = 12.sp, modifier = Modifier.testTag("needs-arena"))
            }
        }
    }
}
