package com.shootoff.compose.app

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.shootoff.compose.arena.ArenaLayoutView
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.targets.EditingOverlay
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.util.Optional

@OptIn(ExperimentalTestApi::class)
class TestTargetsScreen {
    @get:Rule
    val compose = createComposeRule()

    private val app = AppFixture.app()
    private val layout = app.arenaLayout
    private val editor = app.targetsModel.arenaEditor

    @After
    fun close() = app.close()

    // A red square, 100 arena units a side, with its top left corner at (x, y)
    private fun square(x: Double, y: Double, owner: TargetOwner = TargetOwner.USER): TargetId = layout.targets.add(
        TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 100.0, 100.0, "red", mapOf("opacity" to "1")))),
        ResourceResolver.files(),
        Placement(x, y, 1.0, 1.0, true),
        owner,
    ).id

    private fun bounds(id: TargetId): Rect = layout.targets.set.get(id).get().bounds

    // The arena (1280x720) at half size, as on the Targets screen, at one pixel per dp
    private fun showEditor() {
        layout.setSize(Size(1280.0, 720.0))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RangeTheme(dark = true) {
                    ArenaLayoutView(layout, app.arena.value, Modifier.size(640.dp, 360.dp).testTag("view")) { transform ->
                        EditingOverlay(layout.targets, editor, transform)
                    }
                }
            }
        }
    }

    @Test
    fun theRailLeadsToTheTargetsScreenWhichWorksWithTheArenaClosed() {
        compose.setContent { RangeTheme(dark = true) { ShootOffApp(app) } }

        compose.onNodeWithTag("rail-TARGETS").performClick()

        compose.onNodeWithTag("targets-screen").assertExists()
        compose.onNodeWithTag("targets-arena").assertExists()
        compose.onAllNodesWithTag("calibrating-note").assertCountEquals(0)
    }

    @Test
    fun aClickSelectsATargetAndShowsItsHandlesAndAClickOnEmptySpaceDeselects() {
        val target = square(200.0, 100.0)
        showEditor()

        compose.onNodeWithTag("editing-surface").performMouseInput { click(Offset(125f, 75f)) }
        assertEquals(target, editor.selected.value)
        compose.onAllNodesWithTag("handle-TOP_LEFT").assertCountEquals(1)

        compose.onNodeWithTag("editing-surface").performMouseInput { click(Offset(500f, 300f)) }
        assertNull(editor.selected.value)
        compose.onAllNodesWithTag("handle-TOP_LEFT").assertCountEquals(0)
    }

    @Test
    fun draggingATargetMovesItOnTheArena() {
        val target = square(200.0, 100.0)
        showEditor()

        compose.onNodeWithTag("editing-surface").performMouseInput {
            moveTo(Offset(125f, 75f))
            press()
            moveBy(Offset(20f, 10f))
            moveBy(Offset(20f, 10f))
            release()
        }
        compose.waitForIdle()

        // 40 x 20 view pixels are 80 x 40 arena units
        assertEquals(Rect(280.0, 140.0, 100.0, 100.0), bounds(target))
    }

    @Test
    fun aClickThatWobblesALittleSelectsWithoutMoving() {
        val target = square(200.0, 100.0)
        showEditor()

        compose.onNodeWithTag("editing-surface").performMouseInput {
            moveTo(Offset(125f, 75f))
            press()
            moveBy(Offset(2f, 0f))
            release()
        }
        compose.waitForIdle()

        assertEquals(target, editor.selected.value)
        assertEquals(Rect(200.0, 100.0, 100.0, 100.0), bounds(target))
    }

    @Test
    fun aRightButtonDragMovesNothing() {
        val target = square(200.0, 100.0)
        showEditor()

        compose.onNodeWithTag("editing-surface").performMouseInput {
            moveTo(Offset(125f, 75f))
            press(MouseButton.Secondary)
            moveBy(Offset(40f, 0f))
            release(MouseButton.Secondary)
        }
        compose.waitForIdle()

        assertNull(editor.selected.value)
        assertEquals(Rect(200.0, 100.0, 100.0, 100.0), bounds(target))
    }

    @Test
    fun draggingACornerHandleResizesTheTarget() {
        val target = square(200.0, 100.0)
        showEditor()
        compose.onNodeWithTag("editing-surface").performMouseInput { click(Offset(125f, 75f)) }

        compose.onNodeWithTag("handle-BOTTOM_RIGHT").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(25f, 10f))
            release()
        }
        compose.waitForIdle()

        assertEquals(Rect(200.0, 100.0, 150.0, 120.0), bounds(target))
    }

    @Test
    fun theKeysNudgeResizeRemoveAndDeselectTheSelectedTarget() {
        val target = square(200.0, 100.0)
        showEditor()
        compose.onNodeWithTag("editing-surface").performMouseInput { click(Offset(125f, 75f)) }
        val surface = compose.onNodeWithTag("editing-surface")

        surface.performKeyInput { pressKey(Key.DirectionRight) }
        surface.performKeyInput { pressKey(Key.DirectionDown) }
        assertEquals(Rect(201.0, 101.0, 100.0, 100.0), bounds(target))

        surface.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) } }
        assertEquals(101.0, bounds(target).width, 1e-9)

        surface.performKeyInput { pressKey(Key.Escape) }
        assertNull(editor.selected.value)

        compose.onNodeWithTag("editing-surface").performMouseInput { click(Offset(125f, 75f)) }
        surface.performKeyInput { pressKey(Key.Delete) }
        assertFalse(layout.targets.set.get(target).isPresent)
    }

    @Test
    fun anExercisesTargetIsBadgedAndCantBeGrabbed() {
        val target = square(200.0, 100.0, TargetOwner.EXERCISE)
        showEditor()

        compose.onAllNodesWithTag("exercise-badge").assertCountEquals(1)
        compose.onNodeWithTag("editing-surface").performMouseInput {
            moveTo(Offset(125f, 75f))
            press()
            moveBy(Offset(40f, 20f))
            release()
        }
        compose.waitForIdle()

        assertNull(editor.selected.value)
        assertEquals(Rect(200.0, 100.0, 100.0, 100.0), bounds(target))
    }

    // Spec §6: the projector shows the pattern, and the Targets screen goes on showing the targets
    @Test
    fun whileCalibratingTheScreenSaysSoAndStillShowsTheTargets() {
        square(200.0, 100.0)
        app.openArena()
        app.arena.value!!.cover(true)
        app.navigate(Destination.TARGETS)
        compose.setContent { RangeTheme(dark = true) { ShootOffApp(app) } }

        compose.onNodeWithTag("calibrating-note").assertExists().assertTextEquals(CALIBRATING_NOTE)
        val pixels = compose.onNodeWithTag("targets-arena").captureToImage().toPixelMap()
        var red = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) if (pixels[x, y] == Color.Red) red++
        assertFalse("the target isn't drawn", red == 0)
    }
}
