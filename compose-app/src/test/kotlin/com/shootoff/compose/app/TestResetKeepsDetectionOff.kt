package com.shootoff.compose.app

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Final fix (Plan 13): Reset's delayed restart of shot detection leaves it off for a drill that is getting ready. */
class TestResetKeepsDetectionOff {
    private val app = AppFixture.appWithCamera()

    @AfterEach
    fun close() = app.close()

    @Test
    fun aDrillThatPausesDetectionInOnResetKeepsItOffPastResetsOwnSecond() {
        AppFixture.setUpForProjectorDrills(app)
        assertTrue(app.startDrill(AppFixture.gettingReadyDrill))
        val camera = app.camera.value!!
        camera.setDetecting(true)
        assertTrue(app.cameras.areDetecting())

        app.reset()

        // RangeReset's restart is due 1 s after Reset; the drill's queued onReset has long paused by then
        Thread.sleep(RangeResetDelay + 500)
        assertFalse(camera.isDetecting)
    }

    private companion object {
        const val RangeResetDelay = com.shootoff.shots.RangeReset.DETECTION_PAUSE_MILLIS
    }
}
