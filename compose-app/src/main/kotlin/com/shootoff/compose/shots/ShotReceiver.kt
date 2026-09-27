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

package com.shootoff.compose.shots

import com.shootoff.camera.Shot
import com.shootoff.targets.model.Hit

/**
 * Whoever runs the current exercise: shots end there (see ExerciseRunner).
 */
fun interface ShotReceiver {
    /**
     * @param arenaShot true for a shot on the arena, in arena coordinates
     * @return true if an exercise took the shot
     */
    fun deliver(shot: Shot, hit: Hit?, arenaShot: Boolean): Boolean

    companion object {
        val None = ShotReceiver { _, _, _ -> false }
    }
}
