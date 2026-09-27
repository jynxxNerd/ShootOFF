package com.shootoff.compose.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shootoff.compose.theme.RangeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TestScreens {
    @get:Rule
    val compose = createComposeRule()

    private val app = AppFixture.app()

    @After
    fun close() = app.close()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { RangeTheme(dark = true) { content() } }

    @Test
    fun drillsIsALibraryMarkingProjectorDrillsWithNoStartOrStop() {
        show { DrillsScreen(app) }

        compose.onNodeWithTag("drill-Projector drill").assertExists()
        compose.onNodeWithTag("drill-Feed drill").assertExists()
        compose.onNodeWithText(NEEDS_ARENA).assertExists()
        compose.onNodeWithTag("start-drill").assertDoesNotExist()
        compose.onNodeWithTag("stop-drill").assertDoesNotExist()

        // It says what the drill needs, not whether it is there now
        app.openArena()
        compose.waitForIdle()
        compose.onNodeWithText(NEEDS_ARENA).assertExists()
    }

    @Test
    fun theRailLeadsToDrillsAndSettings() {
        show { ShootOffApp(app) }

        compose.onNodeWithTag("rail-DRILLS").performClick()
        compose.onNodeWithText("Feed drill").assertExists()

        compose.onNodeWithTag("rail-SETTINGS").performClick()
        compose.onNodeWithText("SHOT MARKER SIZE").assertExists()
        compose.onNodeWithTag("screen-4480").performClick()

        assertEquals(4480.0, app.settings.arenaPosition.get().x, 0.0)
    }
}
