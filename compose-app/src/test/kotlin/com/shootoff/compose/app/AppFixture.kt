package com.shootoff.compose.app

import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.camera.MockCamera
import com.shootoff.camera.Shot
import com.shootoff.camera.cameratypes.CameraEventListener
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.exercise.ButtonHandle
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.awt.image.BufferedImage
import java.time.LocalTime
import java.util.Optional

/** An app with no camera, the owner's three screens, and two drills in its catalog. */
object AppFixture {
    val ownerScreens = listOf(Rect(1920.0, 0.0, 2560.0, 1440.0), Rect(0.0, 0.0, 1920.0, 1080.0), Rect(4480.0, 0.0, 1280.0, 720.0))

    /** A camera that opens and sends no frames */
    open class TestCamera(private val name: String = "Test camera") : MockCamera() {
        override fun getName() = name

        // Closing a camera clears its listener, which MockCamera doesn't allow
        override fun setCameraEventListener(cameraEventListener: CameraEventListener?) {
            this.cameraEventListener = Optional.ofNullable(cameraEventListener)
        }
    }

    class ProjectorDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Projector drill", "2.0", "ShootOFF tests", "On the arena", true)

        override fun start(host: ExerciseHost) {
            host.addButton("Pause") {}
        }

        override fun onShot(shot: Shot, hit: Optional<Hit>) {}

        override fun onReset() {}

        override fun stop() {}
    }

    /**
     * A projector drill that pauses as the par drill does: its button reads "Pause" while it runs and
     * "Resume" while paused, and pausing turns shot detection off.
     */
    class PausingDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Pausing drill", "2.0", "ShootOFF tests", "Pauses like the par drill", true)

        override fun start(host: ExerciseHost) {
            var paused = false
            lateinit var button: ButtonHandle
            button = host.addButton("Pause") {
                paused = !paused
                button.setLabel(if (paused) "Resume" else "Pause")
                host.pauseShotDetection(paused)
            }
        }

        override fun onShot(shot: Shot, hit: Optional<Hit>) {}

        override fun onReset() {}

        override fun stop() {}
    }

    /** A projector drill with no Pause button */
    class UnpausableDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Unpausable drill", "2.0", "ShootOFF tests", "No pause", true)

        override fun start(host: ExerciseHost) {}

        override fun onShot(shot: Shot, hit: Optional<Hit>) {}

        override fun onReset() {}

        override fun stop() {}
    }

    class FeedDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Feed drill", "1.0", "ShootOFF tests", "On the camera feed")

        override fun start(host: ExerciseHost) {}

        override fun onShot(shot: Shot, hit: Optional<Hit>) {}

        override fun onReset() {}

        override fun stop() {}
    }

    /**
     * A camera (feed) drill that pauses as [PausingDrill] does, but doesn't use the arena: a running non-projector
     * drill should still pause when the camera it depends on is lost.
     */
    class PausingFeedDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Pausing feed drill", "1.0", "ShootOFF tests", "Pauses like the par drill, on the camera feed")

        override fun start(host: ExerciseHost) {
            var paused = false
            lateinit var button: ButtonHandle
            button = host.addButton("Pause") {
                paused = !paused
                button.setLabel(if (paused) "Resume" else "Pause")
                host.pauseShotDetection(paused)
            }
        }

        override fun onShot(shot: Shot, hit: Optional<Hit>) {}

        override fun onReset() {}

        override fun stop() {}
    }

    val projectorDrill = V2ExerciseEntry(ProjectorDrill::class.java, ProjectorDrill().metadata())
    val feedDrill = V2ExerciseEntry(FeedDrill::class.java, FeedDrill().metadata())
    val pausingDrill = V2ExerciseEntry(PausingDrill::class.java, PausingDrill().metadata())
    val pausingFeedDrill = V2ExerciseEntry(PausingFeedDrill::class.java, PausingFeedDrill().metadata())
    val unpausableDrill = V2ExerciseEntry(UnpausableDrill::class.java, UnpausableDrill().metadata())

    /** A camera source with one [TestCamera], which the app opens at start */
    fun oneCamera(camera: TestCamera = TestCamera()) = object : CameraSource {
        override fun cameras() = listOf(camera)

        override fun startCamera(settings: Settings) = camera
    }

    /** The same app with [oneCamera], its settings on [settings] and its watchers on [background] */
    fun appWithCamera(
        settings: Settings = Settings(ScratchConfig.emptyFile().path, arrayOf()),
        background: CoroutineDispatcher = Dispatchers.Default,
        screens: List<Rect> = ownerScreens,
        detector: CalibrationCheck.Detector<BufferedImage> = CalibrationCheck.Detector { Optional.empty() },
        checkClock: () -> Long = System::currentTimeMillis,
        wallClock: () -> LocalTime = LocalTime::now,
        patternSettleMillis: Long = 0,
        calibrationTimers: CalibrationFlow.Scheduler = AppState.TIMER_POOL,
    ): AppState {
        val catalog = ExerciseCatalog()
        catalog.registerProjectorExercise(projectorDrill)
        catalog.registerExercise(feedDrill)
        catalog.registerExercise(pausingFeedDrill)
        return AppState(
            settings,
            catalog,
            oneCamera(),
            { screens },
            ManualClock(),
            { it.run() },
            background,
            wallClock = wallClock,
            detector = { detector },
            checkClock = checkClock,
            patternSettleMillis = patternSettleMillis,
            calibrationTimers = calibrationTimers,
        )
    }

    /**
     * Sets [app] (with a camera) up for projector drills, as the owner does on Setup: the camera, the arena
     * on the projector, and a calibration the camera found.
     */
    fun setUpForProjectorDrills(app: AppState) {
        app.openStartCamera()
        app.openArena()
        app.arena.value!!.setFullScreen(true)
        app.startCalibration()
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)
    }

    /**
     * The arena window on the owner's projector, as the window manager leaves it: full screen, and filling
     * the 1280x720 screen (a remembered box's check, or an automatic calibration, wait for both)
     */
    fun putOnTheProjector(app: AppState) {
        val arena = app.arena.value!!
        arena.setSize(Size(1280.0, 720.0))
        arena.setFullScreen(true)
    }

    fun app(screens: List<Rect> = ownerScreens): AppState {
        val catalog = ExerciseCatalog()
        catalog.registerProjectorExercise(projectorDrill)
        catalog.registerExercise(feedDrill)
        return AppState(
            Settings(ScratchConfig.emptyFile().path, arrayOf()),
            catalog,
            CameraSource.None,
            { screens },
            ManualClock(),
            { it.run() },
        )
    }
}
