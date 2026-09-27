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
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.camera.CameraCalibrationListener
import com.shootoff.camera.perspective.PerspectiveManager
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.config.CalibrationOption
import com.shootoff.config.SavedCalibration
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Optional

/**
 * What the calibration overlay shows.
 *
 * @param message the flow's current request of the user, if any
 * @param box the manual calibration box on the calibrating feed's canvas, while it is showing
 */
data class CalibrationUi(val calibrating: Boolean = false, val message: Message? = null, val box: Rect? = null)

/** The Compose app's words for the flow's messages (the JavaFX app keeps its own). */
fun Message.text(): String = when (this) {
    Message.FULL_SCREEN_REQUEST -> "Move the arena to the projector and press F11"
    Message.AUTO_CALIBRATING -> "Looking for the calibration pattern…"
    Message.MANUAL_REQUEST -> "Drag the box over the projection, then press Done"
}

/** What calibration asks of the rest of the app. */
interface CalibrationViews {
    /** The manual box is showing: the user must see the calibrating camera's feed (Setup). */
    fun showCalibratingFeed()

    fun restoreSelectedView()

    /**
     * A calibration the user asked for ended with a projection (found by the camera, or the box's).
     *
     * @param cameraBounds the projection on the camera's feed
     * @param paper the perspective paper's size, if auto-calibration found one
     */
    fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>)
}

/**
 * Calibrates the projector arena in the Compose app: core's [CalibrationFlow] decides what happens; this
 * shows it (the pattern on the arena, the overlay on the feed) and hands the user's actions and the
 * camera's findings back. The camera reports success on its own thread; the flow's timers come back on
 * [uiThread].
 */
