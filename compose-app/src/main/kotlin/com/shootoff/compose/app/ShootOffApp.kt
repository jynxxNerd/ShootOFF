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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.shootoff.compose.shell.AppRail
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.Range

/** The main window's content: the rail, and the destination it leads to. */
@Composable
fun ShootOffApp(app: AppState) {
    val destination by app.destination.collectAsState()
    Row(Modifier.fillMaxSize().background(Range.colors.background)) {
        AppRail(destination, app::navigate)
        Box(Modifier.fillMaxSize()) {
            when (destination) {
                Destination.RANGE -> RangeScreen(app)
                Destination.DRILLS -> DrillsScreen(app)
                Destination.SETTINGS -> SettingsScreen(app)
                // Disabled on the rail
                Destination.TARGETS, Destination.SESSIONS -> RangeScreen(app)
            }
        }
    }
}
