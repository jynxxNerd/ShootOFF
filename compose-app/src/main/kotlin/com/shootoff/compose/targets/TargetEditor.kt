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
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.PlacedTarget
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.TargetId
import com.shootoff.targets.model.TargetSetListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The corner of a target a resize drags; the opposite corner stays put. */
enum class Corner(val left: Boolean, val top: Boolean) {
    TOP_LEFT(true, true),
    TOP_RIGHT(false, true),
    BOTTOM_LEFT(true, false),
    BOTTOM_RIGHT(false, false),
}

/** An arrow key */
enum class Arrow { LEFT, RIGHT, UP, DOWN }

/**
 * The Targets screen's editing of one surface's targets, without any UI: which target is selected, and the
 * gestures that move, resize, nudge and remove it, as changes to the surface's [SurfaceTargets]. Only the
 * shooter's targets ([TargetOwner.USER]) can be selected; an exercise's are left alone. Every change keeps
 * part of the target on the surface: a strip [KEEP_ON] units deep, or the whole target if it is smaller.
 *
 * The gestures come on the UI thread; the surface's targets may change on other threads meanwhile (an
 * exercise's, a course loading), and the selection goes if its target does.
 *
 * @param size the surface's size, in its own units
 */
class TargetEditor(private val targets: SurfaceTargets, private val size: () -> Size) {
    companion object {
        /** How much of a target always stays on the surface, in surface units */
        const val KEEP_ON = 10.0

        /** How far an arrow key moves a target, and how much Shift+arrow resizes it, in surface units */
        const val STEP = 1.0

        /** The smallest a resize makes a target, in surface units, unless it was already smaller */
        const val MIN_SIZE = 10.0
    }

    private val selectedState = MutableStateFlow<TargetId?>(null)

    // The selected target's placement and bounds as the drag under way began
    private var dragStart: Placement? = null
    private var dragBounds: Rect? = null

    /** The selected target, or null */
    val selected: StateFlow<TargetId?> = selectedState.asStateFlow()

    init {
        targets.set.addListener(object : TargetSetListener {
            override fun targetAdded(target: PlacedTarget) {}

            override fun targetRemoved(target: PlacedTarget) {
                selectedState.compareAndSet(target.id, null)
            }

            override fun targetChanged(target: PlacedTarget) {}
        })
    }

    /**
     * The shooter's topmost shown target whose bounds hold [point]. Clicks go through an exercise's targets,
     * and through a target an exercise has hidden.
     */
    fun targetAt(point: Point): TargetId? = targets.set.targets.asReversed().firstOrNull { target ->
        target.isVisible && targets.owner(target.id) == TargetOwner.USER && target.bounds.contains(point.x, point.y)
    }?.id

    /** A click at [point]: selects the shooter's target there, or nothing */
    fun click(point: Point) {
        selectedState.value = targetAt(point)
    }

    /** @return false, selecting nothing, if [id] isn't one of the shooter's targets on the surface */
    fun select(id: TargetId): Boolean {
        if (targets.owner(id) != TargetOwner.USER) return false
        selectedState.value = id
        return true
    }

    fun deselect() {
        selectedState.value = null
    }

    /**
     * A drag of the selected target begins. Each move or resize after it is placed from where the target
     * was now, by the pointer's whole movement since, so moves the UI coalesces are never lost.
     *
     * @return false if nothing is selected or if it can't be edited
     */
    fun startDrag(): Boolean {
        val target = selectedTarget() ?: return false
        if (!canEdit(target)) return false
        dragStart = target.placement
        dragBounds = target.bounds
        return true
    }

    fun endDrag() {
        dragStart = null
        dragBounds = null
    }

    /** Moves the selected target by ([dx], [dy]) from where the drag began */
    fun dragMove(dx: Double, dy: Double) {
        val target = selectedTarget() ?: return
        if (!canEdit(target)) return
        val start = dragStart ?: return
        place(target, start.withPosition(start.x() + dx, start.y() + dy))
    }

