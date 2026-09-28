package com.shootoff.compose.app

import com.shootoff.calibration.CalibrationCheck
import com.shootoff.camera.cameratypes.Camera
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.SavedCalibration
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.time.Duration
import java.util.Optional
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Spec §8 "Nothing blocks": one test per rule. */
class TestNothingBlocks {
    @Test
    fun rule1AtLaunchTheCameraAndTheArenaOpenAndNothingCalibrates() {
        val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
        settings.setRememberCalibration(true)
        settings.setSavedCalibration(SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), true))
        val looked = AtomicLong()
        val app = AppFixture.appWithCamera(settings, detector = {
            looked.incrementAndGet()
            Optional.empty()
        })
        try {
            app.launch()
            awaitTrue { app.camera.value != null }
            // The main window landing tells the launch arena where to go (review fix: it can't use the
            // default corner, since it isn't known this early)
            app.mainWindowPlaced(Point(50.0, 50.0))

            assertEquals(Destination.RANGE, app.destination.value)
            // The owner's projector was found: the arena opens on it (spec §8 Revision 2, decision 4)
            assertEquals(Rect(4480.0, 0.0, 1280.0, 720.0), app.arenaPlacement.value!!.screen)
            assertFalse(app.calibration.value!!.state.value.calibrating)
            // The saved calibration is checked once the window fills the projector, which it hasn't yet
            assertEquals(CheckState.Checking, app.check.value)
            assertEquals(0, looked.get())
            assertNull(app.arena.value!!.background.value)
        } finally {
            app.close()
        }
    }

    // Spec §8 Revision 3: with the option on, the launch arena calibrates itself, but only once it is on the
    // projector, and without taking the owner off Range
    @Test
    fun rule1WithTheOptionOnTheLaunchArenaCalibratesOnlyOnceItIsOnTheProjector() {
        val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
        settings.setRememberCalibration(true)
        val app = AppFixture.appWithCamera(settings)
        try {
            app.launch()
            awaitTrue { app.camera.value != null }
            app.mainWindowPlaced(Point(50.0, 50.0))

            assertEquals(CheckState.WaitingToCalibrate, app.check.value)
            assertFalse(app.calibration.value!!.state.value.calibrating)
            assertNull(app.arena.value!!.background.value)

            AppFixture.putOnTheProjector(app)
            awaitTrue { app.calibration.value!!.state.value.calibrating }
            assertEquals(Destination.RANGE, app.destination.value)
        } finally {
            app.close()
        }
    }

    @Test
    fun rule1WithNoProjectorScreenOnlyTheCameraOpensAtLaunch() {
        val catalog = ExerciseCatalog()
        val app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), catalog, AppFixture.oneCamera(), { listOf(Rect(0.0, 0.0, 1920.0, 1080.0)) }, ManualClock(), { it.run() })
        try {
            app.launch()
            awaitTrue { app.camera.value != null }
            app.mainWindowPlaced(Point(50.0, 50.0))

            assertNull(app.arena.value)
            assertFalse(app.projectorScreenFound())
        } finally {
            app.close()
        }
    }

    @Test
    fun rule1TheLaunchArenaUsesTheMainWindowsRealCornerNotTheDefault() {
        // Two screens: OTHER_OF_TWO puts the arena on whichever one the main window isn't on. The window's
        // real corner lands it on the second screen, not the origin's default first screen (review fix)
        val screens = listOf(Rect(0.0, 0.0, 1920.0, 1080.0), Rect(1920.0, 0.0, 1920.0, 1080.0))
        val catalog = ExerciseCatalog()
        val app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), catalog, AppFixture.oneCamera(), { screens }, ManualClock(), { it.run() })
        try {
            app.launch()
            awaitTrue { app.camera.value != null }
            app.mainWindowPlaced(Point(2000.0, 100.0))

            assertEquals(screens[0], app.arenaPlacement.value!!.screen)
        } finally {
            app.close()
        }
    }

    @Test
    fun rule1ALateLaunchOpenNeverOverridesTheOwnersPick() {
        val cameraA = AppFixture.TestCamera("Camera A")
        val cameraB = AppFixture.TestCamera("Camera B")
        // launch()'s enumeration is stuck here while the owner picks and opens Camera B themselves
        val latch = CountDownLatch(1)
        val source = object : CameraSource {
            override fun cameras() = listOf(cameraA, cameraB)

            override fun startCamera(settings: Settings): Camera {
                assertTrue(latch.await(5, TimeUnit.SECONDS))
                return cameraA
            }
        }
        // What launch() posts to the UI thread is queued here instead of running at once, so it can be
        // driven by hand once the owner's pick has already landed
        val ui = ConcurrentLinkedQueue<Runnable>()
        val app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), ExerciseCatalog(), source, { AppFixture.ownerScreens }, ManualClock(), { ui.add(it) })
        try {
            app.launch()

            assertTrue(app.openCamera(cameraB))
            assertEquals("Camera B", app.camera.value!!.camera.name)

            latch.countDown()
            awaitTrue { ui.isNotEmpty() }
            ui.poll().run()

            assertEquals("Camera B", app.camera.value!!.camera.name)
        } finally {
            app.close()
        }
    }

    @Test
    fun rule2CalibrationRunsOnlyWhenAskedNeverBecauseTheArenaOpened() {
        val background = TestProblems.QueueDispatcher()
        val app = AppFixture.appWithCamera(background = background)
        try {
            app.openStartCamera()
            app.openArena()
            // The arena window reaching the projector and going full screen
            app.arena.value!!.setFullScreen(true)
            background.drain()

            assertFalse(app.calibration.value!!.state.value.calibrating)
            assertNull(app.arena.value!!.background.value)

            // F6, or Calibrate on Setup
            assertTrue(app.startCalibration())
            assertTrue(app.calibration.value!!.state.value.calibrating)
        } finally {
            app.close()
        }
    }

    @Test
    fun rule3TheCheckRunsOffTheUiThreadAndGivesUpQuietlyAtItsTimeLimit() {
        val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
        settings.setRememberCalibration(true)
        settings.setSavedCalibration(SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), true))
        val now = AtomicLong(0)
        val lookedOn = AtomicReference<Thread>()
        // The projector is off: the pattern is never seen
        val app = AppFixture.appWithCamera(settings, detector = {
            lookedOn.set(Thread.currentThread())
            Optional.empty()
        }, checkClock = now::get)
        try {
            app.openStartCamera()
            app.openArena()
            AppFixture.putOnTheProjector(app)
            awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }
            app.cameraView.updateBackground(BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR), Optional.empty())
            awaitTrue { lookedOn.get() != null }

            now.set(CalibrationCheck.DEFAULT_TIME_LIMIT)
            awaitTrue { app.check.value != CheckState.Checking }

            assertEquals(CheckState.NotVerified(CalibrationCheck.Reason.PATTERN_NOT_SEEN), app.check.value)
            assertNotSame(Thread.currentThread(), lookedOn.get())
            assertNull(app.arena.value!!.projection.value)
            assertNull(app.arena.value!!.background.value)
        } finally {
            app.close()
        }
    }

    @Test
    fun rule6TheUiThreadNeverWaitsOnTheCamera() {
        val slow = TestProblems.SlowCamera()
        val catalog = ExerciseCatalog()
        val app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), catalog, AppFixture.oneCamera(slow), { AppFixture.ownerScreens }, ManualClock(), { it.run() })
        try {
            // All of this on the calling (UI) thread while the camera's open() is stuck
            assertTimeoutPreemptively(Duration.ofSeconds(2)) {
                app.launch()
                awaitTrue { app.openingCamera.value == "Slow camera" }
                app.openArena()
                app.startCalibration()
                app.navigate(Destination.SETUP)
                app.closeArena()
            }
            assertNotSame(Thread.currentThread(), slow.openedOn)

            slow.release.countDown()
            awaitTrue { app.camera.value != null }
        } finally {
            app.close()
        }
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}
