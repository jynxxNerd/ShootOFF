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

package com.shootoff.compose.targets

import com.shootoff.geom.Point
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.PlacedTarget
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetId
import com.shootoff.targets.model.TargetSet
import com.shootoff.targets.model.TargetSetListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * A target as the views draw it: a snapshot of its placed target.
 *
 * @param origin where the target's (0, 0) is on the surface
 */
data class DrawnTarget(
    val id: TargetId,
    val definition: TargetDefinition,
    val placement: Placement,
    val origin: Point,
    val regionVisible: List<Boolean>,
)

/**
 * The targets on one surface (a camera feed or the arena): the [TargetSet] the shot pipeline hit-tests,
 * their images and animations, and the snapshot every view of the surface draws. There is one per
 * surface however many views show it, so every view shows the same targets.
 */
class SurfaceTargets(val set: TargetSet = TargetSet(), clock: AnimationClock = AnimationClock.background) {
    val animations = RegionAnimations(set, clock)
    private val images = ConcurrentHashMap<TargetId, Map<Int, RegionImage>>()
    private val drawnState = MutableStateFlow<List<DrawnTarget>>(emptyList())

    /** The targets, bottom to top, as the views draw them */
    val drawn: StateFlow<List<DrawnTarget>> = drawnState.asStateFlow()

    init {
        set.addListener(object : TargetSetListener {
            override fun targetAdded(target: PlacedTarget) = publish()

            override fun targetRemoved(target: PlacedTarget) {
                images.remove(target.id)
                animations.unregister(target.id)
                publish()
            }

            override fun targetChanged(target: PlacedTarget) = publish()
        })
    }

    /**
     * Adds a target on top, with its images read through [resolver] (the exercise's jar for "@" paths).
     */
    fun add(definition: TargetDefinition, resolver: ResourceResolver, placement: Placement = Placement.ORIGIN): PlacedTarget {
        val loaded = RegionImages.load(definition, resolver)
        val target = set.add(definition, placement)
        images[target.id] = loaded
        animations.register(target.id, loaded)
        publish()
        return target
    }

    fun remove(id: TargetId) = set.remove(id)

    fun image(id: TargetId, region: Int): RegionImage? = images[id]?.get(region)

    // Synchronized so two concurrent mutations' publishes can't complete out of order and leave a
    // stale (older) snapshot published after a fresher one, e.g. one whose mapping happened to take
    // longer finishing after a later mutation's own publish already ran.
    @Synchronized
    private fun publish() {
        drawnState.value = set.targets.map { target ->
            DrawnTarget(
                target.id,
                target.definition,
                target.placement,
                target.localToParent(0.0, 0.0),
                target.definition.regions().indices.map { target.isRegionVisible(it) },
            )
        }
    }
}
