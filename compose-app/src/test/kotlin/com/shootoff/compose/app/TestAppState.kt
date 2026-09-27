package com.shootoff.compose.app

import com.shootoff.camera.Shot
import com.shootoff.compose.feed.CalibrationStatus
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class TestAppState {
    private val app = AppFixture.app()

    @AfterEach
    fun close() = app.close()

    @Test
    fun theCatalogListsTheV2DrillsByName() {
        assertEquals(listOf("Feed drill", "Projector drill"), app.catalog.entries.value.map { it.metadata().name })
    }

    @Test
    fun aProjectorDrillNeedsTheArenaAndStartingGoesToTheRange() {
        app.navigate(Destination.DRILLS)
        assertFalse(app.startDrill(AppFixture.projectorDrill))

        app.openArena()
        assertTrue(app.startDrill(AppFixture.projectorDrill))

        assertEquals(Destination.RANGE, app.destination.value)
        assertEquals(listOf("Pause"), waitForButtons())
    }

    @Test
    fun theArenaOpensOnTheProjectorAndClosingItStopsAProjectorDrill() {
        app.mainWindowCorner = Point(2000.0, 100.0)
        app.openArena()
        assertEquals(Rect(4480.0, 0.0, 1280.0, 720.0), app.arenaPlacement.value!!.screen)
        app.startDrill(AppFixture.projectorDrill)
        app.showView(BigView.ARENA)

        app.closeArena()

        assertNull(app.arena.value)
        assertNull(app.runner.running.value)
        assertEquals(BigView.CAMERA, app.view.value)
    }

    @Test
    fun theArenaViewNeedsAnOpenArena() {
        app.showView(BigView.ARENA)
        assertEquals(BigView.CAMERA, app.view.value)

        app.openArena()
        app.showView(BigView.ARENA)
        assertEquals(BigView.ARENA, app.view.value)
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
            assertTrue(calibratingApp.calibration.value!!.state.value.calibrating)

            assertFalse(calibratingApp.runner.start(AppFixture.projectorDrill))
            assertNull(calibratingApp.runner.running.value)

            calibratingApp.toggleCalibration()
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
    fun targetsAndSessionsCantBeNavigatedTo() {
        app.navigate(Destination.TARGETS)
        app.navigate(Destination.SESSIONS)

        assertEquals(Destination.RANGE, app.destination.value)
    }

    @Test
    fun closingTheArenaShutsItToNewProjectorDrillsBeforeStoppingTheRunningOne() {
        app.openArena()
        ArenaWatchingDrill.app = app
        assertTrue(app.startDrill(ArenaWatchingDrill.entry))

        app.closeArena()

        // By the time the drill is told to stop, no projector drill can start in its place
        assertEquals(false, ArenaWatchingDrill.arenaOpenAtStop)
        assertNull(app.runner.running.value)
        assertFalse(app.startDrill(AppFixture.projectorDrill))
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

    private fun waitForButtons(): List<String> {
        val deadline = System.currentTimeMillis() + 5000
        while (app.drill.buttons.value.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        return app.drill.buttons.value.map { it.label }
    }
}
