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

import com.shootoff.calibration.CalibrationCamera
import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.camera.CameraManager
import com.shootoff.camera.CameraView
import com.shootoff.camera.CamerasSupervisor
import com.shootoff.camera.DiagnosticMessage
import com.shootoff.camera.autocalibration.PatternDetector
import com.shootoff.camera.cameratypes.Camera
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.compose.arena.ArenaPlacement
import com.shootoff.compose.arena.ArenaScreens
import com.shootoff.compose.calibration.CalibrationCheckRun
import com.shootoff.compose.calibration.CalibrationController
import com.shootoff.compose.calibration.CalibrationViews
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.calibration.LatestFrame
import com.shootoff.compose.calibration.savedCalibrationMismatch
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
import com.shootoff.config.SavedCalibration
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
import kotlin.math.roundToInt

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
 * @param detector finds the calibration pattern in a camera's frames, for the remembered calibration's check
 * @param checkClock the check's time limit runs on it
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
    private val detector: (CameraManager) -> CalibrationCheck.Detector<BufferedImage> = { PatternDetector(it.camera) },
    private val checkClock: () -> Long = System::currentTimeMillis,
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
    private val darkState = MutableStateFlow(prefs.dark)
    private val trayHeightState = MutableStateFlow(prefs.trayHeight)
    private val trayCollapsedState = MutableStateFlow(prefs.trayCollapsed)
    private val calibratedAtState = MutableStateFlow<LocalTime?>(null)
    private val calibrationCompleteState = MutableStateFlow<LocalTime?>(null)
    private val pickedDrillState = MutableStateFlow<V2ExerciseEntry?>(null)
    private val promptSkippedState = MutableStateFlow(false)
    private val rememberState = MutableStateFlow(settings.rememberCalibration())
    private val checkState = MutableStateFlow<CheckState>(CheckState.Idle)
    private val checkFrames = LatestFrame()
    private var checkRun: CalibrationCheckRun? = null
    private var checkWatch: Job? = null

    // The arena's calibration now, as it would be remembered; null while uncalibrated, or when it can't be
    // remembered (no projector screen)
    private var currentCalibration: SavedCalibration? = null
    private var fullScreenWatch: Job? = null

    /** The open arena, or null */
    val arena: StateFlow<ArenaModel?> = arenaState.asStateFlow()

    /** Where the arena window opens, while it is open */
    val arenaPlacement: StateFlow<ArenaPlacement?> = placementState.asStateFlow()

    val calibration: StateFlow<CalibrationController?> = calibrationState.asStateFlow()

    /** When the open arena was last calibrated; null while it isn't */
    val calibratedAt: StateFlow<LocalTime?> = calibratedAtState.asStateFlow()

    /**
     * When the last calibration the user asked for completed, for Setup's confirmation; null from the moment
     * another calibration starts, and once the arena closes
     */
    val calibrationComplete: StateFlow<LocalTime?> = calibrationCompleteState.asStateFlow()

    /** The drill picked on Range, if the user picked one (see [pickedDrill]) */
    val drillChoice: StateFlow<V2ExerciseEntry?> = pickedDrillState.asStateFlow()

    /** Whether Range's not-ready prompt was skipped; it comes back when the camera drops or the arena closes */
    val promptSkipped: StateFlow<Boolean> = promptSkippedState.asStateFlow()

    /** Whether calibrations are kept for the next session ("Remember calibration") */
    val rememberCalibration: StateFlow<Boolean> = rememberState.asStateFlow()

    /** Where the check of the remembered calibration stands */
    val check: StateFlow<CheckState> = checkState.asStateFlow()

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
        if (!destination.enabled) return
        // The grid is only ever shown on Setup
        if (destination != Destination.SETUP) arenaState.value?.showGrid(false)
        destinationState.value = destination
    }

    /**
     * Setup's Show grid: the arena shows the alignment grid in place of its background, unless calibration,
     * the check or a projector drill needs the arena.
     */
    fun showGrid(show: Boolean) {
        val arena = arenaState.value ?: return
        arena.showGrid(show && gridAllowed())
    }

    /** Whether nothing else needs the arena, so the grid may show */
    fun gridAllowed(): Boolean = arenaState.value != null &&
        calibrationState.value?.state?.value?.calibrating != true &&
        checkState.value != CheckState.Checking &&
        runner.running.value?.host?.isProjector != true

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

    // The manual box is showing: it is dragged over Setup's camera feed
    override fun showCalibratingFeed() = navigate(Destination.SETUP)

    // Calibration ending leaves the user where they are, a success included (spec §8 Revision 2, decision 1)
    override fun restoreSelectedView() {}

    // ---- The camera

    /**
     * What the app does as it starts (spec §8 rule 1): the camera it starts with is found and opened in the
     * background, and nothing else happens. The arena, calibration and the check wait for the user.
     */
    fun launch() {
        scope.launch(io) {
            val camera = try {
                cameraSource.startCamera(settings)
            } catch (e: Exception) {
                logger.error("Couldn't find the camera to start with", e)
                null
            }
            if (camera != null) uiThread(Runnable {
                // Enumerating (above) can take long enough for the owner to have opened, or started
                // opening, a camera of their own by the time it resolves: that pick, not this stale one, wins
                if (cameraState.value == null && openingState.value == null) openCameraInBackground(camera)
            })
        }
    }

    /** Opens the camera the app starts with, if there is one, on the calling thread (for tests: the app uses [launch]) */
    fun openStartCamera() {
        cameraSource.startCamera(settings)?.let(::openCamera)
    }

    /**
     * The user picked [camera] (on Setup or Settings): it opens in the background and, once open, is saved as
     * the camera to start with, as the JavaFX preferences save it.
     */
    fun pickCamera(camera: Camera) {
        openCameraInBackground(camera) { opened ->
            if (opened) {
                settings.setWebcams(listOf(camera.name), listOf(camera))
                saveSettings()
            }
        }
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
     * Opens [camera] in place of the open one without blocking the calling (UI) thread: the open one closes
     * at once, and the arena it calibrated stays open, uncalibrated (spec §8 Revision 2, decision 7);
     * [openingCamera] names [camera] while it opens on the I/O dispatcher; then [then] hears on the UI thread
     * whether it opened. One camera opens at a time.
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
            // Picked from a list made before it was unplugged, it may be gone, or back under another device
            val found = try {
                cameraSource.current(camera)
            } catch (e: Exception) {
                logger.warn("Couldn't look for the webcam {}", camera.name, e)
                camera
            }
            val opened = if (found != null) startCamera(found) else Opened(camera, OpenView(cameraView), null, notConnected = true)
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
    private class Opened(
        val camera: Camera,
        val view: OpenView,
        val manager: CameraManager?,
        val error: Throwable? = null,
        val notConnected: Boolean = false,
    )

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

    // Closes the open camera's manager; the arena stays open, without the calibration that camera made (spec §8
    // Revision 2, decision 7). Returns the new open's generation and the camera whose device is still to close.
    private fun releaseCamera(): Pair<Int, Camera?> {
        val generation = openGeneration.incrementAndGet()
        openingState.value = null
        val old = cameraState.value
        if (old != null) detachCamera()
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
            if (opened.notConnected) {
                cameraProblems.showNotConnected(opened.camera)
            } else if (error != null) {
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
        // The arena was already open with no camera to calibrate with (opened before the camera, or kept open
        // when the last camera went); now one is here, so the arena can be calibrated
        arenaState.value?.let { arena ->
            if (calibrationState.value == null) makeCalibratable(arena, manager)
            // An uncalibrated arena is checked against the remembered calibration now that there is a camera
            // to check with (spec §8 Revision 2, decision 7); without Remember, Setup's Calibrate step is next
            if (settings.rememberCalibration() && settings.savedCalibration.isPresent &&
                arena.projection.value == null && checkState.value != CheckState.Checking
            ) {
                checkRemembered(arena)
            }
        }
        return true
    }

    // The camera stopped answering (reported on its thread, run here on the UI thread): if it is still the
    // open one, close it, and show the picker, not its last frame. The arena stays open (spec §8 Revision 2,
    // decision 7)
    private fun cameraLost(camera: Camera) {
        val manager = cameraState.value ?: return
        if (manager.camera !== camera) return
        detachCamera()
        promptSkippedState.value = false
        cameraState.value = null
        cameras.clearManager(manager)
        openView?.live = false
        openView = null
        feed.clearFrame()
        problemState.value = cameraProblems.missingMessage(camera)
        closeDeviceLater(camera)
    }

    // The open camera is going (lost, or replaced). The arena stays open (spec §8 Revision 2, decision 7), but
    // its calibration was made with that camera, so it goes: calibration or a check under way ends, the arena
    // says "Needs Calibration" again, and the running drill pauses if it has a Pause button, camera drills
    // included; a projector drill with none is stopped instead, as calibration stops one too.
    private fun detachCamera() {
        pauseOrStopProjectorDrill()
        val arena = arenaState.value ?: return
        stopCheckQuietly()
        currentCalibration = null
        // For calibration the camera going is the arena going: calibration ends, and both projections go
        calibrationState.value?.arenaClosing()
        fullScreenWatch?.cancel()
        calibrationState.value = null
        calibratedAtState.value = null
        calibrationCompleteState.value = null
        arena.setCalibrationLabelVisible(true)
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

        cameraState.value?.let { makeCalibratable(arena, it) }
        if (settings.rememberCalibration()) checkRemembered(arena)
    }

    // The remembered calibration, checked (never at launch: only here, as the arena opens, or once a camera
    // becomes available for an arena already open — see publish) if it was made with this camera and a
    // projector screen like this one. The check tracks the arena's full screen state throughout, through
    // watchFullScreenForCheck: it starts, or restarts, only while full screen, since the pattern on a window
    // still on its way there, or one the owner has pulled off the projector (F11) mid-check, would be
    // measured in the wrong place.
    private fun checkRemembered(arena: ArenaModel) {
        val saved = settings.savedCalibration.orElse(null) ?: return
        val camera = cameraState.value
        if (camera == null || calibrationState.value == null) {
            checkState.value = CheckState.NotVerified(CalibrationCheck.Reason.NO_CAMERA)
            return
        }
        val feed = Size(camera.feedWidth.toDouble(), camera.feedHeight.toDouble())
        savedCalibrationMismatch(saved, camera.name, feed, placementState.value?.screen)?.let {
            checkState.value = CheckState.DoesntFit(it)
            return
        }

        checkState.value = CheckState.Checking
        watchFullScreenForCheck(arena, camera, saved)
    }

    // Starts, and restarts, the check as the arena's full screen state comes and goes (spec §8, Finding 2):
    // reaching full screen starts a run; losing it (F11 mid-check) stops the run quietly, the same way
    // stopCheckQuietly's run?.stop(...) does (the pattern comes off, the background comes back), without
    // reporting a Moved outcome measured in a window — checkState stays Checking so a return to full screen
    // tries again. Runs until checkState leaves Checking: checked cancels it on a real outcome, and
    // stopCheckQuietly cancels it on every other end (cancel, closing the arena, calibrating, remember off).
    private fun watchFullScreenForCheck(arena: ArenaModel, camera: CameraManager, saved: SavedCalibration) {
        checkWatch = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            arena.fullScreen.collect { fullScreen ->
                uiThread(Runnable {
                    if (arenaState.value !== arena || checkState.value != CheckState.Checking) return@Runnable
                    if (fullScreen) {
                        if (checkRun == null) runCheck(arena, camera, saved)
                    } else {
                        stopRunQuietlyForFullScreenLoss()
                    }
                })
            }
        }
    }

    // Finding 2: the arena left full screen mid-check. The run stops without a word, as stopCheckQuietly's
    // run?.stop(...) does, but checkState stays Checking and the full screen watch keeps running, so
    // watchFullScreenForCheck starts a fresh run once the arena is full screen again.
    private fun stopRunQuietlyForFullScreenLoss() {
        val run = checkRun ?: return
        checkRun = null
        cameraView.frameTap = null
        run.stop(CalibrationCheck.Reason.CANCELLED)
    }

    private fun runCheck(arena: ArenaModel, camera: CameraManager, saved: SavedCalibration) {
        val run = CalibrationCheckRun(saved, arena, CalibratingCamera(camera), checkFrames, detector(camera), checkClock, scope, { task, delay ->
            TimerPool.schedule(task, delay) ?: CompletableFuture<Void>()
        }, uiThread) { run, outcome -> checked(run, saved, outcome) }
        checkRun = run
        cameraView.frameTap = checkFrames::offer
        arena.showGrid(false)
        run.start()
    }

    // The check's outcome, on the UI thread; ignored if the check was stopped by something that replaced it
    private fun checked(run: CalibrationCheckRun, saved: SavedCalibration, outcome: CalibrationCheck.Outcome) {
        if (checkRun !== run) return
        checkRun = null
        cameraView.frameTap = null
        // A real outcome ends the check: the full screen watch (Finding 2) has nothing left to restart
        checkWatch?.cancel()
        checkWatch = null
        when (outcome) {
            is CalibrationCheck.Kept -> {
                calibrationState.value?.applySaved(saved)
                currentCalibration = saved
                calibratedAtState.value = wallClock()
                checkState.value = CheckState.Idle
            }
            is CalibrationCheck.Moved -> checkState.value = CheckState.Moved(outcome.distance().roundToInt())
            is CalibrationCheck.NotVerified -> checkState.value = CheckState.NotVerified(outcome.reason())
        }
    }

    // Ends a check without a word (something replaced it); the arena's background comes back
    private fun stopCheckQuietly() {
        checkWatch?.cancel()
        checkWatch = null
        val run = checkRun
        checkRun = null
        cameraView.frameTap = null
        run?.stop(CalibrationCheck.Reason.CANCELLED)
        checkState.value = CheckState.Idle
    }

    /** Cancel on the check: it ends, the arena stays uncalibrated, and Setup says it wasn't verified. */
    fun cancelCheck() {
        if (checkState.value != CheckState.Checking) return
        stopCheckQuietly()
        checkState.value = CheckState.NotVerified(CalibrationCheck.Reason.CANCELLED)
    }

    /**
     * "Remember calibration". On: the arena's calibration, now and after each calibration, is saved for the
     * next session. Off: nothing is saved, the saved one is forgotten, and a check under way stops.
     */
    fun setRememberCalibration(remember: Boolean) {
        // A no-op when nothing changed: turning it on again with nothing calibrated this session (so
        // currentCalibration is still null) would otherwise erase an already-saved calibration
        if (remember == rememberState.value) return
        rememberState.value = remember
        settings.setRememberCalibration(remember)
        settings.setSavedCalibration(if (remember) currentCalibration else null)
        if (!remember && checkState.value == CheckState.Checking) stopCheckQuietly()
        saveSettings()
    }

    private fun saveSettings() {
        try {
            settings.writeConfigurationFile()
        } catch (e: Exception) {
            logger.error("Couldn't save the settings", e)
        }
    }

    // Creates the calibration controller for [camera] on [arena], without calibrating. Also reached when a
    // camera opens (or becomes available) after the arena, which otherwise would leave Calibrate and F6
    // disabled until the arena is closed and reopened.
    private fun makeCalibratable(arena: ArenaModel, camera: CameraManager) {
        val controller = CalibrationController(CalibratingCamera(camera), arena, settings, drillForCalibration, this, { task, delay ->
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

    /** The arena window closed: calibration or a check ends, a projector drill stops, and Range asks for setup again. */
    fun closeArena() {
        val arena = arenaState.value ?: return
        stopCheckQuietly()
        currentCalibration = null
        calibrationState.value?.arenaClosing()
        fullScreenWatch?.cancel()
        calibrationState.value = null
        // The arena goes first, so no projector drill can start on it from here on (newHost finds none);
        // then the one running, if any, stops under the runner's lock, so one started just before can't slip by
        arenaState.value = null
        calibratedAtState.value = null
        calibrationCompleteState.value = null
        runner.stopProjectorExercise()
        placementState.value = null
        promptSkippedState.value = false
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
        // A check under way stops first, putting the arena's background back before calibration saves it
        stopCheckQuietly()
        arenaState.value?.showGrid(false)
        calibrationCompleteState.value = null
        controller.start()
        return true
    }

    /** Cancel: calibration ends, leaving the arena and the camera as they were before it started. */
    fun cancelCalibration() {
        calibrationState.value?.cancel()
    }

    override fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>) {
        val now = wallClock()
        calibratedAtState.value = now
        // The user stays where they are (Setup, usually) and is told it worked; they go back to Range when
        // they choose (spec §8 Revision 2, decision 1)
        calibrationCompleteState.value = now
        checkState.value = CheckState.Idle
        val camera = cameraState.value
        val screen = placementState.value?.screen
        // Made without a projector screen, a calibration can't be matched to one next time
        currentCalibration = if (camera != null && screen != null) {
            SavedCalibration(camera.name, Size(camera.feedWidth.toDouble(), camera.feedHeight.toDouble()), Size(screen.width, screen.height), cameraBounds, paper)
        } else {
            null
        }
        if (settings.rememberCalibration()) {
            settings.setSavedCalibration(currentCalibration)
            saveSettings()
        }
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

    /** Picks the drill Range's card starts */
    fun pickDrill(entry: V2ExerciseEntry) {
        pickedDrillState.value = entry
    }

    /** The drill Range's card starts: the one picked, while it is still in [entries], else the first */
    fun pickedDrill(entries: List<V2ExerciseEntry>): V2ExerciseEntry? = pickedDrillState.value?.takeIf { it in entries } ?: entries.firstOrNull()

    /** Hides Range's not-ready prompt, for camera-only use, until the camera drops or the arena closes */
    fun skipPrompt() {
        promptSkippedState.value = true
    }

    /**
     * Whether a projector drill can run: a camera, the arena open and calibrated, and neither calibration
     * nor the check under way (spec §8; Plan 5's guard, with calibration added).
     */
    fun projectorReady(): Boolean = cameraState.value != null &&
        arenaState.value?.projection?.value != null &&
        calibrationState.value?.state?.value?.calibrating != true &&
        checkState.value != CheckState.Checking

    /**
     * Starts a fresh instance of [entry]; a projector drill needs [projectorReady].
     *
     * @return false if it couldn't start
     */
    fun startDrill(entry: V2ExerciseEntry): Boolean {
        if (entry.isProjectorOnly && !projectorReady()) return false
        arenaState.value?.showGrid(false)
        val started = runner.start(entry)
        if (started) destinationState.value = Destination.RANGE
        return started
    }

    fun stopDrill() = runner.stop()

    /**
     * Pauses the running drill through its own Pause button, as F3 does, unless it is paused already (its
     * button reads Resume). The v2 exercise API has no pause of its own: the drill's button is the pause.
     *
     * @return false if no drill with a Pause button runs
     */
    fun pauseDrill(): Boolean {
        val buttons = drill.buttons.value
        if (buttons.any { it.label == RESUME_LABEL }) return true
        val pause = buttons.firstOrNull { it.label == PAUSE_LABEL } ?: return false
        pause.onClick()
        return true
    }

    // Pauses the running drill if it has a Pause button (camera drills included); a projector drill with
    // none is stopped instead, since nothing else can hold it off the arena while the arena isn't
    // available to it (calibrating, or, per spec §8 Revision 2 decision 7, the camera gone). Returns what
    // starts it again, for calibration to use afterwards; empty when nothing was stopped.
    private fun pauseOrStopProjectorDrill(): Optional<Runnable> {
        val running = runner.running.value
        return when {
            running == null || !running.host.isProjector -> Optional.empty()
            pauseDrill() -> Optional.empty()
            else -> runner.stopProjectorExercise()
        }
    }

    // What calibration does to the running drill (spec §8 Revision 2, decision 2): a projector drill is paused,
    // and stays paused afterwards, never restarted; one with no Pause button is stopped and started afresh
    // after a success, as before. A camera drill doesn't use the arena and is left alone.
    private val drillForCalibration = CalibrationFlow.Exercises { pauseOrStopProjectorDrill() }

    // The camera as calibration and the check see it: when they turn shot detection back on as they end,
    // it stays off while the running drill has it paused (a paused drill turned it off itself)
    private inner class CalibratingCamera(private val camera: CameraManager) : CalibrationCamera by camera {
        override fun setDetecting(isDetecting: Boolean) {
            camera.setDetecting(isDetecting && runner.running.value?.host?.shotDetectionPaused != true)
        }
    }

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
