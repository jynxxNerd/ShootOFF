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

import com.shootoff.camera.shot.ScaledShot

/** A camera feed's shot passed on to the arena, at its point in arena coordinates. */
class ArenaPointShot(shot: ScaledShot, private val arenaX: Double, private val arenaY: Double) : ScaledShot(shot) {
    override fun getX(): Double = arenaX

    override fun getY(): Double = arenaY
}
