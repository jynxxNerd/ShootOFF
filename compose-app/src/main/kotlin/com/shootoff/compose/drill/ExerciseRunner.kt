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

import com.shootoff.calibration.CalibrationFlow
import com.shootoff.camera.Shot
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.exercise.Exercise
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.util.Optional

/** The exercise the Compose app is running, and its host. */
class Running(val entry: V2ExerciseEntry, val host: ComposeExerciseHost)

/**
 * The Compose app's current exercise: at most one runs. Starting one stops the one before. Shots reach
 * it by surface: a projector exercise takes arena shots only; a camera exercise takes camera shots, and
 * arena shots too when it runs everywhere (exercise port spec §5). A camera shot that the pipeline passes
 * on to the arena arrives once, as an arena shot. Calibration
 * pauses a projector exercise, and a camera exercise that runs everywhere (see AppState.pauseDrill); a
 * projector exercise with no Pause button is stopped through [stopProjectorExercise] and started afresh
 * afterwards.
 *
 * @param isCalibrating whether the arena is currently calibrating; a projector drill refuses to start
 *   while it is, so no path (the Drills screen or otherwise) can start one under calibration's feet
 * @param newHost makes a host for a fresh instance of an entry's exercise on the surface it runs on;
 *   null when that surface isn't there (no arena for a projector exercise)
 */
class ExerciseRunner(
    private val isCalibrating: () -> Boolean = { false },
    private val newHost: (V2ExerciseEntry, Exercise, ExerciseRunner) -> ComposeExerciseHost?,
) : ShotReceiver, CalibrationFlow.Exercises {
    private val logger = LoggerFactory.getLogger(ExerciseRunner::class.java)
    private val runningState = MutableStateFlow<Running?>(null)
    private val failureState = MutableStateFlow<String?>(null)

    val running: StateFlow<Running?> = runningState.asStateFlow()

    /** Why the last exercise stopped on its own, until dismissed */
    val failure: StateFlow<String?> = failureState.asStateFlow()

    /**
     * Stops the running exercise and starts a fresh instance of [entry]. A projector drill refuses to
     * start while the arena is calibrating: its target would draw over the calibration pattern, and
     * shot detection is off, so it would look dead. The running exercise, if any, is left alone.
     *
     * @return false if it couldn't start (logged)
     */
    @Synchronized
    fun start(entry: V2ExerciseEntry): Boolean {
        if (entry.isProjectorOnly && isCalibrating()) {
            logger.info("{} can't start while the arena is calibrating", entry.metadata().name)
            return false
        }

        stop()
        failureState.value = null

        val exercise = try {
            entry.newInstance()
        } catch (e: ReflectiveOperationException) {
            logger.error("Failed to start exercise {} {}", entry.metadata().name, entry.metadata().version, e)
            failureState.value = "${entry.metadata().name} couldn't start: ${e.javaClass.simpleName}"
            return false
        }

        val host = newHost(entry, exercise, this) ?: run {
            logger.error("{} needs the projector arena", entry.metadata().name)
            return false
        }
        runningState.value = Running(entry, host)
        host.start()
        return true
    }

    @Synchronized
    fun stop() {
        val running = runningState.value ?: return
        runningState.value = null
        running.host.stop()
    }

    fun reset() {
        runningState.value?.host?.reset()
    }

    fun dismissFailure() {
        failureState.value = null
    }

    /**
     * An exercise's callback threw: the exercise is stopped, off its own thread, and the user told why.
     */
    fun failed(host: ComposeExerciseHost, error: Throwable) {
        if (runningState.value?.host !== host) return
        failureState.value = "${host.name} stopped: ${error.javaClass.simpleName}" + (error.message?.let { ": $it" } ?: "")
        Thread({
            synchronized(this) {
                if (runningState.value?.host === host) stop()
            }
        }, "Stopping ${host.name}").start()
    }

    override fun deliver(shot: Shot, hit: Hit?, arenaShot: Boolean): Boolean {
        val host = runningState.value?.host ?: return false
        val takes = if (arenaShot) host.takesArenaShots else !host.isProjector
        if (!takes) return true
        host.deliverShot(shot, hit)
        return true
    }

    /** Calibration stops a projector exercise first; this starts it again afterwards. */
    // Synchronized with start and stop, so a drill started meanwhile is neither stopped nor replaced
    @Synchronized
    override fun stopProjectorExercise(): Optional<Runnable> {
        val running = runningState.value
        if (running == null || !running.host.isProjector) return Optional.empty()

        stop()
        return Optional.of(Runnable { start(running.entry) })
    }
}
