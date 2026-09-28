package com.shootoff.compose.app

import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationCheck.Reason
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.calibration.savedCalibrationMismatch
import com.shootoff.compose.shell.Destination
import com.shootoff.config.SavedCalibration
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import java.util.Optional
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class TestRememberedCalibration {
    private val file: File = ScratchConfig.emptyFile()

    // The owner's projector is the 1280x720 screen; the test camera's feed is 640x480
    private val saved = SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), true)

    // What the fake detector finds in any frame, and the check's clock
    private val seen = AtomicReference<Optional<Rect>>(Optional.empty())
    private val looks = AtomicLong()
    private val now = AtomicLong(1_000)
    private val frame = BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR)

    private var app = appOn(Settings(file.path, arrayOf()))

    // What the app saved. Read as plain properties: reading them back into Settings would look for the
    // machine's webcams, since the app's save writes an (empty) webcam list
    private fun savedKeys(): Map<String, String> {
        val properties = Properties()
        file.inputStream().use(properties::load)
        return properties.stringPropertyNames().filter { it.startsWith("shootoff.arena.calibration.") }.associateWith(properties::getProperty)
    }

    private fun appOn(
        settings: Settings,
        detector: CalibrationCheck.Detector<BufferedImage> = CalibrationCheck.Detector {
            looks.incrementAndGet()
            seen.get()
        },
        patternSettleMillis: Long = 0,
    ) = AppFixture.appWithCamera(settings, detector = detector, checkClock = now::get, patternSettleMillis = patternSettleMillis)

    @AfterEach
    fun close() = app.close()

    // A remembered calibration from the last session, as the app saved it
    private fun remembered(screen: String = "1280.0x720.0", detector: CalibrationCheck.Detector<BufferedImage>? = null) {
        app.close()
        file.writeText(
            """
            shootoff.arena.calibration.remember=true
            shootoff.arena.calibration.camera=Test camera
            shootoff.arena.calibration.feed=640.0x480.0
            shootoff.arena.calibration.screen=$screen
            shootoff.arena.calibration.bounds=100.0,80.0,400.0,300.0
            shootoff.arena.calibration.manual=true
            """.trimIndent(),
        )
        app = if (detector == null) appOn(Settings(file.path, arrayOf())) else appOn(Settings(file.path, arrayOf()), detector)
    }

    // The arena opened on the projector, and the window reached it
    private fun openArenaOnTheProjector() {
        app.openStartCamera()
        app.openArena()
        AppFixture.putOnTheProjector(app)
    }

    // The camera sends frames until the check has its outcome
    private fun sendFramesUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) {
            app.cameraView.updateBackground(frame, Optional.empty())
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    private fun calibrateWithTheCamera(bounds: Rect) {
        app.startCalibration()
        app.calibration.value!!.calibrate(bounds, Optional.empty(), false, 0)
    }

    // Calibrate, then Done on the manual box where the owner left it (the canvas is the feed's size here)
    private fun calibrateWithTheBox(bounds: Rect) {
        app.startCalibration()
        app.calibration.value!!.flow.calibrated(bounds, Optional.empty(), true)
    }

    private fun calibrating() = app.calibration.value?.state?.value?.calibrating == true

    // An automatic calibration has shown the pattern and is looking for it (in the app the flow starts on the UI
    // thread; here it runs on the watcher's, so the test waits for it to get there)
    private fun awaitAutomaticCalibrationLooking() = awaitTrue { app.calibration.value?.state?.value?.message == Message.AUTO_CALIBRATING }

    @Test
    fun withRememberOffNothingIsSaved() {
        openArenaOnTheProjector()
        calibrateWithTheCamera(Rect(100.0, 80.0, 400.0, 300.0))

        assertNotNull(app.calibratedAt.value)
        assertFalse(file.readText().contains("shootoff.arena.calibration."))
    }

    @Test
    fun withTheOptionOnACalibrationTheCameraFoundSavesOnlyThatTheOptionIsOn() {
        app.setRememberCalibration(true)
        openArenaOnTheProjector()

        calibrateWithTheCamera(Rect(100.0, 80.0, 400.0, 300.0))

        assertEquals(mapOf("shootoff.arena.calibration.remember" to "true"), savedKeys())
    }

    @Test
    fun turningTheOptionOnSavesTheManualBoxThereIsAndOffForgetsIt() {
        openArenaOnTheProjector()
        calibrateWithTheBox(Rect(100.0, 80.0, 400.0, 300.0))

        app.setRememberCalibration(true)
        assertEquals(SAVED_KEYS, savedKeys())

        app.setRememberCalibration(false)
        assertEquals(emptyMap<String, String>(), savedKeys())
    }

    @Test
    fun withTheOptionOnOpeningTheArenaCalibratesItOnceItIsOnTheProjector() {
        app.setRememberCalibration(true)
        app.openStartCamera()
        app.openArena()

        // Not yet on the projector: waiting, not calibrating, and nothing shows
        assertEquals(CheckState.WaitingToCalibrate, app.check.value)
        assertFalse(calibrating())
        assertNull(app.arena.value!!.background.value)

        AppFixture.putOnTheProjector(app)
        awaitAutomaticCalibrationLooking()
        // A full auto-calibration, as Calibrate does: the pattern, alone, and the owner left where they are
        assertEquals(CheckState.Idle, app.check.value)
        assertEquals("pattern.png", app.arena.value!!.background.value?.name)
        assertTrue(app.arena.value!!.covered.value)
        assertEquals(Destination.RANGE, app.destination.value)

        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
        assertNotNull(app.calibratedAt.value)
        assertEquals(Destination.RANGE, app.destination.value)
        assertEquals(mapOf("shootoff.arena.calibration.remember" to "true"), savedKeys())
    }

    // Plan 7 saved the camera's calibrations as bounds, without the manual mark: they're not reapplied (that
    // lost the perspective, spec §8 Revision 3) but calibrated afresh, and forgotten once that succeeds
    @Test
    fun aCalibrationSavedWithoutTheManualMarkIsCalibratedAfreshAndForgotten() {
        remembered()
        file.writeText(file.readText().replace("shootoff.arena.calibration.manual=true", ""))
        app.close()
        app = appOn(Settings(file.path, arrayOf()))

        openArenaOnTheProjector()
        awaitAutomaticCalibrationLooking()
        assertEquals(0, looks.get())

        app.calibration.value!!.calibrate(Rect(102.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertEquals(Rect(102.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
        assertEquals(mapOf("shootoff.arena.calibration.remember" to "true"), savedKeys())
    }

    @Test
    fun anAutomaticCalibrationThatDoesntFindThePatternEndsQuietlyWithoutTheBox() {
        val timers = CopyOnWriteArrayList<Pair<Long, Runnable>>()
        app.close()
        app = AppFixture.appWithCamera(Settings(file.path, arrayOf()), calibrationTimers = { task, delay ->
            timers += delay to task
            CompletableFuture<Void>()
        })
        app.setRememberCalibration(true)
        openArenaOnTheProjector()
        awaitTrue { timers.any { it.first == CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED } }

        // The projector is covered: the pattern is never found
        timers.filter { it.first == CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED }.forEach { it.second.run() }

        assertFalse(calibrating())
        assertEquals(CheckState.NotFound, app.check.value)
        assertNull(app.calibration.value!!.state.value.box)
        assertEquals(Destination.RANGE, app.destination.value)
        assertNull(app.arena.value!!.projection.value)
        assertNull(app.arena.value!!.background.value)
        assertTrue(app.arena.value!!.needsCalibrationLabel.value)

        // Calibrate on Setup works as ever afterwards
        assertTrue(app.startCalibration())
        assertEquals(CheckState.Idle, app.check.value)
        assertTrue(calibrating())
    }

    @Test
    fun withTheOptionOnTheCameraComingBackCalibratesTheArenaAgain() {
        app.setRememberCalibration(true)
        openArenaOnTheProjector()
        awaitAutomaticCalibrationLooking()
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)
        assertNotNull(app.arena.value!!.projection.value)

        app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)
        assertNull(app.arena.value!!.projection.value)

        // Plugged back in: the arena is still on the projector, so calibration starts once it has settled
        assertTrue(app.openCamera(AppFixture.TestCamera()))
        awaitAutomaticCalibrationLooking()
        app.calibration.value!!.calibrate(Rect(104.0, 82.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertEquals(Rect(104.0, 82.0, 400.0, 300.0), app.arena.value!!.projection.value)
    }

    @Test
    fun openingTheArenaChecksTheSavedCalibrationAndKeepsItWhenThePatternIsInPlace() {
        remembered()
        seen.set(Optional.of(Rect(102.0, 79.0, 399.0, 302.0)))

        openArenaOnTheProjector()
        assertEquals(CheckState.Checking, app.check.value)
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }
        // Only the pattern shows: no "Needs Calibration" label, targets or drill texts over it
        assertTrue(app.arena.value!!.covered.value)

        sendFramesUntil { app.check.value == CheckState.Idle }

        assertFalse(app.arena.value!!.covered.value)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.camera.value!!.projectionBounds.get())
        assertNotNull(app.calibratedAt.value)
        assertNull(app.arena.value!!.background.value)
    }

    @Test
    fun aMovedProjectionIsReportedAndTheArenaStaysUncalibrated() {
        remembered()
        // 20 px: more than twice the 8 px tolerance for this 400 px pattern
        seen.set(Optional.of(Rect(120.0, 80.0, 400.0, 300.0)))

        openArenaOnTheProjector()
        sendFramesUntil { app.check.value is CheckState.Moved }

        assertEquals(CheckState.Moved(20), app.check.value)
        assertNull(app.arena.value!!.projection.value)
        assertNull(app.calibratedAt.value)
        assertNull(app.arena.value!!.background.value)
    }

    @Test
    fun aSavedCalibrationForAnotherProjectorIsNotCheckedAndAsksForRecalibration() {
        remembered(screen = "1920.0x1080.0")

        openArenaOnTheProjector()

        assertEquals(
            CheckState.DoesntFit("The saved calibration was made for a 1920×1080 projector; this one is 1280×720"),
            app.check.value,
        )
        assertNull(app.arena.value!!.background.value)
        assertNull(app.arena.value!!.projection.value)
    }

    @Test
    fun aSavedCalibrationFitsOnlyItsOwnCameraFeedAndProjector() {
        val projector = Rect(4480.0, 0.0, 1280.0, 720.0)
        val feed = Size(640.0, 480.0)

        assertNull(savedCalibrationMismatch(saved, "Test camera", feed, projector))
        assertEquals("No projector screen found", savedCalibrationMismatch(saved, "Test camera", feed, null))
        assertEquals("The saved calibration was made with another camera (Test camera)", savedCalibrationMismatch(saved, "C920", feed, projector))
        assertEquals(
            "The saved calibration was made at 640×480; the camera is at 1280×720",
            savedCalibrationMismatch(saved, "Test camera", Size(1280.0, 720.0), projector),
        )
    }

    // Final review (Plan 7): a replugged camera can re-enumerate under another /dev/videoN, so the saved
    // camera's name and the plugged-in one's differ only in that trailing device path; the match must use
    // compose-app's sameCamera helper (CameraSource.kt), not an exact name comparison.
    @Test
    fun savedCalibrationMismatchMatchesACameraRenumberedToAnotherDeviceNode() {
        val projector = Rect(4480.0, 0.0, 1280.0, 720.0)
        val feed = Size(640.0, 480.0)
        val savedOnVideo0 = SavedCalibration(
            "UVC Camera (046d:0825) /dev/video0", feed, Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), true,
        )

        assertNull(savedCalibrationMismatch(savedOnVideo0, "UVC Camera (046d:0825) /dev/video2", feed, projector))
    }

    @Test
    fun calibratingDuringTheCheckStopsItFirstSoThePatternIsNeverSavedAsTheBackground() {
        remembered()
        openArenaOnTheProjector()
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        calibrateWithTheCamera(Rect(120.0, 90.0, 400.0, 300.0))

        // Once calibration ends, the arena's own background is back, not the check's pattern
        assertEquals(CheckState.Idle, app.check.value)
        assertNull(app.arena.value!!.background.value)
        assertEquals(Rect(120.0, 90.0, 400.0, 300.0), app.arena.value!!.projection.value)
    }

    @Test
    fun closingTheArenaMidCheckStopsIt() {
        remembered()
        openArenaOnTheProjector()
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        app.closeArena()
        val looked = looks.get()
        repeat(5) {
            app.cameraView.updateBackground(frame, Optional.empty())
            Thread.sleep(20)
        }

        assertEquals(CheckState.Idle, app.check.value)
        assertEquals(looked, looks.get())
    }

    @Test
    fun unpluggingTheCameraMidCheckStopsItAndKeepsTheArenaOpen() {
        remembered()
        openArenaOnTheProjector()
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)

        assertNotNull(app.arena.value)
        assertEquals(CheckState.Idle, app.check.value)
        assertNull(app.cameraView.frameTap)
        assertNull(app.arena.value!!.background.value)
        assertFalse(app.arena.value!!.covered.value)
    }

    @Test
    fun whenTheCameraComesBackTheSavedCalibrationIsCheckedAgain() {
        remembered()
        seen.set(Optional.of(Rect(102.0, 79.0, 399.0, 302.0)))
        openArenaOnTheProjector()
        sendFramesUntil { app.arena.value!!.projection.value != null }

        app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)
        assertNull(app.arena.value!!.projection.value)
        assertTrue(app.arena.value!!.needsCalibrationLabel.value)

        // Plugged back in (the reconnect, or the owner's pick): the same camera, as a new device
        assertTrue(app.openCamera(AppFixture.TestCamera()))

        assertEquals(CheckState.Checking, app.check.value)
        sendFramesUntil { app.check.value == CheckState.Idle }
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
    }

    // Review fix (Task 5 round 1): an unpausable projector drill must not keep running with the camera
    // gone, since the reconnect's check would show the pattern under it and could change the arena
    // underneath it.
    @Test
    fun losingTheCameraStopsAnUnpausableProjectorDrillAndTheReconnectChecksWithNothingRunning() {
        remembered()
        seen.set(Optional.of(Rect(102.0, 79.0, 399.0, 302.0)))
        openArenaOnTheProjector()
        sendFramesUntil { app.arena.value!!.projection.value != null }
        assertTrue(app.startDrill(AppFixture.unpausableDrill))
        assertNotNull(app.runner.running.value)

        app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)

        assertNull(app.runner.running.value)

        // Plugged back in: the saved calibration is checked again, with nothing running under it
        assertTrue(app.openCamera(AppFixture.TestCamera()))

        assertEquals(CheckState.Checking, app.check.value)
        assertNull(app.runner.running.value)
        sendFramesUntil { app.check.value == CheckState.Idle }
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
    }

    @Test
    fun turningRememberOffMidCheckStopsItQuietly() {
        remembered()
        openArenaOnTheProjector()
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        app.setRememberCalibration(false)

        assertEquals(CheckState.Idle, app.check.value)
        assertNull(app.arena.value!!.background.value)
        assertNull(app.arena.value!!.projection.value)
        assertEquals(emptyMap<String, String>(), savedKeys())
    }

    @Test
    fun cancellingTheCheckLeavesTheArenaUncalibratedAndNotVerified() {
        remembered()
        openArenaOnTheProjector()
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        app.cancelCheck()

        assertEquals(CheckState.NotVerified(Reason.CANCELLED), app.check.value)
        assertNull(app.arena.value!!.background.value)
        assertNull(app.arena.value!!.projection.value)
    }

    // Rule 6 ("Nothing blocks", spec §8): the UI thread never waits on the check, even while a slow
    // detection (the exhaustive chessboard search PatternDetector falls back to) is under way.
    @Test
    fun cancellingDuringASlowDetectionNeverBlocksTheCallingThreadAndTheLateOutcomeChangesNothing() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        remembered(
            detector = CalibrationCheck.Detector {
                started.countDown()
                release.await()
                Optional.of(saved.bounds)
            },
        )
        try {
            openArenaOnTheProjector()
            awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

            app.cameraView.updateBackground(frame, Optional.empty())
            assertTrue(started.await(5, TimeUnit.SECONDS), "the detector should have been reached")

            val stopped = CountDownLatch(1)
            Thread { app.cancelCheck(); stopped.countDown() }.start()
            assertTrue(stopped.await(500, TimeUnit.MILLISECONDS), "cancelCheck() waited on the blocked detector")

            assertEquals(CheckState.NotVerified(Reason.CANCELLED), app.check.value)
            assertNull(app.arena.value!!.projection.value)

            // The detection, blocked the whole time, now finds a perfect match: too late to matter
            release.countDown()
            Thread.sleep(200)

            assertEquals(CheckState.NotVerified(Reason.CANCELLED), app.check.value)
            assertNull(app.arena.value!!.projection.value)
            assertNull(app.camera.value!!.projectionBounds.orElse(null))
        } finally {
            release.countDown()
        }
    }

    // Finding 1 (Plan 6 final review): opening the arena before the camera is open leaves the check
    // NotVerified(NO_CAMERA); once the camera opens, publish() must retry the check rather than leaving
    // Setup stuck saying "No camera to check with" over a live feed.
    @Test
    fun openingTheArenaBeforeTheCameraOpensRetriesTheCheckOnceTheCameraOpens() {
        remembered()
        seen.set(Optional.of(Rect(102.0, 79.0, 399.0, 302.0)))

        app.openArena()
        assertEquals(CheckState.NotVerified(Reason.NO_CAMERA), app.check.value)

        app.openStartCamera()
        assertEquals(CheckState.Checking, app.check.value)

        AppFixture.putOnTheProjector(app)
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }
        sendFramesUntil { app.check.value == CheckState.Idle }

        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.camera.value!!.projectionBounds.get())
        assertNotNull(app.calibratedAt.value)
        assertNull(app.arena.value!!.background.value)
    }

    // Finding 2 (Plan 6 final review): the arena leaving full screen mid-check (F11) must not let a frame,
    // now measured in a window, be scored as Moved. The check pauses (state stays Checking) and restarts
    // once the arena is full screen again.
    @Test
    fun leavingFullScreenMidCheckStopsItQuietlyAndItRestartsOnceFullScreenReturns() {
        remembered()
        openArenaOnTheProjector()
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        app.arena.value!!.setFullScreen(false)
        awaitTrue { app.arena.value!!.background.value == null }

        // This would read as a 14 px move if it were scored; it must not be, since it's from a windowed arena
        seen.set(Optional.of(Rect(114.0, 80.0, 400.0, 300.0)))
        val looked = looks.get()
        repeat(5) {
            app.cameraView.updateBackground(frame, Optional.empty())
            Thread.sleep(20)
        }

        assertEquals(CheckState.Checking, app.check.value)
        assertEquals(looked, looks.get())
        assertNull(app.arena.value!!.projection.value)

        // Back to full screen with a matching frame: the calibration is kept
        seen.set(Optional.of(Rect(102.0, 79.0, 399.0, 302.0)))
        app.arena.value!!.setFullScreen(true)
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }
        sendFramesUntil { app.check.value == CheckState.Idle }

        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
        assertNotNull(app.calibratedAt.value)
    }

    @Test
    fun aManualBoxCalibrationIsRememberedAsTheBoxAndMarkedAsOne() {
        app.setRememberCalibration(true)
        openArenaOnTheProjector()

        app.startCalibration()
        // The box, where the owner left it, as Done hands it to the flow
        app.calibration.value!!.flow.calibrated(Rect(90.0, 70.0, 420.0, 310.0), Optional.empty(), true)

        assertEquals(CheckState.Idle, app.check.value)
        assertEquals("90.0,70.0,420.0,310.0", savedKeys()["shootoff.arena.calibration.bounds"])
        assertEquals("true", savedKeys()["shootoff.arena.calibration.manual"])
    }

    @Test
    fun aSmallDriftIsKeptAtTheFreshMeasurementWhichIsRememberedFromThenOn() {
        remembered()
        // 10 px: past the 8 px tolerance, within twice it
        seen.set(Optional.of(Rect(110.0, 80.0, 400.0, 300.0)))

        openArenaOnTheProjector()
        sendFramesUntil { app.check.value == CheckState.Idle }

        assertEquals(Rect(110.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
        assertEquals("110.0,80.0,400.0,300.0", savedKeys()["shootoff.arena.calibration.bounds"])
    }

    @Test
    fun theCheckWaitsForTheArenaToFillTheProjectorAndSettle() {
        remembered()
        app.close()
        app = appOn(Settings(file.path, arrayOf()), patternSettleMillis = 300)
        app.openStartCamera()
        app.openArena()
        val arena = app.arena.value!!

        // Full screen asked for, but the window manager hasn't resized the 640x480 window yet
        arena.setFullScreen(true)
        Thread.sleep(500)
        assertNull(arena.background.value)

        arena.setSize(Size(1280.0, 720.0))
        Thread.sleep(100)
        assertNull(arena.background.value)
        awaitTrue { arena.background.value?.name == "pattern.png" }
        assertEquals(CheckState.Checking, app.check.value)
    }

    @Test
    fun aFastFullScreenFlapNeverTurnsDetectionOnUnderTheNextPattern() {
        remembered()
        openArenaOnTheProjector()
        val camera = app.camera.value!!
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        // F11 twice, well within the 600 ms the stopped run waits to turn detection back on
        app.arena.value!!.setFullScreen(false)
        awaitTrue { app.arena.value!!.background.value == null }
        app.arena.value!!.setFullScreen(true)
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        Thread.sleep(CalibrationFlow.DETECTION_RESTART_DELAY + 300)
        assertEquals(CheckState.Checking, app.check.value)
        assertFalse(camera.isDetecting)
    }

    @Test
    fun turningRememberOnWhenItsAlreadyOnIsANoOpAndKeepsTheSavedCalibration() {
        remembered()

        app.setRememberCalibration(true)

        assertEquals(SAVED_KEYS, savedKeys())
    }

    companion object {
        val SAVED_KEYS = mapOf(
            "shootoff.arena.calibration.remember" to "true",
            "shootoff.arena.calibration.camera" to "Test camera",
            "shootoff.arena.calibration.feed" to "640.0x480.0",
            "shootoff.arena.calibration.screen" to "1280.0x720.0",
            "shootoff.arena.calibration.bounds" to "100.0,80.0,400.0,300.0",
            "shootoff.arena.calibration.manual" to "true",
        )
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}
