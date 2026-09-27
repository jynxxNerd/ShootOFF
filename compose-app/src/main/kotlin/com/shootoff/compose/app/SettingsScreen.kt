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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.theme.Range
import com.shootoff.geom.Rect
import org.slf4j.LoggerFactory
import kotlin.math.roundToInt

private val logger = LoggerFactory.getLogger("com.shootoff.compose.app.SettingsScreen")

/**
 * The slim Settings screen: which camera, how big shot markers are, and which screen the arena goes on.
 * Changes are saved to shootoff.properties, which the JavaFX app shares.
 */
@Composable
fun SettingsScreen(app: AppState, modifier: Modifier = Modifier) {
    val colors = Range.colors
    val openCamera by app.camera.collectAsState()
    // Listed, and a picked one opened, off the UI thread: both can take seconds
    val cameras by app.cameraList.collectAsState()
    val opening by app.openingCamera.collectAsState()
    LaunchedEffect(Unit) { app.refreshCameras() }
    var markerRadius by remember { mutableIntStateOf(app.settings.markerRadius) }
    var arenaScreen by remember { mutableStateOf(app.arenaPlacementNow().screen) }
    val screens = remember { app.screensNow() }

    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", fontSize = 22.sp, color = colors.text)

        Section("Theme") {
            val dark by app.dark.collectAsState()
            Choice("Range dark", dark, "theme-dark") { app.setDark(true) }
            Choice("Range light", !dark, "theme-light") { app.setDark(false) }
        }

        Section("Camera") {
            val found = cameras
            when {
                found == null -> Text("Looking for cameras…", color = colors.muted)
                found.isEmpty() -> Text("No cameras found.", color = colors.muted)
            }
            for (camera in found.orEmpty()) {
                // One camera opens at a time: the choices wait while one is opening
                Choice(camera.name, openCamera?.camera == camera, "camera-${camera.name}", enabled = opening == null) {
                    app.pickCamera(camera)
                }
            }
            opening?.let { Text("Opening camera $it…", color = colors.muted, modifier = Modifier.testTag("opening-camera")) }
        }

        Section("Shot marker size") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = markerRadius.toFloat(),
                    onValueChange = { markerRadius = it.roundToInt() },
                    onValueChangeFinished = {
                        app.settings.setMarkerRadius(markerRadius)
                        save(app)
                    },
                    valueRange = 1f..20f,
                    steps = 18,
                    modifier = Modifier.width(280.dp).testTag("marker-size"),
                )
                Text("$markerRadius px", color = colors.text, modifier = Modifier.padding(start = 12.dp))
            }
        }

        Section("Arena display") {
            for (screen in screens) {
                Choice(describe(screen), screen == arenaScreen, "screen-${screen.minX.toInt()}") {
                    // Saved as the JavaFX app saves an arena the user placed by hand
                    app.settings.setArenaPosition(screen.minX, screen.minY)
                    save(app)
                    arenaScreen = screen
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), color = Range.colors.mutedStrong, fontSize = 11.sp, letterSpacing = 0.6.sp)
        content()
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, tag: String, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).testTag(tag),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, color = Range.colors.text, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun describe(screen: Rect) = "${screen.width.toInt()}×${screen.height.toInt()} at ${screen.minX.toInt()}, ${screen.minY.toInt()}"

private fun save(app: AppState) {
    try {
        app.settings.writeConfigurationFile()
    } catch (e: Exception) {
        logger.error("Couldn't save the settings", e)
    }
}
