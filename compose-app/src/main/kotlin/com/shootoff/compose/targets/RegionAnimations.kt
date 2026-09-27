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

import com.shootoff.targets.model.TargetId
import com.shootoff.targets.model.TargetSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** One image region of one target. */
data class RegionKey(val target: TargetId, val region: Int)

/** Runs animation steps later, on a background thread. */
fun interface AnimationClock {
    fun schedule(delayMillis: Long, step: () -> Unit)

    companion object {
        private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "Region animations").apply { isDaemon = true }
        }

        val background = AnimationClock { delayMillis, step -> executor.schedule(step, delayMillis, TimeUnit.MILLISECONDS) }
    }
}

/**
 * The animated image regions of one surface's targets: which frame each shows, as the JavaFX app's sprite
 * animations do. Playing steps through the frames once over the image's cycle, toward the last frame, or
 * toward the first once reversed. The current frame is what every view draws and what the hit tester sees
 * (its alpha mask goes to the [TargetSet]).
 *
 * A region's frame, its mask on the [TargetSet] and the published [frames] snapshot always change
 * together, inside the same critical section, so a step and a concurrent [reset] or [unregister] can
 * never interleave and leave the mask and the published frame disagreeing.
 */
class RegionAnimations(private val set: TargetSet, private val clock: AnimationClock = AnimationClock.background) {
    private class State(val image: RegionImage) {
        var frame = 0
        var reversed = false
        var running = false
        var generation = 0
        var reverseWhenDone = false
    }

    private val states = HashMap<RegionKey, State>()
    private val frameState = MutableStateFlow<Map<RegionKey, Int>>(emptyMap())

    /** The frame each animated region shows now */
    val frames: StateFlow<Map<RegionKey, Int>> = frameState.asStateFlow()

    /** A target joined the surface: its images start on their first frames. */
    fun register(target: TargetId, images: Map<Int, RegionImage>) {
        synchronized(this) {
            for ((region, image) in images) {
                val key = RegionKey(target, region)
                states[key] = State(image)
                set.setImageMask(target, region, image.masks[0])
            }
            publishLocked()
        }
    }

    /** A target left the surface: its regions' animations stop at once, even mid-play. */
    fun unregister(target: TargetId) {
        synchronized(this) {
            // Bumps the generation of every region of this target so a step already scheduled for it
            // sees the mismatch and stops instead of continuing to run (and reschedule itself) once gone.
            for ((key, state) in states) if (key.target == target) state.generation++
            states.keys.removeAll { it.target == target }
            publishLocked()
        }
    }

    fun isAnimated(key: RegionKey): Boolean = synchronized(this) { states[key]?.image?.animated ?: false }

    /** Whether the region shows the frame an animation starts from: the first, or the last once reversed. */
    fun isOnFirstFrame(key: RegionKey): Boolean = synchronized(this) {
        val state = states[key] ?: return true
        if (!state.image.animated) return true
        state.frame == if (state.reversed) state.image.frames.size - 1 else 0
    }

    fun frameOf(key: RegionKey): Int = synchronized(this) { states[key]?.frame ?: 0 }

    /**
     * Plays the region's animation once. With [resetAfter] it goes back to its first frame when done.
     */
    fun play(key: RegionKey, resetAfter: Boolean = false) {
        val (state, generation) = synchronized(this) {
            val state = states[key] ?: return
            if (!state.image.animated || state.running) return
            state.running = true
            state.generation++
            state to state.generation
        }
        val stepMillis = maxOf(1L, state.image.cycleMillis / state.image.frames.size)
        step(key, state, generation, stepMillis, resetAfter)
    }

    // One frame per step; the step after the last frame ends the play, a whole cycle after it began. The
    // frame's advance, its mask on the TargetSet and the published frame change together in one critical
    // section, so nothing can ever observe the mask and the frame disagreeing.
    private fun step(key: RegionKey, state: State, generation: Int, stepMillis: Long, resetAfter: Boolean) {
        clock.schedule(stepMillis) {
            var finished = false
            synchronized(this) {
                if (state.generation != generation) return@schedule
                val end = if (state.reversed) 0 else state.image.frames.size - 1
                finished = state.frame == end
                if (finished) {
                    state.running = false
                    if (state.reverseWhenDone) {
                        state.reverseWhenDone = false
                        state.reversed = !state.reversed
                    }
                } else {
                    state.frame += if (state.reversed) -1 else 1
                    set.setImageMask(key.target, key.region, state.image.masks[state.frame])
                    publishLocked()
                }
            }
            if (!finished) {
                step(key, state, generation, stepMillis, resetAfter)
            } else if (resetAfter) {
                reset(key)
            }
        }
    }

    /**
     * Reverses the region's animation, once the current play finishes if it is playing.
     */
    fun reverse(key: RegionKey) {
        synchronized(this) {
            val state = states[key] ?: return
            if (state.running) state.reverseWhenDone = true else state.reversed = !state.reversed
        }
    }

    /** Stops the region's animation and shows its first frame, as Reset does. */
    fun reset(key: RegionKey) {
        synchronized(this) {
            val state = states[key] ?: return
            state.generation++
            state.running = false
            state.reversed = false
            state.reverseWhenDone = false
            state.frame = 0
            set.setImageMask(key.target, key.region, state.image.masks[0])
            publishLocked()
        }
    }

    fun resetAll() {
        val keys = synchronized(this) { states.keys.toList() }
        keys.forEach(::reset)
    }

    // The frameState write for a region always happens together with its mask going to the TargetSet, in
    // the same critical section, so the two can never disagree. Must be called while holding this monitor.
    private fun publishLocked() {
        frameState.value = states.mapValues { it.value.frame }
    }
}
