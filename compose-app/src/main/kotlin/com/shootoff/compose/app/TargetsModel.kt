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

import com.shootoff.compose.arena.ArenaLayout
import com.shootoff.compose.targets.TargetEditor

/** What the Targets screen says while calibration's pattern covers the arena (spec §6) */
const val CALIBRATING_NOTE = "Calibrating — the projector shows the pattern; your edits appear when it's done."

/**
 * The Targets screen's state, apart from its composables: the editor of the arena's targets.
 *
 * @param layout the shooter's arena, open or not
 */
class TargetsModel(val layout: ArenaLayout) {
    val arenaEditor = TargetEditor(layout.targets) { layout.size.value }
}
