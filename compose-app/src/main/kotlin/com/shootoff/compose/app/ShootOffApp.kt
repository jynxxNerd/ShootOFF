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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.shootoff.compose.shell.AppRail
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.Range

/** The main window's content: the rail, the destination it leads to, and snackbars for notices. */
@Composable
fun ShootOffApp(app: AppState) {
    val destination by app.destination.collectAsState()
    Box(Modifier.fillMaxSize()) {
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
        NoticeSnackbars(app.notices, Modifier.align(Alignment.BottomCenter).padding(24.dp))
    }
}

/** Each notice as a snackbar until dismissed, oldest at the top */
@Composable
fun NoticeSnackbars(notices: Notices, modifier: Modifier = Modifier) {
    val shown by notices.notices.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (notice in shown) {
            Snackbar(
                action = { TextButton(onClick = { notices.dismiss(notice) }, modifier = Modifier.testTag("dismiss-notice-${notice.id}")) { Text("Dismiss") } },
                modifier = Modifier.widthIn(max = 640.dp).testTag("notice-${notice.id}"),
            ) {
                Column {
                    Text(notice.title)
                    Text(notice.message)
                }
            }
        }
    }
}
