package com.shootoff.compose.app

import androidx.compose.ui.graphics.ImageBitmap
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.camera.MockCamera
import com.shootoff.camera.cameratypes.Camera
import com.shootoff.camera.cameratypes.CameraEventListener
import com.shootoff.compose.feed.BannerKind
import com.shootoff.compose.feed.FeedFrame
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.exercise.Exercise
import com.shootoff.geom.Rect
import com.shootoff.plugins.engine.PluginEngine
import com.shootoff.plugins.engine.PluginJars
import com.shootoff.plugins.engine.V2ExerciseLoader
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

class TestProblems {
    /** A camera another program holds */
    class LockedCamera : MockCamera() {
        override fun setCameraEventListener(cameraEventListener: CameraEventListener?) {}

        override fun isOpen() = false

        override fun open() = false

        override fun isLocked() = true

        override fun getName() = "Locked camera"
    }

    /** A camera whose open() blocks until released, as real hardware can for seconds */
    class SlowCamera(name: String = "Slow camera") : AppFixture.TestCamera(name) {
        val release = CountDownLatch(1)
        val opens = AtomicInteger()

        @Volatile
        var openedOn: Thread? = null

        @Volatile
        var closed = false

        @Volatile
        private var opened = false

        override fun isOpen() = opened

        override fun open(): Boolean {
            opens.incrementAndGet()
            openedOn = Thread.currentThread()
            release.await(5, TimeUnit.SECONDS)
            opened = true
            return true
        }

        override fun close() {
            opened = false
            closed = true
        }
    }

    /** A camera whose driver throws as it opens */
    class ThrowingCamera : AppFixture.TestCamera("Broken camera") {
        @Volatile
        var closed = false

        override fun isOpen() = false

        override fun open(): Boolean = throw IllegalStateException("driver crashed")

        override fun close() {
            closed = true
        }
    }

