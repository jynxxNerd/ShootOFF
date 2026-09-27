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

package com.shootoff.compose.app

import com.shootoff.camera.CameraManager
import com.shootoff.camera.CameraView
import com.shootoff.camera.CamerasSupervisor
import com.shootoff.camera.DiagnosticMessage
import com.shootoff.camera.cameratypes.Camera
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.compose.arena.ArenaPlacement
import com.shootoff.compose.arena.ArenaScreens
import com.shootoff.compose.calibration.CalibrationController
import com.shootoff.compose.calibration.CalibrationViews
import com.shootoff.compose.drill.ArenaHostSurface
import com.shootoff.compose.drill.ComposeExerciseHost
import com.shootoff.compose.drill.DrillState
import com.shootoff.compose.drill.ExerciseRunner
import com.shootoff.compose.drill.FeedHostSurface
import com.shootoff.compose.drill.HostContext
import com.shootoff.compose.drill.SoundOutput
import com.shootoff.compose.feed.CalibrationStatus
import com.shootoff.compose.feed.ComposeCameraView
import com.shootoff.compose.feed.FeedState
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.shots.FeedSurface
import com.shootoff.compose.shots.RegionCommandRunner
import com.shootoff.compose.shots.ShotMarkers
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.targets.AnimationClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.Settings
import com.shootoff.exercise.Exercise
import com.shootoff.geom.ArenaGeometry
import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.shots.RangeReset
import com.shootoff.util.TimerPool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.time.LocalTime
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** The Range screen's big view */
enum class BigView { CAMERA, ARENA }

const val MIN_TRAY_HEIGHT = 120f
const val MAX_TRAY_HEIGHT = 480f

/**
 * The Compose app's state: the camera and its feed, the arena, calibration, the running drill, the shot
 * timer, and where the user is. Composables read it; user actions call it.
 *
 * @param screens the screens the arena can go on, in AWT's coordinates
 * @param uiThread runs calibration's timers, and the results of camera work, on the UI thread
 * @param background runs the app's watchers
 * @param io opens and lists cameras, which can take seconds, off the UI thread
 * @param wallClock the time of day, which the calibration status shows
 */
