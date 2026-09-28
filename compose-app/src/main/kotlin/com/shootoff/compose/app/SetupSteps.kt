/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.compose.app

import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.calibration.text
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Setup's steps, in order */
enum class Step(val label: String) {
    CAMERA("Camera"),
    PROJECTOR("Projector"),
    CALIBRATE("Calibrate"),
}

/** A step's state: done (✓), the next one to do (highlighted), or waiting its turn */
enum class StepState { DONE, NEXT, WAITING }

/**
 * Where setup stands: each step's state, and whether a projector drill can run.
 */
data class SetupSteps(val camera: StepState, val projector: StepState, val calibrate: StepState) {
    val ready: Boolean get() = camera == StepState.DONE && projector == StepState.DONE && calibrate == StepState.DONE

    operator fun get(step: Step): StepState = when (step) {
        Step.CAMERA -> camera
        Step.PROJECTOR -> projector
        Step.CALIBRATE -> calibrate
    }
}

/** The first step not done is the next; the ones after it wait. */
fun setupSteps(cameraOpen: Boolean, arenaOpen: Boolean, calibrated: Boolean): SetupSteps {
    val done = listOf(cameraOpen, arenaOpen, calibrated)
    val next = done.indexOfFirst { !it }
    val states = done.mapIndexed { index, isDone ->
        when {
            isDone -> StepState.DONE
            index == next -> StepState.NEXT
            else -> StepState.WAITING
        }
    }
    return SetupSteps(states[0], states[1], states[2])
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** Setup's confirmation that the calibration the user asked for worked */
fun calibrationCompleteText(at: LocalTime): String = "Calibration complete ✓ " + at.format(TIME)

/**
 * One line about the arena's calibration, for Setup's Calibrate step and Range's status chip: what is
 * missing, what is under way, or when it was calibrated.
 */
fun calibrationSummary(
    cameraOpen: Boolean,
    arenaOpen: Boolean,
    calibrating: Boolean,
    calibrated: Boolean,
    calibratedAt: LocalTime?,
    check: CheckState,
): String {
    val checking = check.text()
    return when {
        !cameraOpen -> "No camera"
        !arenaOpen -> "No arena"
        calibrating -> "Calibrating…"
        checking != null -> checking
        calibrated -> "✓ Calibrated" + (calibratedAt?.let { " " + it.format(TIME) } ?: "")
        else -> "Not calibrated"
    }
}

/**
 * What Range's not-ready card says after its "Calibrate —" step (it shows only with a camera): the summary, except
 * that a pattern not found says to press the card's own Set up button.
 */
fun notReadyCalibrateDetail(arenaOpen: Boolean, calibrating: Boolean, calibrated: Boolean, calibratedAt: LocalTime?, check: CheckState): String =
    if (arenaOpen && !calibrating && check == CheckState.NotFound) {
        "the pattern wasn't found. Press Set up to calibrate."
    } else {
        calibrationSummary(true, arenaOpen, calibrating, calibrated, calibratedAt, check)
    }
