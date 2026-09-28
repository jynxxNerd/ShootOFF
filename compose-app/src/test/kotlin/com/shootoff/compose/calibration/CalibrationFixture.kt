package com.shootoff.compose.calibration

import com.shootoff.calibration.CalibrationCamera
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.compose.shots.RegionCommandRunner
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A controller on a fake camera, with the flow's timers and the UI thread under the test's control.
 *
 * @param uiThread runs a UI-thread task; defaults to running it immediately, so most tests don't need to
 *        pump anything. A test proving work hops to the UI thread passes its own, e.g. a queue.
 */
class CalibrationFixture(private val uiThread: (Runnable) -> Unit = { it.run() }) {
    val events = CopyOnWriteArrayList<String>()
    val timers = mutableListOf<Pair<Long, Runnable>>()
    val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    var restartExercise: Optional<Runnable> = Optional.empty()

    inner class FakeCamera : CalibrationCamera {
        var bounds: Rect? = null

        // What auto-calibration found beyond the bounds (in CameraManager: the perspective warp and paper size);
        // starting to look for the pattern throws it away
        var warp: String? = null

        override fun getName() = "C270"

        override fun getFeedWidth() = 640

        override fun getFeedHeight() = 480

        override fun getProjectionBounds(): Optional<Rect> = Optional.ofNullable(bounds)

        override fun setProjectionBounds(projectionBounds: Rect?) {
            bounds = projectionBounds
        }

        override fun setCalibrating(isCalibrating: Boolean) {
            events += "camera calibrating $isCalibrating"
        }

        override fun setDetecting(isDetecting: Boolean) {
            events += "camera detecting $isDetecting"
        }

        override fun enableAutoCalibration(calculateFrameDelay: Boolean) {
            events += "auto on"
            warp = null
        }

        override fun disableAutoCalibration() {
            events += "auto off"
        }

        override fun setCropFeedToProjection(cropFeed: Boolean) {}

        override fun setLimitDetectProjection(limitDetection: Boolean) {}

        override fun saveCalibration(): CalibrationCamera.Saved = Saved(bounds, warp)

        override fun restoreCalibration(saved: CalibrationCamera.Saved) {
            saved as Saved
            events += "camera restored"
            bounds = saved.bounds
            warp = saved.warp
        }
    }

    data class Saved(val bounds: Rect?, val warp: String?) : CalibrationCamera.Saved

    val camera = FakeCamera()

    val arena: ArenaModel = ArenaModel(
        settings,
        { ShotReceiver.None },
        { RegionCommandRunner(arena.targets, settings, {}, { null }) },
        ManualClock(),
    ).also { it.setSize(Size(1280.0, 720.0)) }

    val views = object : CalibrationViews {
        override fun showCalibratingFeed() {
            events += "show feed"
        }

        override fun restoreSelectedView() {
            events += "restore view"
        }

        override fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>, byCamera: Boolean) {
            events += "succeeded $cameraBounds $paper"
        }
    }

    val controller = CalibrationController(
        camera,
        arena,
        settings,
        {
            events += "stop exercise"
            restartExercise
        },
        views,
        CalibrationFlow.Scheduler { task, delay ->
            timers += delay to task
            CompletableFuture<Void>()
        },
        uiThread,
    )

    /** Runs the pending timer that was set for [delay] */
    fun fire(delay: Long) {
        val timer = timers.first { it.first == delay }
        timers.remove(timer)
        timer.second.run()
    }
}
