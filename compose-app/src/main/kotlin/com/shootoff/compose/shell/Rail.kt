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

package com.shootoff.compose.shell

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.theme.Range

/**
 * The places the rail leads to, in the rail's order (spec §8). Sessions is in the JavaFX app for now.
 */
enum class Destination(val label: String, val icon: ImageVector, val enabled: Boolean) {
    RANGE("Range", Icons.Filled.Home, true),
    SETUP("Setup", Icons.Filled.Build, true),
    DRILLS("Drills", Icons.Filled.PlayArrow, true),
    TARGETS("Targets", Icons.Filled.Place, true),
    SESSIONS("Sessions", Icons.AutoMirrored.Filled.List, false),
    SETTINGS("Settings", Icons.Filled.Settings, true),
}

const val DISABLED_HINT = "In the JavaFX app for now"

@Composable
fun AppRail(
    selected: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val colors = Range.colors
    NavigationRail(modifier = modifier, containerColor = colors.rail) {
        Spacer(Modifier.height(12.dp))
        for (destination in Destination.entries) {
            NavigationRailItem(
                selected = destination == selected,
                enabled = destination.enabled,
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = colors.onAccentSoft,
                    selectedTextColor = colors.onAccentSoft,
                    indicatorColor = colors.accentSoft,
                    unselectedIconColor = colors.muted,
                    unselectedTextColor = colors.muted,
                    disabledIconColor = colors.muted.copy(alpha = 0.4f),
                    disabledTextColor = colors.muted.copy(alpha = 0.4f),
                ),
                modifier = Modifier.testTag("rail-${destination.name}").semantics {
                    if (!destination.enabled) stateDescription = DISABLED_HINT
                },
            )
        }
        Spacer(Modifier.height(16.dp))
        // Sessions is greyed out; say why
        Text(
            "Sessions: ${DISABLED_HINT.replaceFirstChar { it.lowercase() }}",
            color = colors.muted,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp).padding(horizontal = 4.dp).testTag("rail-hint"),
        )
        Spacer(Modifier.weight(1f))
        trailing()
        Spacer(Modifier.height(12.dp))
    }
}