class CalibrationController(
    private val camera: CalibrationCamera,
    private val arena: ArenaModel,
    private val settings: Settings,
    exercises: CalibrationFlow.Exercises,
    private val views: CalibrationViews,
    scheduler: CalibrationFlow.Scheduler,
    private val uiThread: (Runnable) -> Unit,
) : CalibrationFlow.View, CameraCalibrationListener {
    companion object {
        // The JavaFX app's default box: 150 px square, 75 px in from the feed's corner
        val DEFAULT_BOX = Rect(75.0, 75.0, 150.0, 150.0)
    }

    val flow = CalibrationFlow(camera, this, exercises, settings, scheduler, Optional.empty())

    private val uiState = MutableStateFlow(CalibrationUi())
    val state: StateFlow<CalibrationUi> = uiState.asStateFlow()

    @Volatile
    private var savedBackground: ArenaBackground? = null

    // Whether savedBackground actually holds a save: calibration can be cancelled before it ever reaches
    // the pattern (still asking for full screen), when there is nothing to restore
    @Volatile
    private var backgroundSaved = false

    // Bumped whenever the arena closes, so a calibrated() completion queued on the UI thread beforehand
    // (the camera found the pattern just as the window went away) finds out it is stale and does nothing
    @Volatile
    private var generation = 0

    // The camera's projection when calibration started, which Cancel puts back
    @Volatile
    private var boundsBefore: Rect? = null

    // Whether a calibration the user started is under way, so only its end is reported as a success
    @Volatile
    private var session = false

    // The paper size auto-calibration found this session, if any
    @Volatile
    private var foundPaper: Optional<Size> = Optional.empty()

    // ---- The user

    /**
     * Starts calibrating; only ever because the user asked (Calibrate on Setup, or F6). The flow is told
     * whether the arena is full screen as it starts, so a start on an arena that is already full screen
     * looks for the pattern at once and its timeout reaches the manual box.
     */
    fun start() {
        if (flow.isCalibrating) return
        boundsBefore = camera.projectionBounds.orElse(null)
        foundPaper = Optional.empty()
        session = true
        flow.setFullScreen(arena.fullScreen.value)
    }

    /**
     * Cancel: calibration ends and the arena and the camera are left as they were before it started (the
     * background, targets, shots and the projection). A drill it stopped stays stopped.
     */
    fun cancel() {
        if (!flow.isCalibrating) return
        generation++
        session = false
        flow.cancel()
        putArenaBack()
        camera.setProjectionBounds(boundsBefore)
        uiState.update { CalibrationUi() }
    }

    /** Starts calibrating, or ends it (with the box, if it is showing). */
    fun toggle() = if (flow.isCalibrating) flow.stop() else start()

    /** Applies a calibration remembered from an earlier session, without calibrating. */
    fun applySaved(saved: SavedCalibration) = flow.applySaved(saved.bounds, saved.paper)

    /** Moves or resizes the box, keeping it inside the canvas (the settings' display size). */
    fun moveBox(box: Rect) = uiState.update { if (it.box != null) it.copy(box = clampToCanvas(box)) else it }

    private fun clampToCanvas(box: Rect): Rect {
        val canvasWidth = settings.displayWidth.toDouble()
        val canvasHeight = settings.displayHeight.toDouble()
        val width = box.width.coerceIn(MIN_BOX, canvasWidth.coerceAtLeast(MIN_BOX))
        val height = box.height.coerceIn(MIN_BOX, canvasHeight.coerceAtLeast(MIN_BOX))
        val minX = box.minX.coerceIn(0.0, (canvasWidth - width).coerceAtLeast(0.0))
        val minY = box.minY.coerceIn(0.0, (canvasHeight - height).coerceAtLeast(0.0))
        return Rect(minX, minY, width, height)
    }

    /**
     * The arena window went full screen or left it. It matters only while calibrating: unlike the JavaFX
     * app, the arena going full screen never starts calibration.
     */
    fun fullScreenChanged(fullScreen: Boolean) {
        if (flow.isCalibrating) flow.setFullScreen(fullScreen)
    }

    /**
     * The arena window is closing: calibration ends abruptly, through [CalibrationFlow.cancel] if it was
     * still going (no calibrating to the box, no drill restart; ruling 10), so the camera is left in a
     * normal, not-calibrating state rather than stuck mid-calibration for whatever reopens it. The model
     * itself is put back exactly as it was before calibration started (background, targets, shots), since
     * [CalibrationFlow.cancel] does neither. The flow's own [CalibrationFlow.arenaClosing] always runs
     * too, clearing the camera's projection.
     */
    fun arenaClosing() {
        generation++
        session = false
        if (flow.isCalibrating) {
            flow.cancel()
            putArenaBack()
        }
        uiState.update { CalibrationUi() }
        flow.arenaClosing()
        arena.setProjection(null)
    }

    // The arena's look before calibration: its background, targets, shots, and the label if uncalibrated
    private fun putArenaBack() {
        restoreArenaBackground()
        arena.setTargetsVisible(true)
        arena.showShots(settings.showArenaShotMarkers())
        arena.setCalibrationLabelVisible(arena.projection.value == null)
    }

    // ---- The camera (CameraCalibrationListener)

    /**
     * The camera reports success on its own thread. Completing calibration touches the view (and can
     * restart a drill), so the whole of it runs on the UI thread, not the camera's. If the arena closes
     * (or calibration is cancelled) before this reaches the UI thread, it does nothing: either the
     * [generation] it captured here no longer matches, or (a frame already in flight when it closed can
     * still read the new generation) the flow just isn't calibrating any more.
     */
    override fun calibrate(arenaBounds: Rect, perspectivePaperDims: Optional<Size>, calibratedFromCanvas: Boolean, frameDelay: Long) {
        val expectedGeneration = generation
        uiThread(
            Runnable {
                if (generation == expectedGeneration && flow.isCalibrating) {
                    foundPaper = perspectivePaperDims
                    flow.calibrated(arenaBounds, perspectivePaperDims, calibratedFromCanvas)
                }
            },
        )
    }

    /** Auto-calibration's exposure step shows a white screen, then none */
    override fun setArenaBackground(resourceFilename: String?) = arena.showResource(resourceFilename)

    // ---- The flow (CalibrationFlow.View)

    override fun isArenaFullScreen(): Boolean = arena.fullScreen.value

    override fun setArenaShotsVisible(visible: Boolean) = arena.showShots(visible)

    override fun setCalibrating(calibrating: Boolean) = uiState.update { it.copy(calibrating = calibrating) }

    override fun calibrationStarted() {
        arena.setTargetsVisible(false)
        arena.setCalibrationLabelVisible(false)
    }

    override fun saveArenaBackground() {
        savedBackground = arena.background.value
        backgroundSaved = true
    }

    override fun restoreArenaBackground() {
        if (backgroundSaved) arena.setBackground(savedBackground)
        savedBackground = null
        backgroundSaved = false
    }

    override fun showPattern() = arena.showResource("pattern.png")

    override fun showMessage(message: Message) = uiState.update { it.copy(message = message) }

    override fun hideMessage(message: Message) = uiState.update { if (it.message == message) it.copy(message = null) else it }

    override fun showCalibratingFeed() = views.showCalibratingFeed()

    override fun restoreSelectedView() = views.restoreSelectedView()

    override fun showManualBox() = uiState.update { it.copy(box = DEFAULT_BOX) }

    override fun manualBox(): Optional<Rect> = Optional.ofNullable(uiState.value.box)

    override fun removeManualBox() = uiState.update { it.copy(box = null) }

    override fun projectionCalibrated(canvasBounds: Rect) = arena.setProjection(canvasBounds)

    override fun calibratedFeedBehavior(): CalibrationOption = settings.calibratedFeedBehavior

    override fun arenaResolution(): Size = arena.size.value

    override fun calibrated(perspectiveManager: Optional<PerspectiveManager>) {
        arena.perspective = perspectiveManager.orElse(null)
        arena.setCalibrationLabelVisible(false)
        arena.setTargetsVisible(true)
        // Targets take their real-world sizes once the perspective is known
        if (perspectiveManager.isPresent) {
            for (target in arena.targets.set.targets) arena.placeNewTarget(target)
        }
        // A calibration the user started, not a remembered one being applied, and one that found the projection
        if (session) {
            session = false
            camera.projectionBounds.ifPresent { views.calibrationSucceeded(it, foundPaper) }
        }
    }

    override fun runOnUiThread(action: Runnable) = uiThread(action)
}