    /**
     * Resizes the selected target by dragging [corner] ([dx], [dy]) from where the drag began, the opposite
     * corner staying put. With [keepAspect] (Ctrl held) the target keeps its shape, following whichever
     * side the pointer changed more.
     */
    fun dragResize(corner: Corner, dx: Double, dy: Double, keepAspect: Boolean) {
        val target = selectedTarget() ?: return
        if (!canEdit(target)) return
        val start = dragStart ?: return
        val from = dragBounds ?: return
        val minWidth = min(MIN_SIZE, from.width)
        val minHeight = min(MIN_SIZE, from.height)
        var width = if (corner.left) from.width - dx else from.width + dx
        var height = if (corner.top) from.height - dy else from.height + dy
        if (keepAspect) {
            val factor = max(
                if (abs(width - from.width) / from.width >= abs(height - from.height) / from.height) width / from.width else height / from.height,
                max(minWidth / from.width, minHeight / from.height),
            )
            width = from.width * factor
            height = from.height * factor
        }
        width = max(width, minWidth)
        height = max(height, minHeight)
        val minX = if (corner.left) from.maxX - width else from.minX
        val minY = if (corner.top) from.maxY - height else from.minY
        place(target, fitted(target, start, Rect(minX, minY, width, height)))
    }

    /**
     * An arrow key: moves the selected target by [STEP], or with [resize] (Shift held) makes it [STEP]
     * wider (Right) or narrower (Left), taller (Down) or shorter (Up), about its center, as the JavaFX app did.
     */
    fun nudge(arrow: Arrow, resize: Boolean) {
        val target = selectedTarget() ?: return
        if (!canEdit(target)) return
        val p = target.placement
        if (!resize) {
            val (dx, dy) = when (arrow) {
                Arrow.LEFT -> -STEP to 0.0
                Arrow.RIGHT -> STEP to 0.0
                Arrow.UP -> 0.0 to -STEP
                Arrow.DOWN -> 0.0 to STEP
            }
            place(target, p.withPosition(p.x() + dx, p.y() + dy))
            return
        }
        val bounds = target.bounds
        var width = bounds.width
        var height = bounds.height
        when (arrow) {
            Arrow.LEFT -> width = max(width - STEP, min(MIN_SIZE, width))
            Arrow.RIGHT -> width += STEP
            Arrow.UP -> height = max(height - STEP, min(MIN_SIZE, height))
            Arrow.DOWN -> height += STEP
        }
        val center = Point(bounds.minX + bounds.width / 2, bounds.minY + bounds.height / 2)
        place(target, fitted(target, p, Rect(center.x - width / 2, center.y - height / 2, width, height)))
    }

    /** Removes the selected target */
    fun delete() {
        val target = selectedTarget() ?: return
        if (!canEdit(target)) return
        val id = selectedState.value ?: return
        selectedState.value = null
        targets.remove(id)
    }

    private fun selectedTarget(): PlacedTarget? {
        val id = selectedState.value ?: return null
        if (targets.owner(id) != TargetOwner.USER) return null
        return targets.set.get(id).orElse(null)
    }

    // Checks if the target can be edited: must be visible and have positive width and height
    private fun canEdit(target: PlacedTarget): Boolean {
        if (!target.isVisible) return false
        val bounds = target.bounds
        return bounds.width > 0 && bounds.height > 0
    }

    // The placement that gives [target] the bounds [wanted]: scaled from [from] by the bounds' change, then
    // moved so its corner lands where asked (a region that keeps its size can leave it a little off)
    private fun fitted(target: PlacedTarget, from: Placement, wanted: Rect): Placement {
        val was = target.boundsAt(from)
        val scaled = from.withScale(from.scaleX() * wanted.width / was.width, from.scaleY() * wanted.height / was.height)
        val now = target.boundsAt(scaled)
        return scaled.withPosition(scaled.x() + wanted.minX - now.minX, scaled.y() + wanted.minY - now.minY)
    }

    // Places [target] at [placement], moved back as far as it takes to keep part of it on the surface
    private fun place(target: PlacedTarget, placement: Placement) {
        val bounds = target.boundsAt(placement)
        val surface = size()
        val (dx, dy) = keepOn(bounds, surface)
        targets.set.place(target.id, placement.withPosition(placement.x() + dx, placement.y() + dy))
    }
}

/**
 * How far [bounds] must move to keep part of it on a [surface]-sized surface: a strip [TargetEditor.KEEP_ON]
 * deep on each axis, or the whole of a smaller target.
 */
fun keepOn(bounds: Rect, surface: Size): Pair<Double, Double> {
    fun shift(low: Double, high: Double, length: Double, extent: Double): Double {
        val strip = min(TargetEditor.KEEP_ON, length)
        return when {
            high < strip -> strip - high
            low > extent - strip -> extent - strip - low
            else -> 0.0
        }
    }
    return shift(bounds.minX, bounds.maxX, bounds.width, surface.width) to shift(bounds.minY, bounds.maxY, bounds.height, surface.height)
}
