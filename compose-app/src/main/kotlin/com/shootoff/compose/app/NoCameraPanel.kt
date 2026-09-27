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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.theme.Range

/**
 * The feed with no camera: why, and the cameras to pick from. The cameras are listed, and the picked one
 * opened, off the UI thread; meanwhile the panel says so.
 */
@Composable
fun NoCameraPanel(app: AppState, modifier: Modifier = Modifier) {
    val problem by app.cameraProblem.collectAsState()
    val opening by app.openingCamera.collectAsState()
    val cameras by app.cameraList.collectAsState()
    val waitingFor by app.waitingFor.collectAsState()
    // Looked for again after each problem: an unplugged camera may be back
    LaunchedEffect(problem) { app.refreshCameras() }
    val colors = Range.colors

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.card,
            border = BorderStroke(1.dp, colors.cardBorder),
            modifier = Modifier.widthIn(max = 460.dp).testTag("no-camera"),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("No camera", fontSize = 20.sp, color = colors.text)
                val shownOpening = opening
                if (shownOpening != null) {
                    Text("Opening camera $shownOpening…", color = colors.mutedStrong, modifier = Modifier.testTag("opening-camera"))
                } else {
                    Text(problem ?: "Pick the camera pointed at your target.", color = colors.mutedStrong)
                    // The lost camera reopens by itself when it is plugged back in; another can be picked meanwhile
                    waitingFor?.let {
                        Text("Waiting for $it to be plugged back in…", color = colors.muted, modifier = Modifier.testTag("waiting-for-camera"))
                    }
                    val found = cameras
                    when {
                        found == null -> Text("Looking for cameras…", color = colors.muted)
                        found.isEmpty() -> Text("No cameras found. Plug one in and come back.", color = colors.muted)
                    }
                    for (camera in found.orEmpty()) {
                        FilledTonalButton(onClick = { app.openCameraInBackground(camera) }, modifier = Modifier.testTag("pick-${camera.name}")) {
                            Text(camera.name)
                        }
                    }
                }
            }
        }
    }
}
