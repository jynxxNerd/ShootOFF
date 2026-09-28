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
import com.shootoff.compose.app.sameCamera
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

/**
 * Where the arena's own calibration stands as it opens (spec §8 Revision 3): an automatic calibration waiting
 * for the projector, or one that didn't find the pattern; or the check of a remembered manual box
 */
sealed interface CheckState {
    /** Nothing under way, or the last check kept the calibration */
    data object Idle : CheckState

    /** An automatic calibration, waiting for the arena to reach the projector */
    data object WaitingToCalibrate : CheckState

    /** An automatic calibration didn't find the pattern in time, and ended without calibrating */
    data object NotFound : CheckState

    /** Waiting for the arena to reach the projector, or looking for the pattern */
    data object Checking : CheckState

    data class Moved(val pixels: Int) : CheckState

    data class NotVerified(val reason: Reason) : CheckState

    /** The saved calibration was made with another camera or projector, so it wasn't checked */
    data class DoesntFit(val why: String) : CheckState
}

/** Whether the pattern is on the arena for a check, or waiting to go on for a check or a calibration */
val CheckState.showsPattern: Boolean get() = this == CheckState.Checking || this == CheckState.WaitingToCalibrate

/** What Setup and Range say about a check, or null when there is nothing to say */
fun CheckState.text(): String? = when (this) {
    CheckState.Idle -> null
    CheckState.WaitingToCalibrate -> "Calibrating once the arena is on the projector…"
    CheckState.NotFound -> "The pattern wasn't found: not calibrated"
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
    // sameCamera (CameraSource.kt): a replug can renumber the camera onto another /dev/videoN, which is all
    // that differs between the saved name and the plugged-in one's
    !sameCamera(saved.camera, camera) -> "The saved calibration was made with another camera (${saved.camera})"
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

/** What a [PatternRun] does with the camera's frames: check a remembered calibration */
interface PatternWork<T : Any> {
    /** Looks at [frame]; the result, once there is one */
    fun offer(frame: BufferedImage): T?

    /** The result once the time is up, or null */
    fun tick(): T?
}

/** A check of [CalibrationCheck], as a [PatternRun] does it */
fun CalibrationCheck<BufferedImage>.work(): PatternWork<CalibrationCheck.Outcome> = object : PatternWork<CalibrationCheck.Outcome> {
    override fun offer(frame: BufferedImage) = this@work.offer(frame).orElse(null)

    override fun tick() = this@work.tick().orElse(null)
}

/**
 * The calibration pattern shown on the open arena, alone, while the camera's frames are looked at to check a
 * remembered calibration (spec §8). The frames are looked at in [scope], never on the UI thread, and [onDone]
 * hears the result on the UI thread once the arena's look is back, or null if the run was stopped. Shot
 * detection is off while the pattern shows, as in calibration, and asked back shortly after.
 *
 * @param work made as the pattern shows, so its time limit starts then
 */
class PatternRun<T : Any>(
    private val arena: ArenaModel,
    private val camera: CalibrationCamera,
    private val frames: LatestFrame,
    private val work: () -> PatternWork<T>,
    private val scope: CoroutineScope,
    private val scheduler: CalibrationFlow.Scheduler,
    private val uiThread: (Runnable) -> Unit,
    private val onDone: (PatternRun<T>, T?) -> Unit,
) {
    companion object {
        // How often a frame is looked at, at most; a detection itself takes about 400-500 ms on the owner's machine
        const val POLL_MILLIS = 50L
    }

    private val finished = AtomicBoolean(false)
    private var background: ArenaBackground? = null

    @Volatile
    private var job: Job? = null

    /** Shows the pattern, alone, and starts looking; on the UI thread. */
    fun start() {
        background = arena.background.value
        arena.cover(true)
        arena.showResource("pattern.png")
        camera.setDetecting(false)
        frames.take()
        val work = work()
        job = scope.launch {
            while (isActive) {
                val frame = frames.take()
                val result = if (frame != null) work.offer(frame) else work.tick()
                if (result != null) {
                    uiThread(Runnable { finish(result) })
                    return@launch
                }
                delay(POLL_MILLIS)
            }
        }
    }

    /**
     * Stops the run; on the UI thread. Never touches the work itself: a check's `offer` is `synchronized` and can hold its lock for as long as a detection takes (hundreds of milliseconds when
     * the pattern isn't found), which would freeze the UI thread here (spec §8 rule 6). [finish]'s
     * compare-and-set makes the first end final, so a result arriving after this is ignored.
     */
    fun stop() = finish(null)

    private fun finish(result: T?) {
        if (!finished.compareAndSet(false, true)) return
        job?.cancel()
        arena.setBackground(background)
        arena.cover(false)
        scheduler.schedule({ camera.setDetecting(true) }, CalibrationFlow.DETECTION_RESTART_DELAY)
        onDone(this, result)
    }
}
