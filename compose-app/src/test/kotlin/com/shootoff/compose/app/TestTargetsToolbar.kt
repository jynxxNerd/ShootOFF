package com.shootoff.compose.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.courses.io.CourseIO
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinitions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class TestTargetsToolbar {
    @get:Rule
    val compose = createComposeRule()

    private val app = AppFixture.app()
    private val model = app.targetsModel
    private val layout = app.arenaLayout

    @Before
    fun show() {
        layout.setSize(Size(1280.0, 720.0))
        app.navigate(Destination.TARGETS)
        compose.setContent { RangeTheme(dark = true) { ShootOffApp(app) } }
    }

    @After
    fun close() = app.close()

    private fun shooters() = layout.targets.targetsOf(TargetOwner.USER)

    private fun reset() = layout.targets.add(
        TargetDefinitions.load(File("targets/Reset.target").toPath()),
        ResourceResolver.files(),
        Placement(5.0, 6.0, 1.0, 1.0, true),
    )

    private fun waitUntil(what: String, condition: () -> Boolean) = compose.waitUntil(what, 5000, condition)

    @Test
    fun addTargetListsTheTargetsAndAddsTheChosenOneSelected() {
        compose.onNodeWithTag("add-target").performClick()
        compose.onNodeWithTag("panel").assertExists()

        compose.onNodeWithTag("target-list").performScrollToNode(hasTestTag("target-Reset"))
        compose.onNodeWithTag("target-Reset").performClick()

        waitUntil("the target is added") { shooters().size == 1 }
        compose.onAllNodesWithTag("panel").assertCountEquals(0)
        waitUntil("the target is selected") { model.arenaEditor.selected.value == shooters().single().id }
    }

    @Test
    fun theBackgroundPanelPutsUpABundledBackgroundOrNone() {
        compose.onNodeWithTag("background").performClick()
        compose.onNodeWithTag("background-Indoor Range").performClick()
        waitUntil("the background is up") { layout.background.value?.name == "/arena/backgrounds/indoor_range.gif" }

        compose.onNodeWithTag("background").performClick()
        compose.onNodeWithTag("background-none").performClick()
        waitUntil("the background is down") { layout.background.value == null }
    }

    @Test
    fun aCourseIsSavedByNameAndReplacingOneAsksFirst() {
        reset()
        compose.onNodeWithTag("save-course").performClick()
        compose.onNodeWithTag("save").performClick()
        compose.onNodeWithTag("name-hint").assertExists()

        compose.onNodeWithTag("course-name").performTextInput("Mine")
        compose.onNodeWithTag("save").performClick()
        val file = File(model.files.courses, "Mine.course")
        waitUntil("the course is saved") { file.isFile }
        waitUntil("the save is said") { model.message.value?.text == "Saved the course Mine." }

        reset()
        compose.onNodeWithTag("save-course").performClick()
        compose.onNodeWithTag("course-name").performTextInput("Mine")
        compose.onNodeWithTag("save").performClick()
        compose.onNodeWithTag("replace-question").assertExists()
        compose.onNodeWithTag("replace").performClick()
        waitUntil("the course is replaced") { CourseIO.loadCourse(file).map { it.targets.size }.orElse(0) == 2 }
    }

    @Test
    fun aSavedCourseIsOfferedAndLoads() {
        reset()
        model.saveCourse("Mine")
        val file = File(model.files.courses, "Mine.course")
        waitUntil("the course is saved") { file.isFile }
        model.clear()

        compose.onNodeWithTag("load-course").performClick()
        compose.onNodeWithTag("course-Mine").performClick()

        waitUntil("the course is loaded") { shooters().size == 1 }
    }

    @Test
    fun clearOffersUndoForAWhile() {
        reset()
        compose.onNodeWithTag("clear").performClick()
        waitUntil("the arena is cleared") { shooters().isEmpty() }
        compose.waitUntilExactlyOneExists(hasTestTag("undo"), 5000)

        compose.onNodeWithTag("undo").performClick()
        waitUntil("the target is back") { shooters().size == 1 }
        compose.onAllNodesWithTag("cleared").assertCountEquals(0)

        compose.onNodeWithTag("clear").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("cleared"), 5000)
        compose.mainClock.advanceTimeBy(UNDO_MILLIS + 100)
        compose.waitForIdle()
        waitUntil("the offer is dropped") { model.undo.value == null }
        compose.onAllNodesWithTag("cleared").assertCountEquals(0)
    }

    @Test
    fun aProblemShowsInTheBannerUntilDismissed() {
        val file = File(model.files.courses, "broken.course")
        file.writeText("<course><target")
        compose.onNodeWithTag("load-course").performClick()
        compose.onNodeWithTag("course-broken").performClick()

        waitUntil("the problem is said") { model.message.value != null }
        val banner = model.message.value!!
        compose.onNodeWithTag("banner-${banner.id}").assertExists()
        compose.onNodeWithTag("dismiss-${banner.id}").performClick()
        assertNull(model.message.value)
    }

    @Test
    fun theCameraHasOnlyAddTargetAndClearAndItsTargetsAreHit() {
        compose.onNodeWithTag("surface-CAMERA").performClick()

        compose.onNodeWithTag("camera-feed").assertExists()
        compose.onAllNodesWithTag("background").assertCountEquals(0)
        compose.onAllNodesWithTag("load-course").assertCountEquals(0)
        compose.onAllNodesWithTag("save-course").assertCountEquals(0)

        compose.onNodeWithTag("add-target").performClick()
        compose.onNodeWithTag("target-list").performScrollToNode(hasTestTag("target-Reset"))
        compose.onNodeWithTag("target-Reset").performClick()
        waitUntil("the target is on the feed") { model.feedTargets.targetsOf(TargetOwner.USER).size == 1 }
        assertTrue(layout.targets.set.targets.isEmpty())

        // A shot on it hits it, as the feed's shot pipeline tests it
        val bounds = model.feedTargets.targetsOf(TargetOwner.USER).single().bounds
        val hit = app.feedSurface.hitTest(bounds.minX + bounds.width / 2, bounds.minY + bounds.height / 2)
        assertEquals(model.feedTargets.targetsOf(TargetOwner.USER).single().id, hit.get().targetId())
    }

    @Test
    fun theCameraTabWithNoCameraSaysSo() {
        compose.onNodeWithTag("surface-CAMERA").performClick()
        compose.onNodeWithTag("no-camera").assertExists()
    }

    @Test
    fun theUndoOfferDoesntResizeThePreview() {
        reset()
        val before = compose.onNodeWithTag("targets-arena").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("clear").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("cleared"), 5000)
        assertEquals(before, compose.onNodeWithTag("targets-arena").getUnclippedBoundsInRoot())
    }
}