    /** Runs what is dispatched to it only when drained */
    class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.add(block)
        }

        fun drain() {
            while (true) (queue.poll() ?: return).run()
        }
    }

    private val cameras = listOf(AppFixture.TestCamera())

    @Volatile
    private var listedOn: Thread? = null
    private val source = object : CameraSource {
        override fun cameras() = cameras.also { listedOn = Thread.currentThread() }

        override fun startCamera(settings: Settings) = cameras.firstOrNull()
    }
    private val app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), ExerciseCatalog(), source, { AppFixture.ownerScreens }, ManualClock(), { it.run() })

    @AfterEach
    fun close() = app.close()

    @Test
    fun aCameraThatCantBeOpenedLeavesTheNoCameraPanelWithTheReason() {
        assertFalse(app.openCamera(LockedCamera()))

        assertNull(app.camera.value)
        assertTrue(app.cameraProblem.value!!.startsWith("Cannot open the webcam Locked camera."))
    }

    @Test
    fun aCameraThatStopsAnsweringIsClosedAndItsLastFrameGoes() {
        app.openStartCamera()
        val manager = app.camera.value
        assertNotNull(manager)

        app.cameraProblems.showMissingCameraError(manager!!.camera)

        assertNull(app.camera.value)
        assertNull(app.feed.frame.value)
        assertEquals("ShootOFF can no longer communicate with the webcam Test camera. Was it unplugged?", app.cameraProblem.value)
    }

    @Test
    fun losingTheCameraKeepsTheArenaOpenUncalibratedAndPausesTheDrill() {
        val app = AppFixture.appWithCamera()
        try {
            AppFixture.setUpForProjectorDrills(app)
            assertTrue(app.startDrill(AppFixture.pausingDrill))
            awaitTrue { app.drill.buttons.value.any { it.label == "Pause" } }
            val host = app.runner.running.value!!.host

            app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)

            val arena = app.arena.value!!
            assertNull(arena.projection.value)
            assertTrue(arena.needsCalibrationLabel.value)
            assertNull(app.calibration.value)
            assertNull(app.calibratedAt.value)
            awaitTrue { app.drill.buttons.value.any { it.label == "Resume" } }
            assertSame(host, app.runner.running.value!!.host)
            assertEquals("No camera", calibrationSummary(app.camera.value != null, true, false, false, null, app.check.value))

            // Without Remember calibration, the camera coming back leaves Calibrate as the next step
            assertTrue(app.openCamera(AppFixture.TestCamera()))
            assertNotNull(app.calibration.value)
            assertEquals(StepState.NEXT, setupSteps(true, true, arena.projection.value != null).calibrate)
        } finally {
            app.close()
        }
    }

    // Final review (Plan 7): detachCamera moved to pauseOrStopDrillForCalibration(), which does nothing for a
    // non-projector (camera feed) drill; a camera drill with a Pause button must still pause when the camera
    // it depends on is lost.
    @Test
    fun losingTheCameraPausesARunningCameraDrill() {
        val app = AppFixture.appWithCamera()
        try {
            app.openStartCamera()
            assertTrue(app.startDrill(AppFixture.pausingFeedDrill))
            awaitTrue { app.drill.buttons.value.any { it.label == "Pause" } }

            app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)

            awaitTrue { app.drill.buttons.value.any { it.label == "Resume" } }
        } finally {
            app.close()
        }
    }

    @Test
    fun lowFpsAndBrightnessAreBannersOnTheFeed() {
        app.openStartCamera()
        val manager = app.camera.value!!

        app.cameraProblems.showFPSWarning(manager.camera, 4.0)
        app.cameraProblems.showBrightnessWarning(manager.camera)

        assertEquals(listOf(BannerKind.WARNING, BannerKind.WARNING), app.feed.banners.value.map { it.kind })
    }

    @Test
    fun aV1PluginIsSkippedAndTheV2BesideItStillLoads(@TempDir exercises: Path) {
        val previous = System.getProperty("shootoff.plugins")
        System.setProperty("shootoff.plugins", exercises.toString())
        try {
            PluginJars.build(exercises, "Old.jar", Optional.of(PluginJars.descriptor(1, "com.example.v1.OldDrill")),
                mapOf("com.example.v1.OldDrill" to "package com.example.v1; public class OldDrill {}"), classpath())
            PluginJars.build(exercises, "New.jar", Optional.of(PluginJars.descriptor(2, "com.example.v2.NewDrill")),
                mapOf("com.example.v2.NewDrill" to NEW_DRILL), classpath())
            val catalog = ExerciseCatalog()

            PluginEngine(catalog, listOf(V2ExerciseLoader()), emptyList())

            assertEquals(listOf("New drill"), catalog.entries.value.map { it.metadata().name })
        } finally {
            if (previous == null) System.clearProperty("shootoff.plugins") else System.setProperty("shootoff.plugins", previous)
        }
    }

    @Test
    fun openingACameraAfterTheArenaMakesItCalibratableWithoutCalibrating() {
        app.openArena()
        assertNull(app.calibration.value)

        app.openStartCamera()

        assertNotNull(app.calibration.value)
        assertFalse(app.calibration.value!!.state.value.calibrating)
        assertTrue(app.startCalibration())
        assertEquals(Message.FULL_SCREEN_REQUEST, app.calibration.value!!.state.value.message)
    }

    @Test
    fun switchingCamerasKeepsTheArenaOpenUncalibratedAndCalibratableWithTheNewOne() {
        app.openStartCamera()
        app.openArena()
        val old = app.calibration.value
        assertNotNull(old)

        assertTrue(app.openCamera(AppFixture.TestCamera("Other camera")))

        assertNotNull(app.arena.value)
        assertNull(app.arena.value!!.projection.value)
        assertNotNull(app.calibration.value)
        assertNotSame(old, app.calibration.value)
        assertEquals("Other camera", app.camera.value!!.camera.name)
    }

    @Test
    fun aCameraOpensOffTheCallingThreadAndSaysSoMeanwhile() {
        app.openStartCamera()
        app.openArena()
        val slow = SlowCamera()
        val opened = AtomicReference<Boolean?>()

        // Returns while the camera's open() is still blocked
        app.openCameraInBackground(slow) { opened.set(it) }

        assertNotNull(app.arena.value)
        assertNull(app.calibration.value)
        assertNull(app.camera.value)
        assertEquals("Slow camera", app.openingCamera.value)

        slow.release.countDown()
        awaitTrue { opened.get() != null }
        assertNull(app.openingCamera.value)
        assertEquals(true, opened.get())
        assertNotSame(Thread.currentThread(), slow.openedOn)
    }

    @Test
    fun aCameraThatCantBeOpenedInTheBackgroundGivesTheReason() {
        val opened = AtomicReference<Boolean?>()

        app.openCameraInBackground(LockedCamera()) { opened.set(it) }

        awaitTrue { opened.get() != null }
        assertEquals(false, opened.get())
        assertNull(app.openingCamera.value)
        assertTrue(app.cameraProblem.value!!.startsWith("Cannot open the webcam Locked camera."))
    }

    @Test
    fun theCamerasAreListedOffTheCallingThread() {
        assertNull(app.cameraList.value)

        app.refreshCameras()

        awaitTrue { app.cameraList.value != null }
        assertEquals(listOf("Test camera"), app.cameraList.value!!.map { it.name })
        assertNotSame(Thread.currentThread(), listedOn)
    }

    @Test
    fun theArenaGoingFullScreenAtOnceStillReachesCalibration() {
        val background = QueueDispatcher()
        val app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), ExerciseCatalog(), source, { AppFixture.ownerScreens }, ManualClock(), { it.run() }, background)
        try {
            app.openStartCamera()
            app.openArena()
            app.startCalibration()
            val controller = app.calibration.value!!
            assertEquals(Message.FULL_SCREEN_REQUEST, controller.state.value.message)

            // Before the app's background dispatcher has run anything
            app.arena.value!!.setFullScreen(true)
            background.drain()

            assertNotEquals(Message.FULL_SCREEN_REQUEST, controller.state.value.message)
        } finally {
            app.close()
        }
    }

    @Test
    fun aNewerOpenBeatsAnOlderSlowOneAndTheOlderCameraIsClosed() {
        val slow = SlowCamera()
        app.openCameraInBackground(slow)
        awaitTrue { slow.openedOn != null }

        assertTrue(app.openCamera(AppFixture.TestCamera("Newer camera")))
        slow.release.countDown()

        awaitTrue { slow.closed }
        assertEquals("Newer camera", app.camera.value!!.camera.name)
        assertEquals(listOf("Newer camera"), app.cameras.cameraManagers.map { it.camera.name })
        assertSame(app.camera.value, app.cameraView.cameraManager)
        assertNull(app.openingCamera.value)
    }

    @Test
    fun closingTheAppDuringABlockedOpenLeavesNothingOpen() {
        val slow = SlowCamera()
        app.openCameraInBackground(slow)
        awaitTrue { slow.openedOn != null }

        app.close()
        slow.release.countDown()

        awaitTrue { slow.closed }
        assertNull(app.camera.value)
        assertTrue(app.cameras.cameraManagers.isEmpty())
    }

    @Test
    fun anOpenThatThrowsClearsTheOpeningStateAndSaysWhy() {
        val broken = ThrowingCamera()
        val opened = AtomicReference<Boolean?>()

        app.openCameraInBackground(broken) { opened.set(it) }

        awaitTrue { opened.get() != null }
        assertEquals(false, opened.get())
        assertNull(app.openingCamera.value)
        assertNull(app.camera.value)
        assertEquals("Cannot open the webcam Broken camera: IllegalStateException: driver crashed", app.cameraProblem.value)
        assertTrue(broken.closed)
        assertTrue(app.cameras.cameraManagers.isEmpty())
    }

    @Test
    fun aSecondPickWhileACameraOpensIsIgnored() {
        val first = SlowCamera("First camera")
        val second = SlowCamera("Second camera")
        second.release.countDown()

        assertTrue(app.openCameraInBackground(first))
        assertFalse(app.openCameraInBackground(second))
        first.release.countDown()

        awaitTrue { app.camera.value != null }
        assertEquals("First camera", app.camera.value!!.camera.name)
        assertEquals(0, second.opens.get())
        assertEquals(1, app.cameras.cameraManagers.size)
    }

    @Test
    fun pickingTheOpenCameraAgainKeepsIt() {
        app.openStartCamera()
        val manager = app.camera.value!!

        app.openCameraInBackground(manager.camera)

        assertSame(manager, app.camera.value)
        assertEquals(listOf(manager), app.cameras.cameraManagers)
    }

    @Test
    fun aLostCameraThatIsNoLongerOpenLeavesTheLiveFeedAlone() {
        app.openStartCamera()
        val old = app.camera.value!!.camera
        app.openCamera(AppFixture.TestCamera("Newer camera"))
        val frame = FeedFrame(ImageBitmap(4, 3), Rect(0.0, 0.0, 640.0, 480.0))
        app.feed.showFrame(frame)

        app.cameraProblems.showMissingCameraError(old)

        assertSame(frame, app.feed.frame.value)
        assertNull(app.cameraProblem.value)
        assertEquals("Newer camera", app.camera.value!!.camera.name)
    }

    @Test
    fun pickingACameraThatIsNoLongerPluggedInSaysItIsNotConnected() {
        // Listed before it was unplugged: the system no longer has a camera by its name
        val gone = SlowCamera("HD Webcam C270")
        val unplugged = object : CameraSource {
            override fun cameras() = emptyList<AppFixture.TestCamera>()

            override fun startCamera(settings: Settings) = null

            override fun current(camera: Camera) = null
        }
        val app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), ExerciseCatalog(), unplugged, { AppFixture.ownerScreens }, ManualClock(), { it.run() })
        try {
            val opened = AtomicReference<Boolean?>()

            app.openCameraInBackground(gone) { opened.set(it) }

            awaitTrue { opened.get() != null }
            assertEquals(false, opened.get())
            assertNull(app.camera.value)
            assertNull(app.openingCamera.value)
            assertEquals("HD Webcam C270 is not connected. Plug it in, or pick another camera.", app.cameraProblem.value)
            assertEquals(0, gone.opens.get())
        } finally {
            app.close()
        }
    }

    @Test
    fun aCameraPluggedBackInIsFoundByNameAndThatOneOpens() {
        // Sarxos names include the device node, and it can change when a camera re-enumerates
        val stale = SlowCamera("UVC Camera (046d:0825) /dev/video0")
        val replugged = AppFixture.TestCamera("UVC Camera (046d:0825) /dev/video2")
        val source = object : CameraSource {
            override fun cameras() = listOf(replugged)

            override fun startCamera(settings: Settings) = null

            override fun current(camera: Camera) = cameras().firstOrNull { sameCamera(it.name, camera.name) }
        }
        val app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), ExerciseCatalog(), source, { AppFixture.ownerScreens }, ManualClock(), { it.run() })
        try {
            app.openCameraInBackground(stale)

            awaitTrue { app.camera.value != null }
            assertSame(replugged, app.camera.value!!.camera)
            assertEquals(0, stale.opens.get())
        } finally {
            app.close()
        }
    }

    // What is plugged in, for the reconnect: cameras come and go from it
    private class Pluggable(vararg cameras: Camera) : CameraSource {
        val plugged = CopyOnWriteArrayList(cameras.toList())

        override fun cameras(): List<Camera> = plugged.toList()

        override fun startCamera(settings: Settings) = plugged.firstOrNull()
    }

    private fun reconnectingApp(source: CameraSource) =
        AppState(
            Settings(ScratchConfig.emptyFile().path, arrayOf()),
            ExerciseCatalog(),
            source,
            { AppFixture.ownerScreens },
            ManualClock(),
            { it.run() },
            reconnectMillis = 20,
            reconnectRetryMillis = 20,
        )

    // The ERROR lines AppState logs while [block] runs
    private fun errorsLoggedDuring(block: () -> Unit): List<String> {
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        val logger = LoggerFactory.getLogger(AppState::class.java) as LogbackLogger
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }
    }

    @Test
    fun aLostCameraReopensByItselfWhenItIsPluggedBackIn() {
        // Sarxos names include the device node, and it can change when a camera re-enumerates
        val lost = AppFixture.TestCamera("UVC Camera (046d:0825) /dev/video0")
        val source = Pluggable(lost)
        val app = reconnectingApp(source)
        try {
            app.openStartCamera()
            app.openArena()

            app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)
            source.plugged.clear()
            assertEquals("UVC Camera (046d:0825) /dev/video0", app.waitingFor.value)
            Thread.sleep(100)
            assertNull(app.camera.value)

            val back = AppFixture.TestCamera("UVC Camera (046d:0825) /dev/video2")
            source.plugged.add(back)

            awaitTrue { app.camera.value != null }
            assertSame(back, app.camera.value!!.camera)
            assertNull(app.waitingFor.value)
            // Back on the arena it left, ready to calibrate
            assertNotNull(app.calibration.value)
        } finally {
            app.close()
        }
    }

    @Test
    fun pickingAnotherCameraWhileWaitingEndsTheWatch() {
        val lost = AppFixture.TestCamera("HD Webcam C270")
        val source = Pluggable(lost)
        val app = reconnectingApp(source)
        try {
            app.openStartCamera()
            app.cameraProblems.showMissingCameraError(lost)
            source.plugged.clear()

            assertTrue(app.openCamera(AppFixture.TestCamera("Other camera")))
            assertNull(app.waitingFor.value)
            source.plugged.add(AppFixture.TestCamera("HD Webcam C270"))

            Thread.sleep(200)
            assertEquals("Other camera", app.camera.value!!.camera.name)
        } finally {
            app.close()
        }
    }

    @Test
    fun aCameraThatIsListedButWontOpenIsTriedThreeTimesUntilItIsPluggedInAgain() {
        val source = Pluggable(AppFixture.TestCamera("HD Webcam C270"))
        val app = reconnectingApp(source)
        try {
            app.openStartCamera()
            app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)
            // Still listed, but another program has it now
            val locked = object : AppFixture.TestCamera("HD Webcam C270") {
                val opens = AtomicInteger()

                override fun isOpen() = false

                override fun open(): Boolean {
                    opens.incrementAndGet()
                    return false
                }
            }

            val errors = errorsLoggedDuring {
                source.plugged.clear()
                source.plugged.add(locked)

                awaitTrue { locked.opens.get() == AppState.RECONNECT_TRIES }
                Thread.sleep(200)
            }

            assertEquals(AppState.RECONNECT_TRIES, locked.opens.get())
            assertEquals("HD Webcam C270", app.waitingFor.value)
            // Only the last try is reported, so the owner sees one error, not one every try
            assertEquals(listOf("Cannot open the webcam HD Webcam C270"), errors)
        } finally {
            app.close()
        }
    }

    // The owner's replug (Plan 8's hardware check): the device node appeared a moment before the camera could be
    // opened, and the one try failed, leaving the camera closed until the next replug
    @Test
    fun aCameraThatFailsToReopenJustAfterItAppearsIsTriedAgainAndOpens() {
        val source = Pluggable(AppFixture.TestCamera("UVC Camera (046d:0825) /dev/video0"))
        val app = reconnectingApp(source)
        try {
            app.openStartCamera()
            app.openArena()
            app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)
            val notReadyYet = object : AppFixture.TestCamera("UVC Camera (046d:0825) /dev/video0") {
                val opens = AtomicInteger()

                @Volatile
                private var opened = false

                override fun isOpen() = opened

                override fun open(): Boolean {
                    opened = opens.incrementAndGet() > 1
                    return opened
                }
            }

            val errors = errorsLoggedDuring {
                source.plugged.clear()
                source.plugged.add(notReadyYet)

                awaitTrue { app.camera.value != null }
            }

            assertSame(notReadyYet, app.camera.value!!.camera)
            assertEquals(2, notReadyYet.opens.get())
            assertNull(app.waitingFor.value)
            assertNull(app.cameraProblem.value)
            assertEquals(emptyList<String>(), errors)
        } finally {
            app.close()
        }
    }

    // Plan 7's deferred minor: a reappearance was used up even when the reopen was declined because another
    // camera was opening; it must still be tried once that other open is over
    @Test
    fun aCameraThatAppearsWhileAnotherIsOpeningIsTriedOnceThatOpenFails() {
        val lost = AppFixture.TestCamera("HD Webcam C270")
        val source = Pluggable(lost)
        val app = reconnectingApp(source)
        try {
            app.openStartCamera()
            app.cameraProblems.showMissingCameraError(lost)
            source.plugged.clear()
            // The owner picks a camera that takes a while to open, and then fails
            val slow = object : AppFixture.TestCamera("Other camera") {
                val release = CountDownLatch(1)

                override fun isOpen() = false

                override fun open(): Boolean {
                    release.await(5, TimeUnit.SECONDS)
                    return false
                }
            }
            app.openCameraInBackground(slow)
            source.plugged.add(AppFixture.TestCamera("HD Webcam C270"))
            Thread.sleep(200)
            assertNull(app.camera.value)

            slow.release.countDown()

            awaitTrue { app.camera.value != null }
            assertEquals("HD Webcam C270", app.camera.value!!.camera.name)
        } finally {
            app.close()
        }
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }

    private val NEW_DRILL = """
        package com.example.v2;
        import java.util.Optional;
        import com.shootoff.camera.Shot;
        import com.shootoff.exercise.Exercise;
        import com.shootoff.exercise.ExerciseHost;
        import com.shootoff.plugins.ExerciseMetadata;
        import com.shootoff.targets.model.Hit;
        public class NewDrill implements Exercise {
          @Override public ExerciseMetadata metadata() { return new ExerciseMetadata("New drill", "1.0", "ShootOFF tests", "Its jar"); }
          @Override public void start(ExerciseHost host) {}
          @Override public void onShot(Shot shot, Optional<Hit> hit) {}
          @Override public void onReset() {}
          @Override public void stop() {}
        }
    """.trimIndent()

    // ShootOFF's classes, as a plugin author compiles against them
    private fun classpath(): String = listOf(System.getProperty("java.class.path"), location(Exercise::class.java), location(com.shootoff.camera.Shot::class.java))
        .joinToString(File.pathSeparator)

    private fun location(type: Class<*>) = File(type.protectionDomain.codeSource.location.path).path
}