class AppState(
    val settings: Settings,
    val catalog: ExerciseCatalog = ExerciseCatalog(),
    private val cameraSource: CameraSource = CameraSource.None,
    private val screens: () -> List<Rect> = ArenaScreens::screens,
    private val clock: AnimationClock = AnimationClock.background,
    private val uiThread: (Runnable) -> Unit = EventQueue::invokeLater,
    background: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    val prefs: UiPrefs = UiPrefs(),
    private val wallClock: () -> LocalTime = LocalTime::now,
) : CalibrationViews {
    private val logger = LoggerFactory.getLogger(AppState::class.java)
    private val scope = CoroutineScope(SupervisorJob() + background)

    val displaySize = Size(settings.displayWidth.toDouble(), settings.displayHeight.toDouble())
    val cameras = CamerasSupervisor(settings)
    val timer = ShotTimerModel()
    val drill = DrillState()
    val feed = FeedState(displaySize)
    val feedTargets = SurfaceTargets(clock = clock)
    val feedMarkers = ShotMarkers()

    val rangeReset = RangeReset(cameras, { calibration.value?.flow?.isCalibrating ?: false }, { task, delay -> TimerPool.schedule(task, delay) })
    val runner: ExerciseRunner = ExerciseRunner(
        isCalibrating = { calibrationState.value?.state?.value?.calibrating == true },
        newHost = ::newHost,
    )

    private val arenaState = MutableStateFlow<ArenaModel?>(null)
    private val placementState = MutableStateFlow<ArenaPlacement?>(null)
    private val calibrationState = MutableStateFlow<CalibrationController?>(null)
    private val cameraState = MutableStateFlow<CameraManager?>(null)
    private val problemState = MutableStateFlow<String?>(null)
    private val openingState = MutableStateFlow<String?>(null)
    private val cameraListState = MutableStateFlow<List<Camera>?>(null)

    // Which camera open is the latest: an open that finishes after a newer one (or after the app closed) is dropped
    private val openGeneration = AtomicInteger()

    // The open camera's view of the feed
    private var openView: OpenView? = null
    private val destinationState = MutableStateFlow(Destination.RANGE)
    private val viewState = MutableStateFlow(BigView.CAMERA)
    private val darkState = MutableStateFlow(prefs.dark)
    private val trayHeightState = MutableStateFlow(prefs.trayHeight)
    private val trayCollapsedState = MutableStateFlow(prefs.trayCollapsed)
    private val calibratedAtState = MutableStateFlow<LocalTime?>(null)
    private var viewBeforeCalibration: BigView? = null
    private var fullScreenWatch: Job? = null

    /** The open arena, or null */
    val arena: StateFlow<ArenaModel?> = arenaState.asStateFlow()

    /** Where the arena window opens, while it is open */
    val arenaPlacement: StateFlow<ArenaPlacement?> = placementState.asStateFlow()

    val calibration: StateFlow<CalibrationController?> = calibrationState.asStateFlow()

    /** When the open arena was last calibrated; null while it isn't */
    val calibratedAt: StateFlow<LocalTime?> = calibratedAtState.asStateFlow()

    /** The open camera, or null */
    val camera: StateFlow<CameraManager?> = cameraState.asStateFlow()

    /** Why there is no camera, if something went wrong */
    val cameraProblem: StateFlow<String?> = problemState.asStateFlow()

    /** The name of the camera being opened in the background, or null */
    val openingCamera: StateFlow<String?> = openingState.asStateFlow()

    /** The cameras plugged in, as last found by [refreshCameras]; null until found */
    val cameraList: StateFlow<List<Camera>?> = cameraListState.asStateFlow()

    /** Problems for the user from code without a user interface (see Settings.setUserNotifier) */
    val notices = Notices()

    val destination: StateFlow<Destination> = destinationState.asStateFlow()
    val view: StateFlow<BigView> = viewState.asStateFlow()

    /** Range dark, or its light variant */
    val dark: StateFlow<Boolean> = darkState.asStateFlow()

    /** The tray's height in dp, and whether it is folded down to its title bar */
    val trayHeight: StateFlow<Float> = trayHeightState.asStateFlow()
    val trayCollapsed: StateFlow<Boolean> = trayCollapsedState.asStateFlow()

    val feedSurface = FeedSurface(
        "Default",
        settings,
        feedTargets,
        timer,
        feedMarkers,
        { runner },
        { feedCommands },
        { arenaState.value?.surface },
        { arenaState.value?.projection?.value },
    )

    val cameraView = ComposeCameraView("Default", feed, feedSurface)

    /**
     * What the cameras report their troubles to. A camera is lost on its own thread; the app closes it, and
     * the arena it calibrated, on the UI thread, where the arena and calibration are changed.
     */
    val cameraProblems = CameraProblems(settings, feed, { problemState.value = it }) { camera -> uiThread(Runnable { cameraLost(camera) }) }

    private val feedCommands = RegionCommandRunner(feedTargets, settings, ::reset, ::exerciseResources)

    // ---- Where the user is

    fun navigate(destination: Destination) {
        if (destination.enabled) destinationState.value = destination
    }

    fun showView(view: BigView) {
        if (view == BigView.ARENA && arenaState.value == null) return
        viewState.value = view
        prefs.view = view
    }

    fun setDark(dark: Boolean) {
        darkState.value = dark
        prefs.dark = dark
    }

    /** The user dragged the tray's edge */
    fun setTrayHeight(height: Float) {
        val clamped = height.coerceIn(MIN_TRAY_HEIGHT, MAX_TRAY_HEIGHT)
        trayHeightState.value = clamped
        prefs.trayHeight = clamped
    }

    fun setTrayCollapsed(collapsed: Boolean) {
        trayCollapsedState.value = collapsed
        prefs.trayCollapsed = collapsed
    }

    override fun showCalibratingFeed() {
        viewBeforeCalibration = viewState.value
        viewState.value = BigView.CAMERA
    }

    override fun restoreSelectedView() {
        viewBeforeCalibration?.let { viewState.value = it }
        viewBeforeCalibration = null
    }

    // ---- The camera

    /** Opens the camera the app starts with, if there is one */
    fun openStartCamera() {
        cameraSource.startCamera(settings)?.let(::openCamera)
    }

    /** Looks for the cameras plugged in, off the UI thread (it can take seconds), and publishes them in [cameraList]. */
    fun refreshCameras() {
        scope.launch(io) {
            cameraListState.value = try {
                cameraSource.cameras()
            } catch (e: Exception) {
                logger.error("Couldn't list the cameras", e)
                emptyList()
            }
        }
    }

    /**
     * Opens [camera] in place of the open one, on the calling thread. It can block for as long as the
     * hardware takes; the UI uses [openCameraInBackground].
     *
     * @return false if it can't be opened
     */
    fun openCamera(camera: Camera): Boolean {
        if (cameraState.value?.camera === camera) return true
        val (generation, old) = releaseCamera()
        old?.let(::closeDevice)
        return publish(generation, startCamera(camera))
    }

    /**
     * Opens [camera] in place of the open one without blocking the calling (UI) thread: the open one, and
     * the arena it calibrated, close at once; [openingCamera] names [camera] while it opens on the I/O
     * dispatcher; then [then] hears on the UI thread whether it opened. One camera opens at a time.
     *
     * @return false if the pick was ignored, because another camera is still opening
     */
    fun openCameraInBackground(camera: Camera, then: (Boolean) -> Unit = {}): Boolean {
        if (openingState.value != null) return false
        if (cameraState.value?.camera === camera) {
            then(true)
            return true
        }
        val (generation, old) = releaseCamera()
        openingState.value = camera.name
        scope.launch(io) {
            // The old device closes before the new one opens, in case they are the same hardware
            old?.let(::closeDevice)
            val opened = startCamera(camera)
            // Dropped already (a newer open, or the app closing): cleaned up here, off the UI thread
            if (generation != openGeneration.get()) {
                discard(opened)
            } else {
                uiThread(Runnable { then(publish(generation, opened)) })
            }
        }
        return true
    }

    // A camera open's outcome: its manager if it started, else why not
    private class Opened(val camera: Camera, val view: OpenView, val manager: CameraManager?, val error: Throwable? = null)

    // Starts a manager for [camera], blocking on the hardware. It isn't registered with [cameras] (only
    // [publish] does that, on the UI thread), and a camera that fails to start is closed again.
    private fun startCamera(camera: Camera): Opened {
        val view = OpenView(cameraView)
        var manager: CameraManager? = null
        return try {
            manager = CameraManager(camera, cameraProblems, view)
            if (manager.start()) {
                Opened(camera, view, manager)
            } else {
                discard(Opened(camera, view, manager))
                Opened(camera, view, null)
            }
        } catch (e: Exception) {
            logger.error("Cannot open the webcam {}", camera.name, e)
            discard(Opened(camera, view, manager))
            Opened(camera, view, null, e)
        }
    }

    // Closes a camera that won't be shown, without touching the live feed
    private fun discard(opened: Opened) {
        opened.view.live = false
        try {
            opened.manager?.close()
        } catch (e: Exception) {
            logger.warn("Couldn't close the manager of webcam {}", opened.camera.name, e)
        }
        closeDevice(opened.camera)
    }

    // CameraManager.close() leaves the device open; this closes it (it can block)
    private fun closeDevice(camera: Camera) {
        try {
            camera.close()
        } catch (e: Exception) {
            logger.warn("Couldn't close the webcam {}", camera.name, e)
        }
    }

    private fun closeDeviceLater(camera: Camera) = io.asExecutor().execute { closeDevice(camera) }

    // Closes the open camera's manager, and the arena it calibrated, as the JavaFX app's arena closes with its
    // camera (ruling 13). Returns the new open's generation and the camera whose device is still to close.
    private fun releaseCamera(): Pair<Int, Camera?> {
        val generation = openGeneration.incrementAndGet()
        openingState.value = null
        val old = cameraState.value
        if (old != null) closeArena()
        old?.let(cameras::clearManager)
        openView?.live = false
        openView = null
        cameraState.value = null
        feed.clearFrame()
        return generation to old?.camera
    }

    // An open finished, on the UI thread: shows its camera, or why it couldn't open, unless a newer open (or
    // the app closing) has replaced it
    private fun publish(generation: Int, opened: Opened): Boolean {
        if (generation != openGeneration.get()) {
            if (opened.manager != null) {
                opened.view.live = false
                io.asExecutor().execute { discard(opened) }
            }
            return false
        }
        openingState.value = null
        val manager = opened.manager
        if (manager == null) {
            val error = opened.error
            if (error != null) {
                cameraProblems.showOpenError(opened.camera, error)
            } else {
                logger.error("Cannot open the webcam {}", opened.camera.name)
                cameraProblems.showCameraLockError(opened.camera, false)
            }
            return false
        }
        cameras.addStartedCameraManager(manager)
        cameraView.setCameraManager(manager)
        openView = opened.view
        problemState.value = null
        cameraState.value = manager
        // The arena was already open with no camera to calibrate with (openArena found none); now one is
        // here, so the arena can be calibrated, as it could have been if the camera had come first
        arenaState.value?.let { arena -> if (calibrationState.value == null) makeCalibratable(arena, manager) }
        return true
    }

    // The camera stopped answering (reported on its thread, run here on the UI thread): if it is still the
    // open one, close it and the arena it calibrated, and show the picker, not its last frame
    private fun cameraLost(camera: Camera) {
        val manager = cameraState.value ?: return
        if (manager.camera !== camera) return
        closeArena()
        cameraState.value = null
        cameras.clearManager(manager)
        openView?.live = false
        openView = null
        feed.clearFrame()
        problemState.value = cameraProblems.missingMessage(camera)
        closeDeviceLater(camera)
    }

    // ---- The arena

    /** The main window's top left corner, which tells the screen ShootOFF is on */
    @Volatile
    var mainWindowCorner = Point(0.0, 0.0)

    /** Where the arena would open now */
    fun arenaPlacementNow(): ArenaPlacement {
        val all = screens()
        return ArenaScreens.place(all, ArenaScreens.screenAt(all, mainWindowCorner), settings.arenaPosition.orElse(null))
    }

    fun screensNow(): List<Rect> = screens()

    /** Whether a screen looks like the projector */
    fun projectorScreenFound(): Boolean = arenaPlacementNow().screen != null

    /**
     * Opens the arena window, on the projector if one is found, ready to be calibrated with the open camera.
     * Unlike the JavaFX app, it never starts calibrating: only [startCalibration] does. Without an open
     * camera there is nothing to calibrate with yet; [makeCalibratable] runs later instead, once a camera
     * opens (see [publish]).
     */
    fun openArena() {
        if (arenaState.value != null) return

        placementState.value = arenaPlacementNow()

        lateinit var arena: ArenaModel
        arena = ArenaModel(settings, { runner }, { arenaCommands(arena) }, clock)
        arenaState.value = arena
        // Back to the view the user last left the app on
        if (prefs.view == BigView.ARENA) viewState.value = BigView.ARENA

        cameraState.value?.let { makeCalibratable(arena, it) }
    }

    // Creates the calibration controller for [camera] on [arena], without calibrating. Also reached when a
    // camera opens (or becomes available) after the arena, which otherwise would leave Calibrate and F6
    // disabled until the arena is closed and reopened.
    private fun makeCalibratable(arena: ArenaModel, camera: CameraManager) {
        val controller = CalibrationController(camera, arena, settings, runner, this, { task, delay ->
            TimerPool.schedule(task, delay) ?: CompletableFuture<Void>()
        }, uiThread)
        camera.setCalibrationManager(controller)
        calibrationState.value = controller

        // Calibration hears the arena going full screen or leaving it, as the JavaFX arena tells it: on the
        // UI thread, as calibration's other inputs are, and only while this arena is still the open one.
        // Started undispatched, so it is watching before this returns: a flip right after the arena opens is
        // not taken for the value drop(1) skips
        fullScreenWatch = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            arena.fullScreen.drop(1).collect { fullScreen ->
                uiThread(Runnable { if (calibrationState.value === controller) controller.fullScreenChanged(fullScreen) })
            }
        }
    }

    /** The arena window closed: calibration ends, a projector drill stops, and the view goes back to the camera. */
    fun closeArena() {
        val arena = arenaState.value ?: return
        calibrationState.value?.arenaClosing()
        fullScreenWatch?.cancel()
        calibrationState.value = null
        // The arena goes first, so no projector drill can start on it from here on (newHost finds none);
        // then the one running, if any, stops under the runner's lock, so one started just before can't slip by
        arenaState.value = null
        calibratedAtState.value = null
        runner.stopProjectorExercise()
        placementState.value = null
        viewState.value = BigView.CAMERA
        arena.targets.set.targets.forEach { arena.targets.remove(it.id) }
    }

    /**
     * Starts calibrating the open arena with the open camera: the only way calibration ever starts
     * (Calibrate on Setup, or F6).
     *
     * @return false if there is no arena, or no camera, to calibrate
     */
    fun startCalibration(): Boolean {
        val controller = calibrationState.value ?: return false
        controller.start()
        return true
    }

    /** Cancel: calibration ends, leaving the arena and the camera as they were before it started. */
    fun cancelCalibration() {
        calibrationState.value?.cancel()
    }

    override fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>) {
        calibratedAtState.value = wallClock()
    }

    fun calibrationStatus(): CalibrationStatus = calibrationStatus(
        arenaState.value != null,
        calibrationState.value?.state?.value?.calibrating == true,
        arenaState.value?.projection?.value != null,
    )

    private fun arenaCommands(arena: ArenaModel) = RegionCommandRunner(arena.targets, settings, ::reset, ::exerciseResources, toCamera = { point ->
        // poi_adjust measures the region's center on the camera feed, through the calibrated projection
        val bounds = cameraState.value?.projectionBounds?.orElse(null)
        if (bounds == null) point else ArenaGeometry.arenaToCamera(point.x, point.y, bounds, arena.size.value)
    })

    // ---- Drills

    /**
     * Starts a fresh instance of [entry]; a projector drill needs the open arena.
     *
     * @return false if it couldn't start
     */
    fun startDrill(entry: V2ExerciseEntry): Boolean {
        val started = runner.start(entry)
        if (started) destinationState.value = Destination.RANGE
        return started
    }

    fun stopDrill() = runner.stop()

    /** Reset: the cameras, the arena's animations and the shots, then the drill, then a short pause in detection */
    fun reset() = rangeReset.reset { runner.reset() }

    /** Clears the shot markers and the shot timer */
    fun clearShots() = feedSurface.clear()

    private fun exerciseResources(): ClassLoader? = runner.running.value?.entry?.exerciseClass()?.classLoader

    private fun newHost(entry: V2ExerciseEntry, exercise: Exercise, runner: ExerciseRunner): ComposeExerciseHost? {
        val surface = if (entry.isProjectorOnly) {
            ArenaHostSurface(arenaState.value ?: return null)
        } else {
            FeedHostSurface(feedTargets, displaySize)
        }
        return ComposeExerciseHost(
            exercise,
            HostContext(
                settings,
                surface,
                timer,
                drill,
                entry.exerciseClass().classLoader,
                SoundOutput.speakers,
                clearShots = ::clearShots,
                setDetecting = cameras::setDetectingAll,
                onFailure = runner::failed,
            ),
        )
    }

    /** Stops everything, for the app's exit */
    fun close() {
        // A camera still opening is closed when it finishes
        openGeneration.incrementAndGet()
        runner.stop()
        closeArena()
        cameras.closeAll()
        scope.cancel()
    }
}

