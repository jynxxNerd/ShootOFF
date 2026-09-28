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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.shootoff.compose.arena.ArenaLayoutView
import com.shootoff.compose.targets.EditingOverlay
import com.shootoff.compose.theme.Range

/**
 * The Targets screen (spec §4): the shooter's arena, drawn from the same targets the projector draws, with
 * every change showing on the projector as it is made.
 */
@Composable
fun TargetsScreen(app: AppState, modifier: Modifier = Modifier) {
    val model = app.targetsModel
    val arena by app.arena.collectAsState()
    val covered = arena?.covered?.collectAsState()?.value == true
    val colors = Range.colors

    Column(modifier.fillMaxSize().padding(16.dp).testTag("targets-screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Targets", fontSize = 22.sp, color = colors.text)
        if (covered) Text(CALIBRATING_NOTE, color = colors.warning, modifier = Modifier.testTag("calibrating-note"))
        Surface(shape = RoundedCornerShape(16.dp), color = colors.feedEdge, modifier = Modifier.weight(1f).fillMaxWidth()) {
            ArenaLayoutView(model.layout, arena, Modifier.fillMaxSize().testTag("targets-arena")) { transform ->
                EditingOverlay(model.layout.targets, model.arenaEditor, transform)
            }
        }
    }
}
