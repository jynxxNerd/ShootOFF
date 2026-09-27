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
import com.shootoff.camera.CamerasSupervisor
import com.shootoff.camera.cameratypes.Camera
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.awt.EventQueue
import java.util.concurrent.CompletableFuture

/** The Range screen's big view */
enum class BigView { CAMERA, ARENA }

/**
 * The Compose app's state: the camera and its feed, the arena, calibration, the running drill, the shot
 * timer, and where the user is. Composables read it; user actions call it.
 *
 * @param screens the screens the arena can go on, in AWT's coordinates
 * @param uiThread runs calibration's timers on the UI thread
 */
class AppState(
    val settings: Settings,
    val catalog: ExerciseCatalog = ExerciseCatalog(),
    private val cameraSource: CameraSource = CameraSource.None,
    private val screens: () -> List<Rect> = ArenaScreens::screens,
    private val clock: AnimationClock = AnimationClock.background,
    private val uiThread: (Runnable) -> Unit = EventQueue::invokeLater,
) : CalibrationViews {
    private val logger = LoggerFactory.getLogger(AppState::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val displaySize = Size(settings.displayWidth.toDouble(), settings.displayHeight.toDouble())
    val cameras = CamerasSupervisor(settings)
    val timer = ShotTimerModel()
    val drill = DrillState()
    val feed = FeedState(displaySize)
    val feedTargets = SurfaceTargets(clock = clock)
    val feedMarkers = ShotMarkers()

    val rangeReset = RangeReset(cameras, { calibration.value?.flow?.isCalibrating ?: false }, { task, delay -> TimerPool.schedule(task, delay) })
    val runner: ExerciseRunner = ExerciseRunner(::newHost)

    private val arenaState = MutableStateFlow<ArenaModel?>(null)
    private val placementState = MutableStateFlow<ArenaPlacement?>(null)
    private val calibrationState = MutableStateFlow<CalibrationController?>(null)
    private val cameraState = MutableStateFlow<CameraManager?>(null)
    private val destinationState = MutableStateFlow(Destination.RANGE)
    private val viewState = MutableStateFlow(BigView.CAMERA)
    private var viewBeforeCalibration: BigView? = null
    private var fullScreenWatch: Job? = null

    /** The open arena, or null */
    val arena: StateFlow<ArenaModel?> = arenaState.asStateFlow()

    /** Where the arena window opens, while it is open */
    val arenaPlacement: StateFlow<ArenaPlacement?> = placementState.asStateFlow()

    val calibration: StateFlow<CalibrationController?> = calibrationState.asStateFlow()

    /** The open camera, or null */
    val camera: StateFlow<CameraManager?> = cameraState.asStateFlow()

    val destination: StateFlow<Destination> = destinationState.asStateFlow()
    val view: StateFlow<BigView> = viewState.asStateFlow()

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

    private val feedCommands = RegionCommandRunner(feedTargets, settings, ::reset, ::exerciseResources)

    // ---- Where the user is

    fun navigate(destination: Destination) {
        if (destination.enabled) destinationState.value = destination
    }

    fun showView(view: BigView) {
        if (view == BigView.ARENA && arenaState.value == null) return
        viewState.value = view
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

    fun availableCameras(): List<Camera> = cameraSource.cameras()

    /**
     * Opens [camera] in place of the open one.
     *
     * @return false if it can't be opened
     */
    fun openCamera(camera: Camera): Boolean {
        cameraState.value?.let(cameras::clearManager)
        cameraState.value = null

        val manager = cameras.addCameraManager(camera, null, cameraView).orElse(null)
        if (manager == null) {
            logger.error("Cannot open the webcam {}", camera.name)
            return false
        }
        cameraState.value = manager
        return true
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
     * Opens the arena window, on the projector if one is found, and starts calibrating it with the open
     * camera, as the JavaFX app does.
     */
    fun openArena() {
        if (arenaState.value != null) return

        placementState.value = arenaPlacementNow()

        lateinit var arena: ArenaModel
        arena = ArenaModel(settings, { runner }, { arenaCommands(arena) }, clock)
        arenaState.value = arena

        val camera = cameraState.value ?: return
        val controller = CalibrationController(camera, arena, settings, runner, this, { task, delay ->
            TimerPool.schedule(task, delay) ?: CompletableFuture<Void>()
        }, uiThread)
        camera.setCalibrationManager(controller)
        calibrationState.value = controller

        // Calibration hears the arena going full screen, as the JavaFX arena tells it
        // on the UI thread, as calibration's other inputs are, and only while this arena is still the open one:
        // the flow starts calibrating on a full-screen change, which must never happen after the arena closed
        fullScreenWatch = scope.launch {
            arena.fullScreen.drop(1).collect { fullScreen ->
                uiThread(Runnable { if (calibrationState.value === controller) controller.fullScreenChanged(fullScreen) })
            }
        }

        controller.flow.start()
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
        runner.stopProjectorExercise()
        placementState.value = null
        viewState.value = BigView.CAMERA
        arena.targets.set.targets.forEach { arena.targets.remove(it.id) }
    }

    fun toggleCalibration() {
        calibrationState.value?.toggle()
    }

    fun calibrationStatus(): CalibrationStatus {
        val arena = arenaState.value ?: return CalibrationStatus.NO_ARENA
        return when {
            calibrationState.value?.state?.value?.calibrating == true -> CalibrationStatus.CALIBRATING
            arena.projection.value != null -> CalibrationStatus.CALIBRATED
            else -> CalibrationStatus.NEEDS_CALIBRATION
        }
    }

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
        runner.stop()
        closeArena()
        cameras.closeAll()
        scope.cancel()
    }
}