/** What the status strip says about calibration */
fun calibrationStatus(arenaOpen: Boolean, calibrating: Boolean, calibrated: Boolean): CalibrationStatus = when {
    !arenaOpen -> CalibrationStatus.NO_ARENA
    calibrating -> CalibrationStatus.CALIBRATING
    calibrated -> CalibrationStatus.CALIBRATED
    else -> CalibrationStatus.NEEDS_CALIBRATION
}

/**
 * One camera's view of the app's feed. It goes dead when that camera is replaced or dropped, so a camera
 * still sending (or closing) after it has been replaced never draws on, or clears, the live feed.
 */
private class OpenView(private val view: ComposeCameraView) : CameraView by view {
    @Volatile
    var live = true

    override fun setCameraManager(cameraManager: CameraManager) {
        if (live) view.setCameraManager(cameraManager)
    }

    override fun updateBackground(frame: BufferedImage?, projectionBounds: Optional<Rect>) {
        if (live) view.updateBackground(frame, projectionBounds)
    }

    override fun addShot(shot: ScaledShot) {
        if (live) view.addShot(shot)
    }

    override fun addDiagnosticWarning(message: String): DiagnosticMessage =
        if (live) view.addDiagnosticWarning(message) else DiagnosticMessage {}

    override fun close() {
        if (live) view.close()
    }
}
