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

package com.shootoff.compose.drill

import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.geom.Size
import com.shootoff.targets.model.PlacedTarget

/**
 * The surface an exercise runs on, as its host uses it: the projector arena, or a camera feed.
 */
interface HostSurface {
    /** Where the exercise's own targets go */
    val targets: SurfaceTargets

    val isProjector: Boolean

    /**
     * The targets the exercise sees and hears about: its surface's, and for a camera exercise that runs
     * everywhere, the arena's too
     */
    val seenTargets: List<SurfaceTargets> get() = listOf(targets)

    /** Whether the exercise takes shots on the arena (in arena coordinates) */
    val takesArenaShots: Boolean get() = isProjector

    fun size(): Size

    /** The arena's background (always none on a camera feed) */
    fun background(): ArenaBackground? = null

    fun setBackground(background: ArenaBackground?) {}

    /** Fits a target the exercise just added, as the surface fits new targets */
    fun placeNewTarget(target: PlacedTarget) {}
}

class ArenaHostSurface(private val arena: ArenaModel) : HostSurface {
    override val targets: SurfaceTargets get() = arena.targets

    override val isProjector: Boolean get() = true

    override fun size(): Size = arena.size.value

    override fun background(): ArenaBackground? = arena.background.value

    override fun setBackground(background: ArenaBackground?) = arena.setBackground(background)

    override fun placeNewTarget(target: PlacedTarget) = arena.placeNewTarget(target)
}

/**
 * A camera feed: its targets on the feed's canvas, which is the display size.
 *
 * @param arenaTargets for an exercise that runs everywhere (exercise port spec §5): the shooter's arena
 *   targets, which the exercise sees beside the feed's, and whose shots it takes too
 */
class FeedHostSurface(
    override val targets: SurfaceTargets,
    private val displaySize: Size,
    private val arenaTargets: SurfaceTargets? = null,
) : HostSurface {
    override val isProjector: Boolean get() = false

    override val seenTargets: List<SurfaceTargets> get() = listOfNotNull(targets, arenaTargets)

    override val takesArenaShots: Boolean get() = arenaTargets != null

    override fun size(): Size = displaySize
}
