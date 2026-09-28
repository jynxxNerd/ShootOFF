package com.shootoff.compose.app

import com.shootoff.calibration.CalibrationCheck.Reason
import com.shootoff.compose.calibration.CheckState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime

class TestSetupSteps {
    @Test
    fun theFirstStepNotDoneIsNextAndTheOnesAfterItWait() {
        assertEquals(SetupSteps(StepState.NEXT, StepState.WAITING, StepState.WAITING), setupSteps(false, false, false))
        // The projector can be done before the camera
        assertEquals(SetupSteps(StepState.NEXT, StepState.DONE, StepState.WAITING), setupSteps(false, true, false))
        assertEquals(SetupSteps(StepState.DONE, StepState.DONE, StepState.NEXT), setupSteps(true, true, false))
        assertFalse(setupSteps(true, true, false).ready)
        assertTrue(setupSteps(true, true, true).ready)
    }

    @Test
    fun theSummarySaysWhatIsMissingWhatIsUnderWayOrWhenItWasCalibrated() {
        val idle = CheckState.Idle
        assertEquals("No camera", calibrationSummary(false, true, false, false, null, idle))
        assertEquals("No arena", calibrationSummary(true, false, false, false, null, idle))
        assertEquals("Calibrating…", calibrationSummary(true, true, true, true, null, idle))
        assertEquals("Checking the saved calibration…", calibrationSummary(true, true, false, false, null, CheckState.Checking))
        assertEquals(
            "Calibrating once the arena is on the projector…",
            calibrationSummary(true, true, false, false, null, CheckState.WaitingToCalibrate),
        )
        assertEquals(
            "The pattern wasn't found: not calibrated — calibrate on Setup",
            calibrationSummary(true, true, false, false, null, CheckState.NotFound),
        )
        assertEquals("The projection moved about 14 px — recalibrate", calibrationSummary(true, true, false, false, null, CheckState.Moved(14)))
        assertEquals(
            "The pattern wasn't seen: not verified — recalibrate on Setup",
            calibrationSummary(true, true, false, false, null, CheckState.NotVerified(Reason.PATTERN_NOT_SEEN)),
        )
        assertEquals("✓ Calibrated 01:12", calibrationSummary(true, true, false, true, LocalTime.of(1, 12, 40), idle))
        assertEquals("Not calibrated", calibrationSummary(true, true, false, false, null, idle))
    }
}
