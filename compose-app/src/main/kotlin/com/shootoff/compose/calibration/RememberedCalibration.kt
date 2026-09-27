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

package com.shootoff.compose.calibration

import com.shootoff.calibration.CalibrationCamera
import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationCheck.Reason
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.config.SavedCalibration
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Where the check of a remembered calibration stands */
sealed interface CheckState {
    /** No check, or the last one kept the calibration */
    data object Idle : CheckState

    /** Waiting for the arena to reach the projector, or looking for the pattern */
    data object Checking : CheckState

    data class Moved(val pixels: Int) : CheckState

    data class NotVerified(val reason: Reason) : CheckState

    /** The saved calibration was made with another camera or projector, so it wasn't checked */
    data class DoesntFit(val why: String) : CheckState
}

/** What Setup and Range say about a check, or null when there is nothing to say */
fun CheckState.text(): String? = when (this) {
    CheckState.Idle -> null
    CheckState.Checking -> "Checking the saved calibration…"
    is CheckState.Moved -> "The projection moved about $pixels px — recalibrate"
    is CheckState.NotVerified -> when (reason) {
        Reason.PATTERN_NOT_SEEN -> "The pattern wasn't seen: not verified — recalibrate on Setup"
        Reason.NO_CAMERA -> "No camera to check with: not verified — recalibrate on Setup"
        Reason.CANCELLED -> "Check cancelled: not verified — recalibrate on Setup"
    }
    is CheckState.DoesntFit -> "$why — recalibrate"
}

/**
 * Why a saved calibration can't be used with this camera and projector screen, or null when it can
 * (and so is worth checking).
 *
 * @param screen the projector screen the arena is on; null when none was found
 */
fun savedCalibrationMismatch(saved: SavedCalibration, camera: String, feed: Size, screen: Rect?): String? = when {
    screen == null -> "No projector screen found"
    saved.camera != camera -> "The saved calibration was made with another camera (${saved.camera})"
    saved.feed != feed -> "The saved calibration was made at ${describe(saved.feed)}; the camera is at ${describe(feed)}"
    saved.screen != Size(screen.width, screen.height) ->
        "The saved calibration was made for a ${describe(saved.screen)} projector; this one is ${describe(Size(screen.width, screen.height))}"
    else -> null
}

private fun describe(size: Size) = "${size.width.roundToInt()}×${size.height.roundToInt()}"

/** The newest camera frame, kept for the check to take; older ones are dropped. */
class LatestFrame {
    private val frame = AtomicReference<BufferedImage?>()

    fun offer(image: BufferedImage) = frame.set(image)

    fun take(): BufferedImage? = frame.getAndSet(null)
}

/**
 * One check of a remembered calibration on the open arena (spec §8): the pattern shows on the arena,
 * the camera's frames are looked at in [scope], never on the UI thread, and [onDone] hears the outcome
 * on the UI thread once the arena's background is back. Shot detection is off while the pattern shows,
 * as in calibration, and comes back shortly after.
 */
class CalibrationCheckRun(
    private val saved: SavedCalibration,
    private val arena: ArenaModel,
    private val camera: CalibrationCamera,
    private val frames: LatestFrame,
    private val detector: CalibrationCheck.Detector<BufferedImage>,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val scheduler: CalibrationFlow.Scheduler,
    private val uiThread: (Runnable) -> Unit,
    private val onDone: (CalibrationCheckRun, CalibrationCheck.Outcome) -> Unit,
) {
    companion object {
        // How often a frame is looked at; detection itself takes a few tens of milliseconds
        const val POLL_MILLIS = 50L
    }

    private val finished = AtomicBoolean(false)
    private var background: ArenaBackground? = null

    @Volatile
    private var check: CalibrationCheck<BufferedImage>? = null

    @Volatile
    private var job: Job? = null

    /** Shows the pattern and starts looking; on the UI thread. */
    fun start() {
        background = arena.background.value
        arena.showResource("pattern.png")
        camera.setDetecting(false)
        frames.take()
        val check = CalibrationCheck(saved.bounds, detector, clock)
        this.check = check
        job = scope.launch {
            while (isActive) {
                val frame = frames.take()
                val outcome = (if (frame != null) check.offer(frame) else check.tick()).orElse(null)
                if (outcome != null) {
                    uiThread(Runnable { finish(outcome) })
                    return@launch
                }
                delay(POLL_MILLIS)
            }
        }
    }

    /** Stops the check for [reason], unless it already has an outcome; on the UI thread. */
    fun stop(reason: Reason) {
        val outcome = check?.stop(reason) ?: CalibrationCheck.NotVerified(reason)
        finish(outcome)
    }

    private fun finish(outcome: CalibrationCheck.Outcome) {
        if (!finished.compareAndSet(false, true)) return
        job?.cancel()
        arena.setBackground(background)
        scheduler.schedule({ camera.setDetecting(true) }, CalibrationFlow.DETECTION_RESTART_DELAY)
        onDone(this, outcome)
    }
}
