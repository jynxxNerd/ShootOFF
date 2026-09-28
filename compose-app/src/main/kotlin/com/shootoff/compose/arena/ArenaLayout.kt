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

package com.shootoff.compose.arena

import com.shootoff.compose.targets.AnimationClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.geom.Size
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the arena holds whether or not its window is open: its targets, the background the shooter picked,
 * and its size. Each arena window's [ArenaModel] draws it, so the shooter's layout survives the window
 * closing, and the Targets screen edits it while the window is closed.
 */
class ArenaLayout(clock: AnimationClock = AnimationClock.background) {
    /** Every target on the arena, the shooter's and a running exercise's */
    val targets = SurfaceTargets(clock = clock)

    private val backgroundState = MutableStateFlow<ArenaBackground?>(null)
    private val sizeState = MutableStateFlow(Size(640.0, 480.0))
    private val backgroundListeners = CopyOnWriteArrayList<Runnable>()
    private val screenSizeState = MutableStateFlow<Size?>(null)
    private val lastingSizeListeners = CopyOnWriteArrayList<Runnable>()

    /**
     * The shooter's background: what the arena shows unless calibration or an exercise has put up one of its
     * own ([ArenaModel.background])
     */
    val background: StateFlow<ArenaBackground?> = backgroundState.asStateFlow()

    /** The arena's size, in its own units: the open window's, or the last one's while it is closed */
    val size: StateFlow<Size> = sizeState.asStateFlow()

    fun setBackground(background: ArenaBackground?) {
        backgroundState.value = background
        backgroundListeners.forEach(Runnable::run)
    }

    /** [listener] runs, on the caller's thread, whenever the shooter's background is set */
    fun addBackgroundListener(listener: Runnable) {
        backgroundListeners += listener
    }

    /** [listener] runs, on the caller's thread, whenever the [lastingSize] actually changes */
    fun addLastingSizeListener(listener: Runnable) {
        lastingSizeListeners += listener
    }

    /**
     * The size layouts are recorded at: the projector screen's when the arena belongs on one, since targets stay
     * in its units while the window is smaller (F11 out); else the arena's own
     */
    val lastingSize: Size get() = screenSizeState.value ?: sizeState.value

    /** The size of the projector screen the arena belongs on, or null if there is none */
    fun setScreenSize(size: Size?) = changeLastingSize { screenSizeState.value = size }

    fun setSize(size: Size) = changeLastingSize { sizeState.value = size }

    // Tells the listeners if the change moved the lasting size; changes are serialized so none is missed
    private fun changeLastingSize(change: () -> Unit) {
        val moved = synchronized(this) {
            val before = lastingSize
            change()
            lastingSize != before
        }
        if (moved) lastingSizeListeners.forEach(Runnable::run)
    }
}
