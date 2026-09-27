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

import com.shootoff.camera.shot.ShotColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

data class Marker(val id: Long, val x: Double, val y: Double, val color: ShotColor, val radius: Int)

/** The shot markers on one surface, in the surface's coordinates. */
class ShotMarkers {
    private val next = AtomicLong()
    private val markerState = MutableStateFlow<List<Marker>>(emptyList())
    private val visibleState = MutableStateFlow(true)

    val markers: StateFlow<List<Marker>> = markerState.asStateFlow()

    /** The arena hides its markers unless the user wants them (shootoff.arena.show.markers) */
    val visible: StateFlow<Boolean> = visibleState.asStateFlow()

    fun add(x: Double, y: Double, color: ShotColor, radius: Int): Marker {
        val marker = Marker(next.incrementAndGet(), x, y, color, radius)
        markerState.update { it + marker }
        return marker
    }

    fun remove(marker: Marker) = markerState.update { markers -> markers.filterNot { it.id == marker.id } }

    fun removeAll(gone: Collection<Marker>) {
        val ids = gone.map { it.id }.toSet()
        markerState.update { markers -> markers.filterNot { it.id in ids } }
    }

    fun clear() = markerState.update { emptyList() }

    fun setVisible(visible: Boolean) {
        visibleState.value = visible
    }
}
