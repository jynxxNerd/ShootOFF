package com.shootoff.compose.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.shootoff.compose.theme.RangeTheme
import org.junit.Rule
import org.junit.Test

class TestAppRail {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sessionsIsShownButDisabled() {
        compose.setContent { RangeTheme(dark = true) { AppRail(Destination.RANGE, {}) } }

        compose.onNodeWithTag("rail-RANGE").assertIsEnabled().assertIsSelected()
        compose.onNodeWithTag("rail-DRILLS").assertIsEnabled()
        compose.onNodeWithTag("rail-SETTINGS").assertIsEnabled()
        compose.onNodeWithTag("rail-TARGETS").assertIsEnabled()
        compose.onNodeWithTag("rail-SESSIONS").assertIsNotEnabled()
        compose.onNodeWithTag("rail-hint").assertTextEquals("Sessions: in the JavaFX app for now")
    }

    @Test
    fun choosingADestinationSelectsIt() {
        compose.setContent {
            var selected by remember { mutableStateOf(Destination.RANGE) }
            RangeTheme(dark = false) { AppRail(selected, { selected = it }) }
        }

        compose.onNodeWithTag("rail-DRILLS").performClick()
        compose.onNodeWithTag("rail-DRILLS").assertIsSelected()
    }
}
