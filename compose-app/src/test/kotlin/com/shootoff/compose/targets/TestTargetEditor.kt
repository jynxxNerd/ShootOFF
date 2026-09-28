package com.shootoff.compose.targets

import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class TestTargetEditor {
    private val targets = SurfaceTargets(clock = ManualClock())
    private val editor = TargetEditor(targets) { Size(640.0, 480.0) }

    // 100 x 50, its top left corner at (x, y)
    private fun box(x: Double = 100.0, y: Double = 100.0, width: Double = 100.0, height: Double = 50.0, owner: TargetOwner = TargetOwner.USER): TargetId =
        targets.add(
            TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, width, height, "red", mapOf()))),
            ResourceResolver.files(),
            Placement(x, y, 1.0, 1.0, true),
            owner,
        ).id

    private fun bounds(id: TargetId): Rect = targets.set.get(id).get().bounds

    private fun assertBounds(expected: Rect, id: TargetId) {
        val actual = bounds(id)
        val close = listOf(
            expected.minX to actual.minX,
            expected.minY to actual.minY,
            expected.width to actual.width,
            expected.height to actual.height,
        ).all { (e, a) -> Math.abs(e - a) < 1e-6 }
        assertTrue(close, "expected $expected but was $actual")
    }

    @Test
    fun aClickSelectsTheShootersTopmostTargetAndEmptySpaceSelectsNothing() {
        val below = box()
        val above = box(150.0, 120.0)

        editor.click(Point(160.0, 130.0))
        assertEquals(above, editor.selected.value)

        editor.click(Point(110.0, 110.0))
        assertEquals(below, editor.selected.value)

        editor.click(Point(500.0, 400.0))
        assertNull(editor.selected.value)
    }

    @Test
    fun anExercisesTargetCantBeSelectedAndClicksGoThroughIt() {
        val shooters = box()
        val exercises = box(owner = TargetOwner.EXERCISE)

        editor.click(Point(110.0, 110.0))
        assertEquals(shooters, editor.selected.value)

        targets.remove(shooters)
        editor.click(Point(110.0, 110.0))
        assertNull(editor.selected.value)
        assertFalse(editor.select(exercises))
        assertNull(editor.selected.value)
    }

    @Test
    fun aTargetAnExerciseHidIsNotClickedOn() {
        val target = box()
        targets.set.setVisible(target, false)

        editor.click(Point(110.0, 110.0))

        assertNull(editor.selected.value)
    }

    @Test
    fun aDragMovesTheTargetFromWhereTheDragBeganByThePointersWholeMovement() {
        val target = box()
        editor.select(target)

        assertTrue(editor.startDrag())
        editor.dragMove(10.0, 5.0)
        editor.dragMove(30.0, 20.0)
        editor.endDrag()

        assertBounds(Rect(130.0, 120.0, 100.0, 50.0), target)
    }

    @Test
    fun aCornerDragResizesTheTargetAndTheOppositeCornerStaysPut() {
        val target = box()
        editor.select(target)

        editor.startDrag()
        editor.dragResize(Corner.BOTTOM_RIGHT, 50.0, 10.0, keepAspect = false)
        editor.endDrag()
        assertBounds(Rect(100.0, 100.0, 150.0, 60.0), target)

        editor.startDrag()
        editor.dragResize(Corner.TOP_LEFT, -20.0, -10.0, keepAspect = false)
        editor.endDrag()
        assertBounds(Rect(80.0, 90.0, 170.0, 70.0), target)
    }

    @Test
    fun withCtrlHeldACornerDragKeepsTheTargetsShape() {
        val target = box()
        editor.select(target)

        editor.startDrag()
        // The width changed more: it leads, and the height follows
        editor.dragResize(Corner.BOTTOM_RIGHT, 100.0, 10.0, keepAspect = true)
        editor.endDrag()
        assertBounds(Rect(100.0, 100.0, 200.0, 100.0), target)

        editor.startDrag()
        editor.dragResize(Corner.TOP_LEFT, 10.0, 50.0, keepAspect = true)
        editor.endDrag()
        assertBounds(Rect(200.0, 150.0, 100.0, 50.0), target)
    }

    @Test
    fun aResizeStopsAtTheSmallestSize() {
        val target = box()
        editor.select(target)

        editor.startDrag()
        editor.dragResize(Corner.BOTTOM_RIGHT, -500.0, -500.0, keepAspect = false)
        editor.endDrag()

        assertBounds(Rect(100.0, 100.0, TargetEditor.MIN_SIZE, TargetEditor.MIN_SIZE), target)
    }

    @Test
    fun arrowsMoveTheSelectedTargetByOneAndShiftArrowsResizeItAboutItsCenter() {
        val target = box()
        editor.select(target)

        editor.nudge(Arrow.RIGHT, resize = false)
        editor.nudge(Arrow.DOWN, resize = false)
        assertBounds(Rect(101.0, 101.0, 100.0, 50.0), target)
        editor.nudge(Arrow.LEFT, resize = false)
        editor.nudge(Arrow.UP, resize = false)
        assertBounds(Rect(100.0, 100.0, 100.0, 50.0), target)

        // Right and Down grow, Left and Up shrink
        editor.nudge(Arrow.RIGHT, resize = true)
        assertBounds(Rect(99.5, 100.0, 101.0, 50.0), target)
        editor.nudge(Arrow.DOWN, resize = true)
        assertBounds(Rect(99.5, 99.5, 101.0, 51.0), target)
        editor.nudge(Arrow.LEFT, resize = true)
        editor.nudge(Arrow.UP, resize = true)
        assertBounds(Rect(100.0, 100.0, 100.0, 50.0), target)
    }

    @Test
    fun deleteRemovesTheSelectedTargetAndSelectsNothing() {
        val target = box()
        editor.select(target)

        editor.delete()

        assertFalse(targets.set.get(target).isPresent)
        assertNull(editor.selected.value)
    }

    @Test
    fun aSelectedTargetRemovedElsewhereIsNoLongerSelected() {
        val target = box()
        editor.select(target)

        targets.remove(target)

        assertNull(editor.selected.value)
    }

    @Test
    fun aDragKeepsAStripOfTheTargetOnTheSurfaceAtEveryEdge() {
        val target = box()
        editor.select(target)

        for ((move, expected) in listOf(
            Point(-1000.0, 0.0) to Rect(-90.0, 100.0, 100.0, 50.0),
            Point(1000.0, 0.0) to Rect(630.0, 100.0, 100.0, 50.0),
            Point(0.0, -1000.0) to Rect(100.0, -40.0, 100.0, 50.0),
            Point(0.0, 1000.0) to Rect(100.0, 470.0, 100.0, 50.0),
        )) {
            editor.startDrag()
            editor.dragMove(move.x, move.y)
            editor.endDrag()
            assertBounds(expected, target)
            editor.startDrag()
            editor.dragMove(100.0 - expected.minX, 100.0 - expected.minY)
            editor.endDrag()
        }
    }

    @Test
    fun aTargetSmallerThanTheStripStaysWhollyOnTheSurface() {
        val target = box(width = 6.0, height = 4.0)
        editor.select(target)

        editor.startDrag()
        editor.dragMove(-1000.0, 1000.0)
        editor.endDrag()

        assertBounds(Rect(0.0, 476.0, 6.0, 4.0), target)
    }

    @Test
    fun nudgesAndResizesKeepTheStripOnTheSurfaceToo() {
        val target = box(x = -90.0)
        editor.select(target)

        editor.nudge(Arrow.LEFT, resize = false)
        assertBounds(Rect(-90.0, 100.0, 100.0, 50.0), target)

        // Narrower from the right, it would leave only 5 units on: it moves back in
        editor.startDrag()
        editor.dragResize(Corner.BOTTOM_RIGHT, -5.0, 0.0, keepAspect = false)
        editor.endDrag()
        assertBounds(Rect(-85.0, 100.0, 95.0, 50.0), target)
    }

    @Test
    fun keepOnSaysHowFarBoundsMustMove() {
        val surface = Size(640.0, 480.0)
        assertEquals(0.0 to 0.0, keepOn(Rect(10.0, 10.0, 50.0, 50.0), surface))
        assertEquals(5.0 to 0.0, keepOn(Rect(-45.0, 10.0, 50.0, 50.0), surface))
        assertEquals(0.0 to -5.0, keepOn(Rect(10.0, 475.0, 50.0, 50.0), surface))
    }

    @Test
    fun aSelectedTargetAnExerciseHidesCantBeEditedUntilItShowsAgain() {
        val target = box()
        editor.select(target)
        val initial = bounds(target)

        targets.set.setVisible(target, false)

        editor.nudge(Arrow.RIGHT, resize = false)
        assertBounds(initial, target)

        assertFalse(editor.startDrag())
        editor.dragMove(50.0, 50.0)
        editor.endDrag()
        assertBounds(initial, target)

        assertFalse(editor.startDrag())
        editor.dragResize(Corner.BOTTOM_RIGHT, 100.0, 50.0, keepAspect = false)
        editor.endDrag()
        assertBounds(initial, target)

        editor.delete()
        assertTrue(targets.set.get(target).isPresent)

        targets.set.setVisible(target, true)
        editor.nudge(Arrow.RIGHT, resize = false)
        assertBounds(Rect(101.0, 100.0, 100.0, 50.0), target)
    }

    @Test
    fun aTargetWithoutAreaIsNeverGivenANonFiniteScale() {
        val target = box()
        editor.select(target)

        // Hide all regions by disabling the sole one (index 0)
        targets.set.setRegionVisible(target, 0, false)

        editor.nudge(Arrow.RIGHT, resize = true)
        val placement1 = targets.set.get(target).get().placement
        assertTrue(placement1.scaleX().isFinite() && placement1.scaleY().isFinite())
        assertBounds(bounds(target), target)

        editor.startDrag()
        editor.dragResize(Corner.BOTTOM_RIGHT, 50.0, 30.0, keepAspect = false)
        editor.endDrag()
        val placement2 = targets.set.get(target).get().placement
        assertTrue(placement2.scaleX().isFinite() && placement2.scaleY().isFinite())
        assertBounds(bounds(target), target)
    }

    @Test
    fun aCornerResizeOfAnAlreadyScaledTargetKeepsTheOppositeCornerStill() {
        val target = box()
        editor.select(target)

        // First resize to scale ~1.5
        editor.startDrag()
        editor.dragResize(Corner.BOTTOM_RIGHT, 50.0, 50.0, keepAspect = false)
        editor.endDrag()
        val firstResize = bounds(target)

        // Second resize from a different corner
        editor.startDrag()
        editor.dragResize(Corner.TOP_LEFT, 30.0, 20.0, keepAspect = false)
        editor.endDrag()
        val secondResize = bounds(target)

        // The opposite corner (BOTTOM_RIGHT from TOP_LEFT resize) should be unchanged
        assertEquals(firstResize.maxX, secondResize.maxX, 1e-6)
        assertEquals(firstResize.maxY, secondResize.maxY, 1e-6)

        // Test with keepAspect too
        editor.startDrag()
        editor.dragResize(Corner.TOP_LEFT, -20.0, -20.0, keepAspect = true)
        editor.endDrag()
        val withAspect = bounds(target)

        // Opposite corner should still be the same
        assertEquals(secondResize.maxX, withAspect.maxX, 1e-6)
        assertEquals(secondResize.maxY, withAspect.maxY, 1e-6)
        // And aspect should be preserved (width/height ratio should not change drastically)
        val expectedRatio = secondResize.width / secondResize.height
        val actualRatio = withAspect.width / withAspect.height
        assertEquals(expectedRatio, actualRatio, 1e-6)
    }
}
