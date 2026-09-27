package com.shootoff.compose.app

import com.shootoff.compose.shell.Destination
import com.shootoff.geom.Rect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class TestSetupModel {
    private val app = AppFixture.appWithCamera()

    @AfterEach
    fun close() = app.close()

    private fun setUpOnTheProjector() {
        app.openStartCamera()
        app.openArena()
        app.arena.value!!.setFullScreen(true)
        app.navigate(Destination.SETUP)
    }

    @Test
    fun theGridShowsOnlyOnSetupAndNeverOverCalibrationOrADrill() {
        setUpOnTheProjector()
        val arena = app.arena.value!!

        app.showGrid(true)
        assertTrue(arena.grid.value)
        app.navigate(Destination.RANGE)
        assertFalse(arena.grid.value)

        app.navigate(Destination.SETUP)
        app.showGrid(true)
        app.startCalibration()
        assertFalse(arena.grid.value)
        app.showGrid(true)
        assertFalse(arena.grid.value)
        app.cancelCalibration()

        app.showGrid(true)
        assertTrue(app.startDrill(AppFixture.feedDrill))
        assertFalse(arena.grid.value)
    }

    @Test
    fun aCalibrationFinishedOnSetupGoesBackToTheRange() {
        setUpOnTheProjector()

        app.startCalibration()
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertEquals(Destination.RANGE, app.destination.value)
    }

    @Test
    fun theManualBoxBringsTheOwnerToSetupWhereTheFeedIs() {
        app.openStartCamera()
        app.openArena()
        app.startCalibration()
        assertEquals(Destination.RANGE, app.destination.value)

        app.showCalibratingFeed()

        assertEquals(Destination.SETUP, app.destination.value)
    }
}
