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

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.shootoff.compose.calibration.showsPattern
import com.shootoff.compose.shell.Destination

/**
 * The Compose app's keyboard shortcuts. Function keys, so they never clash with typing in a field.
 */
enum class Shortcut(val key: Key, val label: String) {
    PAUSE_DRILL(Key.F3, "Pause or resume the drill"),
    CLEAR_SHOTS(Key.F4, "Clear the shots"),
    CALIBRATE(Key.F6, "Open Setup and start calibrating"),
    ;

    companion object {
        fun forKey(key: Key): Shortcut? = entries.firstOrNull { it.key == key }
    }
}

/** The par drill's pause button reads this while the drill runs */
const val PAUSE_LABEL = "Pause"

/** … and this while it is paused */
const val RESUME_LABEL = "Resume"

/** Labels a drill's pause button may have */
private val PAUSE_LABELS = setOf(PAUSE_LABEL, RESUME_LABEL)

/**
 * Does what a shortcut does.
 *
 * @return false if it did nothing (e.g. no drill with a pause button runs)
 */
fun AppState.perform(shortcut: Shortcut): Boolean {
    when (shortcut) {
        Shortcut.PAUSE_DRILL -> {
            // Does nothing while calibrating or a pattern shows (Task 8 review fix round 1): a paused drill's
            // Resume must not turn detection back on, or resume its rounds, under the cover
            if (calibration.value?.state?.value?.calibrating == true || check.value.showsPattern) return false
            val pause = drill.buttons.value.firstOrNull { it.label in PAUSE_LABELS } ?: return false
            pause.onClick()
        }
        Shortcut.CLEAR_SHOTS -> clearShots()
        Shortcut.CALIBRATE -> {
            navigate(Destination.SETUP)
            return startCalibration()
        }
    }
    return true
}

/** The main window's key handler: a shortcut's key going down does its action. */
fun AppState.handleKey(key: Key, type: KeyEventType): Boolean {
    if (type != KeyEventType.KeyDown) return false
    val shortcut = Shortcut.forKey(key) ?: return false
    perform(shortcut)
    return true
}
