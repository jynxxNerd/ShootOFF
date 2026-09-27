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

package com.shootoff.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.shootoff.compose.shell.AppRail
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.Range
import com.shootoff.compose.theme.RangeTheme

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "ShootOFF") {
        RangeTheme(dark = true) {
            var destination by remember { mutableStateOf(Destination.RANGE) }
            Row(Modifier.fillMaxSize().background(Range.colors.background)) {
                AppRail(destination, { destination = it })
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(destination.label, color = Range.colors.muted)
                }
            }
        }
    }
}
