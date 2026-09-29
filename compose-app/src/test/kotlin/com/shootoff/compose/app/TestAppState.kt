package com.shootoff.compose.app

import androidx.compose.ui.graphics.ImageBitmap
import com.shootoff.camera.Shot
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.feed.CalibrationStatus
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class TestAppState {
    private val app = AppFixture.app()

    @AfterEach
    fun close() = app.close()

    // Reset stands fallen targets back up even with no camera and the arena window closed
    @Test
    fun resetStandsFallenTargetsBackUpWithNoCameraOrArena() {
        val clock = ManualClock()
        val app = AppFixture.app(clock = clock)
        try {
            val onArena = AppFixture.fallenPopper(app.arenaLayout.targets, clock)
            val onFeed = AppFixture.fallenPopper(app.feedTargets, clock)

            app.reset()

            assertTrue(app.arenaLayout.targets.animations.isOnFirstFrame(onArena))
            assertTrue(app.feedTargets.animations.isOnFirstFrame(onFeed))
        } finally {
            app.close()
        }
    }

    // Spec §6: editing works with the arena closed, and the next window shows the layout
    @Test
    fun theShootersTargetsAndBackgroundOutliveTheArenaWindowButAnExercisesTargetsDont() {
        app.openArena()
        val targets = app.arena.value!!.targets
        val box = TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 10.0, 10.0, "red", mapOf())))
        val shooters = targets.add(box, ResourceResolver.files())
        targets.add(box, ResourceResolver.files(), owner = TargetOwner.EXERCISE)
        val background = ArenaBackground(ImageBitmap(2, 2), "indoor_range.gif")
        app.arenaLayout.setBackground(background)

        app.closeArena()

        assertEquals(listOf(shooters.id), app.arenaLayout.targets.set.targets.map { it.id })
        assertSame(background, app.arenaLayout.background.value)

        app.openArena()

        assertSame(app.arenaLayout.targets, app.arena.value!!.targets)
        assertEquals(listOf(shooters.id), app.arena.value!!.targets.set.targets.map { it.id })
    }

    @Test
    fun theCatalogListsTheV2DrillsByName() {
        assertEquals(listOf("Feed drill", "Projector drill"), app.catalog.entries.value.map { it.metadata().name })
    }

    @Test
    fun aProjectorDrillNeedsTheArenaOpenAndCalibrated() {
        val cameraApp = AppFixture.appWithCamera()
        try {
            assertFalse(cameraApp.startDrill(AppFixture.projectorDrill))
            cameraApp.openStartCamera()
            cameraApp.openArena()
            assertFalse(cameraApp.startDrill(AppFixture.projectorDrill))

            AppFixture.setUpForProjectorDrills(cameraApp)
            assertTrue(cameraApp.startDrill(AppFixture.projectorDrill))

            assertEquals(Destination.RANGE, cameraApp.destination.value)
            assertEquals(listOf("Pause"), waitForButtons(cameraApp))
        } finally {
            cameraApp.close()
        }
    }

    @Test
    fun theArenaOpensOnTheProjectorAndClosingItStopsAProjectorDrill() {
        val cameraApp = AppFixture.appWithCamera()
        try {
            cameraApp.mainWindowCorner = Point(2000.0, 100.0)
            AppFixture.setUpForProjectorDrills(cameraApp)
            assertEquals(Rect(4480.0, 0.0, 1280.0, 720.0), cameraApp.arenaPlacement.value!!.screen)
            assertTrue(cameraApp.startDrill(AppFixture.projectorDrill))

            cameraApp.closeArena()

            assertNull(cameraApp.arena.value)
            assertNull(cameraApp.runner.running.value)
            assertNull(cameraApp.calibratedAt.value)
        } finally {
            cameraApp.close()
        }
    }

    @Test
    fun theDrillPickedIsTheFirstUntilAnotherIsPickedWhileItIsInTheCatalog() {
        val entries = app.catalog.entries.value
        assertEquals(AppFixture.feedDrill, app.pickedDrill(entries))

        app.pickDrill(AppFixture.projectorDrill)
        assertEquals(AppFixture.projectorDrill, app.pickedDrill(entries))

        // Its jar was removed
        assertEquals(AppFixture.feedDrill, app.pickedDrill(listOf(AppFixture.feedDrill)))
    }

    @Test
    fun withoutACameraTheArenaOpensButCantCalibrate() {
        app.openArena()

        assertNotNull(app.arena.value)
        assertNull(app.calibration.value)
        assertEquals(CalibrationStatus.NEEDS_CALIBRATION, app.calibrationStatus())
    }

    @Test
    fun aProjectorDrillCantStartWhileTheArenaIsCalibrating() {
        val catalog = ExerciseCatalog()
        catalog.registerProjectorExercise(AppFixture.projectorDrill)
        val cameras = listOf(AppFixture.TestCamera())
        val source = object : CameraSource {
            override fun cameras() = cameras
            override fun startCamera(settings: Settings) = cameras.firstOrNull()
        }
        val calibratingApp = AppState(
            Settings(ScratchConfig.emptyFile().path, arrayOf()),
            catalog,
            source,
            { AppFixture.ownerScreens },
            ManualClock(),
            { it.run() },
        )
        try {
            calibratingApp.openStartCamera()
            calibratingApp.openArena()
            assertTrue(calibratingApp.startCalibration())
            assertTrue(calibratingApp.calibration.value!!.state.value.calibrating)

            assertFalse(calibratingApp.runner.start(AppFixture.projectorDrill))
            assertNull(calibratingApp.runner.running.value)

            calibratingApp.cancelCalibration()
            assertFalse(calibratingApp.calibration.value!!.state.value.calibrating)

            assertTrue(calibratingApp.runner.start(AppFixture.projectorDrill))
        } finally {
            calibratingApp.close()
        }
    }

    @Test
    fun onOneScreenNoProjectorIsFound() {
        val single = AppFixture.app(listOf(Rect(0.0, 0.0, 1920.0, 1080.0)))
        try {
            assertFalse(single.projectorScreenFound())
            assertTrue(app.projectorScreenFound())
        } finally {
            single.close()
        }
    }

    @Test
    fun targetsCanBeNavigatedToButSessionsCant() {
        app.navigate(Destination.TARGETS)
        assertEquals(Destination.TARGETS, app.destination.value)

        app.navigate(Destination.SESSIONS)
        assertEquals(Destination.TARGETS, app.destination.value)
    }

    @Test
    fun closingTheArenaShutsItToNewProjectorDrillsBeforeStoppingTheRunningOne() {
        val cameraApp = AppFixture.appWithCamera()
        try {
            AppFixture.setUpForProjectorDrills(cameraApp)
            ArenaWatchingDrill.app = cameraApp
            assertTrue(cameraApp.startDrill(ArenaWatchingDrill.entry))

            cameraApp.closeArena()

            // By the time the drill is told to stop, no projector drill can start in its place
            assertEquals(false, ArenaWatchingDrill.arenaOpenAtStop)
            assertNull(cameraApp.runner.running.value)
            assertFalse(cameraApp.startDrill(AppFixture.projectorDrill))
        } finally {
            cameraApp.close()
        }
    }

    /** A projector drill that notes, as it stops, whether the arena is still open */
    class ArenaWatchingDrill : Exercise {
        companion object {
            @Volatile
            var app: AppState? = null

            @Volatile
            var arenaOpenAtStop: Boolean? = null

            val entry = V2ExerciseEntry(ArenaWatchingDrill::class.java, ArenaWatchingDrill().metadata())
        }

        override fun metadata() = ExerciseMetadata("Arena-watching drill", "1.0", "ShootOFF tests", "Watches the arena", true)

        override fun start(host: ExerciseHost) {}

        override fun onShot(shot: Shot, hit: Optional<Hit>) {}

        override fun onReset() {}

        override fun stop() {
            arenaOpenAtStop = app?.arena?.value != null
        }
    }

    private fun waitForButtons(app: AppState): List<String> {
        val deadline = System.currentTimeMillis() + 5000
        while (app.drill.buttons.value.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        return app.drill.buttons.value.map { it.label }
    }
}
