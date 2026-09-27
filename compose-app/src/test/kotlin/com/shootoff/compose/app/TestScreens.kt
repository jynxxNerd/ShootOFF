package com.shootoff.compose.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.geom.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TestScreens {
    @get:Rule
    val compose = createComposeRule()

    private var app = AppFixture.app()

    @After
    fun close() = app.close()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { RangeTheme(dark = true) { content() } }

    @Test
    fun theSwitchOffersTheArenaOnlyOnceItIsOpen() {
        show { ShootOffApp(app) }

        compose.onNodeWithTag("view-camera").assertIsSelected()
        compose.onNodeWithTag("view-arena").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Open the arena first"))

        compose.onNodeWithTag("open-arena").performClick()
        compose.onNodeWithTag("view-arena").assertIsEnabled().performClick()

        compose.onNodeWithTag("view-arena").assertIsSelected()
        compose.onNodeWithTag("arena-view").assertExists()
    }

    @Test
    fun withoutAProjectorScreenTheSwitchSaysSo() {
        app.close()
        app = AppFixture.app(listOf(Rect(0.0, 0.0, 1920.0, 1080.0)))
        show { ShootOffApp(app) }

        compose.onNodeWithTag("arena-hint").assertExists()
        compose.onNodeWithText("No projector screen found").assertExists()
    }

    @Test
    fun drillsMarkProjectorDrillsUntilTheArenaIsOpen() {
        show { DrillsScreen(app) }

        compose.onNodeWithTag("drill-Projector drill").assertExists()
        compose.onNodeWithText(NEEDS_ARENA).assertExists()

        app.openArena()
        compose.waitForIdle()
        compose.onNodeWithText(NEEDS_ARENA).assertDoesNotExist()
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
