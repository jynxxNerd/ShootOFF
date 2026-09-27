package com.shootoff.compose.drill

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.exercise.DelayRange
import com.shootoff.exercise.TextStyle
import com.shootoff.geom.Size
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TestDrillPanel {
    @get:Rule
    val compose = createComposeRule()

    private val drill = DrillState()
    private val heard = mutableListOf<String>()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RangeTheme(dark = true) { content() }
            }
        }
    }

    @Test
    fun theCardShowsTheDrillsNameTextsAndButtons() {
        drill.setName("Random Target PAR Drill with Score")
        drill.addText(DrillText(1, "Score: 35", 10.0, 10.0, TextStyle(40.0, "white", "transparent")))
        drill.addText(DrillText(2, "Round: 4/10\nPar 4.00", 300.0, 10.0, TextStyle(40.0, "white", "transparent")))
        drill.addButton(DrillButton(3, "Pause") { heard += "pause" })
        drill.addButton(DrillButton(4, "Clear Shots") { heard += "clear" })
        show { DrillCard(drill) }

        compose.onNodeWithText("RANDOM TARGET PAR DRILL WITH SCORE").assertExists()
        compose.onNodeWithTag("drill-text-0").assertTextEquals("Score: 35")
        compose.onNodeWithTag("drill-text-1").assertTextEquals("Round: 4/10")
        compose.onNodeWithTag("drill-button-Pause").performClick()
        compose.onNodeWithTag("drill-button-Clear Shots").performClick()

        assertEquals(listOf("pause", "clear"), heard)
    }

    @Test
    fun noDrillNoCard() {
        show { DrillCard(drill) }

        compose.onNodeWithTag("drill-card").assertDoesNotExist()
    }

    @Test
    fun theShotTimerShowsTheExercisesColumnsAndItsCoralRow() {
        val timer = ShotTimerModel()
        timer.addColumn("Length")
        timer.addColumn("Score")
        timer.appendShotRow(ScaledShot(ShotColor.RED, 1.0, 2.0, 1500), false, false)
        timer.setColumnValue("Score", "10")
        timer.addTimerRow(5600, "coral")
        timer.setColumnValue("Length", "4.20")
        timer.setColumnValue("Score", "0")
        show { Box(Modifier.size(600.dp, 300.dp)) { ShotTimerTable(timer) } }

        compose.onNodeWithText("Length").assertExists()
        compose.onNodeWithText("4.20").assertExists()
        compose.onNodeWithTag("timer-row-0").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "plain"))
        compose.onNodeWithTag("timer-row-1").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "coral"))
    }

    @Test
    fun numberSettingsStepAndTakeTypedValuesOnEnter() {
        drill.addSetting(NumberSetting(1, "Rounds", 10.0, 1.0, 20.0, 1.0) { value ->
            heard += "rounds $value"
            drill.setSettingValue(1, value)
        })
        show { DrillSettings(drill) }

        compose.onNodeWithTag("setting-Rounds-up").performClick()
        compose.onNodeWithTag("setting-Rounds").performTextReplacement("15")
        compose.onNodeWithTag("setting-Rounds").performKeyInput { pressKey(Key.Enter) }
        // Out of range: ignored, and the field shows the value again
        compose.onNodeWithTag("setting-Rounds").performTextReplacement("99")
        compose.onNodeWithTag("setting-Rounds").performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("rounds 11.0", "rounds 15.0"), heard)
        compose.onNodeWithTag("setting-Rounds").assertTextEquals("15")
    }

    @Test
    fun parTimeAndStartDelayReachTheExercise() {
        drill.setTiming(TimingControls(true, 2.0, DelayRange(4, 8), { heard += "par $it" }, { min, max -> heard += "delay $min-$max" }))
        show { DrillSettings(drill) }

        compose.onNodeWithTag("par-time-down").performClick()
        compose.onNodeWithTag("delay-max").performTextReplacement("6")
        compose.onNodeWithTag("delay-max").performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("par 1.9", "delay 4-6"), heard)
    }

    @Test
    fun anExercisesTextSitsAtItsPlaceOnTheSurface() {
        drill.addText(DrillText(7, "Score: 0", 100.0, 50.0, TextStyle(40.0, "white", "transparent")))
        // A 1280x720 arena shown at half size
        show { Box(Modifier.size(640.dp, 360.dp)) { ExerciseOverlay(drill, SurfaceTransform.fit(Size(1280.0, 720.0), 640f, 360f)) } }

        compose.onNodeWithTag("exercise-text-7").assertLeftPositionInRootIsEqualTo(50.dp).assertTopPositionInRootIsEqualTo(25.dp)
    }
}
