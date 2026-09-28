package com.shootoff.compose.app

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.geom.Rect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The INFO lines an automatic calibration logs on AppState's own logger (spec §8 Revision 3, decision 11):
 * "Auto-calibration's own outcome... is logged at INFO." Captured with logback's ListAppender, already on
 * the test classpath through core's logback-classic dependency, rather than a new one.
 */
class TestCalibrationLogging {
    private val appender = ListAppender<ILoggingEvent>()
    private val logbackLogger = LoggerFactory.getLogger(AppState::class.java) as LogbackLogger

    private var app = AppFixture.appWithCamera()

    @BeforeEach
    fun attach() {
        appender.start()
        logbackLogger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logbackLogger.detachAppender(appender)
        app.close()
    }

    // The appender adds under its own lock (AppenderBase.doAppend), from whichever thread logs
    private fun messages() = synchronized(appender) { appender.list.map { it.formattedMessage } }

    @Test
    fun anAutomaticCalibrationLogsItsStartAndItsSuccess() {
        app.setRememberCalibration(true)
        app.openStartCamera()
        app.openArena()
        AppFixture.putOnTheProjector(app)

        awaitTrue { app.calibration.value?.state?.value?.message == Message.AUTO_CALIBRATING }
        assertTrue(messages().contains("Calibrating automatically: the arena opened"))

        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertTrue(messages().any { it.startsWith("Calibration succeeded: found by the camera, bounds") })
    }

    @Test
    fun anUnattendedCalibrationThatTimesOutLogsTheTimeoutWithTheActualTimeout() {
        val timers = CopyOnWriteArrayList<Pair<Long, Runnable>>()
        app.close()
        app = AppFixture.appWithCamera(calibrationTimers = { task, delay ->
            timers += delay to task
            CompletableFuture<Void>()
        })
        app.setRememberCalibration(true)
        app.openStartCamera()
        app.openArena()
        AppFixture.putOnTheProjector(app)

        awaitTrue { timers.any { it.first == CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED } }
        timers.filter { it.first == CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED }.forEach { it.second.run() }

        val expectedSeconds = CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED / 1000
        assertTrue(messages().contains("The pattern wasn't found in $expectedSeconds s: calibration ended"))
    }

    // The owner's launch (Plan 8's hardware check): the camera opened a moment after the arena, and the log said
    // "the camera came back"
    @Test
    fun aCameraOpeningAfterTheArenaIsLoggedAsOpeningNotComingBack() {
        app.setRememberCalibration(true)
        app.openArena()
        AppFixture.putOnTheProjector(app)

        app.openStartCamera()

        awaitTrue { app.calibration.value?.state?.value?.message == Message.AUTO_CALIBRATING }
        assertTrue(messages().contains("Calibrating automatically: the camera opened"))
    }

    @Test
    fun theLostCameraReopeningIsLoggedAsComingBack() {
        app.setRememberCalibration(true)
        app.openStartCamera()
        app.openArena()
        AppFixture.putOnTheProjector(app)
        awaitTrue { app.calibration.value?.state?.value?.message == Message.AUTO_CALIBRATING }
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)
        app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)

        assertTrue(app.openCamera(AppFixture.TestCamera()))

        awaitTrue { messages().contains("Calibrating automatically: the camera came back") }
    }

    // Plan 8's final review: after an automatic calibration timed out, a Calibrate the owner pressed logged the
    // automatic one's stale "ms since the pattern first showed". It logs its own pattern's time.
    @Test
    fun aCalibrationTheOwnerStartsAfterAnAutomaticOneTimedOutLogsItsOwnPatternTime() {
        val now = AtomicLong(0)
        val timers = CopyOnWriteArrayList<Pair<Long, Runnable>>()
        app.close()
        app = AppFixture.appWithCamera(checkClock = now::get, calibrationTimers = { task, delay ->
            timers += delay to task
            CompletableFuture<Void>()
        })
        app.setRememberCalibration(true)
        app.openStartCamera()
        app.openArena()
        AppFixture.putOnTheProjector(app)
        awaitTrue { timers.any { it.first == CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED } }
        now.set(60_000)
        timers.filter { it.first == CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED }.forEach { it.second.run() }

        app.startCalibration()
        now.set(61_500)
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        val success = messages().single { it.startsWith("Calibration succeeded") }
        assertTrue(success.endsWith("1500 ms since it started, 1500 ms since the pattern first showed"), success)
    }

    // Plan 9's hardware check: after an arena reopen the log said "2217 ms since it started, 7261 ms since the
    // pattern first showed". The arena had filled the projector's screen about 5 s before its calibration started;
    // the pattern showed only then (spec §8 Revision 5, decision 4).
    @Test
    fun thePatternTimeCountsFromWhenThePatternShowedNotFromWhenTheArenaFilledTheScreen() {
        val now = AtomicLong(0)
        app.close()
        app = AppFixture.appWithCamera(checkClock = now::get, patternSettleMillis = 300)
        app.setRememberCalibration(true)
        app.openStartCamera()
        app.openArena()
        AppFixture.putOnTheProjector(app)

        // The arena filled the screen at 0; the calibration starts, and the pattern shows, at 5000
        Thread.sleep(100)
        now.set(5000)
        awaitTrue { app.calibration.value?.state?.value?.message == Message.AUTO_CALIBRATING }
        now.set(7000)
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        val success = messages().single { it.startsWith("Calibration succeeded") }
        assertTrue(success.endsWith("2000 ms since it started, 2000 ms since the pattern first showed"), success)
    }

    @Test
    fun anArenaClosedMidCalibrationLeavesNoTimesForTheNextOne() {
        val now = AtomicLong(0)
        app.close()
        app = AppFixture.appWithCamera(checkClock = now::get)
        app.setRememberCalibration(true)
        app.openStartCamera()
        app.openArena()
        AppFixture.putOnTheProjector(app)
        awaitTrue { app.calibration.value?.state?.value?.message == Message.AUTO_CALIBRATING }
        now.set(1000)
        app.closeArena()

        now.set(10_000)
        app.openArena()
        AppFixture.putOnTheProjector(app)
        awaitTrue { app.calibration.value?.state?.value?.message == Message.AUTO_CALIBRATING }
        now.set(12_000)
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        val success = messages().single { it.startsWith("Calibration succeeded") }
        assertTrue(success.endsWith("2000 ms since it started, 2000 ms since the pattern first showed"), success)
    }

    // So the log tells a calibration the owner cancelled from one that ended by itself (Plan 8's replug: a success
    // "1222 ms since it started" after 30 s of searching reads as a Cancel and a fresh Calibrate)
    @Test
    fun cancellingACalibrationIsLoggedWhicheverCancelIsPressed() {
        app.openStartCamera()
        app.openArena()
        app.arena.value!!.setFullScreen(true)
        app.startCalibration()

        // The feed's own Cancel goes straight to the controller
        app.calibration.value!!.cancel()

        assertTrue(messages().any { it.startsWith("Calibration cancelled") }, messages().toString())
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}
