package com.shootoff.compose.app

import com.shootoff.calibration.CalibrationCheck
import com.shootoff.camera.MockCamera
import com.shootoff.camera.Shot
import com.shootoff.camera.cameratypes.CameraEventListener
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.geom.Rect
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.awt.image.BufferedImage
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

    class FeedDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Feed drill", "1.0", "ShootOFF tests", "On the camera feed")

        override fun start(host: ExerciseHost) {}

        override fun onShot(shot: Shot, hit: Optional<Hit>) {}

        override fun onReset() {}

        override fun stop() {}
    }

    val projectorDrill = V2ExerciseEntry(ProjectorDrill::class.java, ProjectorDrill().metadata())
    val feedDrill = V2ExerciseEntry(FeedDrill::class.java, FeedDrill().metadata())

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
    ): AppState {
        val catalog = ExerciseCatalog()
        catalog.registerProjectorExercise(projectorDrill)
        catalog.registerExercise(feedDrill)
        return AppState(
            settings,
            catalog,
            oneCamera(),
            { screens },
            ManualClock(),
            { it.run() },
            background,
            detector = { detector },
            checkClock = checkClock,
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
