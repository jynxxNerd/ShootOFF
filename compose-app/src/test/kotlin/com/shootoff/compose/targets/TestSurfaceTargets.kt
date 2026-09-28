package com.shootoff.compose.targets

import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import com.shootoff.targets.model.ImageRegion
import com.shootoff.targets.model.PlacedTarget
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetDefinitions
import com.shootoff.targets.model.TargetSetListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.Optional
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class TestSurfaceTargets {
    private val targets = SurfaceTargets(clock = ManualClock())

    private fun box() = TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 10.0, 20.0, "red", mapOf())))

    @Test
    fun anImageTargetIsReadyToHitTestAsSoonAsItIsAdded() {
        val popper = targets.add(TargetDefinitions.load(Paths.get("targets/Pepper_Popper.target")), ResourceResolver.files())

        assertTrue(targets.image(popper.id, 0)!!.animated)
        assertTrue(targets.set.get(popper.id).get().getImageMask(0).isPresent)
        assertEquals(listOf(true, false, false), targets.drawn.value.single().regionVisible)
    }

    @Test
    fun theSnapshotFollowsEveryChangeToTheSet() {
        val target = targets.add(box(), ResourceResolver.files(), Placement(5.0, 6.0, 1.0, 1.0, true))
        assertEquals(Point(5.0, 6.0), targets.drawn.value.single().origin)

        targets.set.move(target.id, 50.0, 60.0)
        assertEquals(Point(50.0, 60.0), targets.drawn.value.single().origin)

        targets.set.setVisible(target.id, false)
        assertFalse(targets.drawn.value.single().placement.visible())

        targets.set.setRegionVisible(target.id, 0, false)
        assertEquals(listOf(false), targets.drawn.value.single().regionVisible)

        targets.remove(target.id)
        assertEquals(emptyList<DrawnTarget>(), targets.drawn.value)
    }

    @Test
    fun aTargetIsTheShootersUnlessAnExerciseAddedIt() {
        val shooters = targets.add(box(), ResourceResolver.files())
        val exercises = targets.add(box(), ResourceResolver.files(), Placement(50.0, 0.0, 1.0, 1.0, true), TargetOwner.EXERCISE)

        assertEquals(TargetOwner.USER, targets.owner(shooters.id))
        assertEquals(TargetOwner.EXERCISE, targets.owner(exercises.id))
        assertEquals(listOf(TargetOwner.USER, TargetOwner.EXERCISE), targets.drawn.value.map { it.owner })
        assertEquals(listOf(shooters.id), targets.targetsOf(TargetOwner.USER).map { it.id })
        assertEquals(listOf(exercises.id), targets.targetsOf(TargetOwner.EXERCISE).map { it.id })

        targets.remove(exercises.id)
        assertNull(targets.owner(exercises.id))
    }

    // The layout's memory hears of each new target and must know whether it is the shooter's
    @Test
    fun theSetsListenersKnowANewTargetsOwnerAsTheyHearOfIt() {
        val heard = mutableListOf<TargetOwner?>()
        targets.set.addListener(object : TargetSetListener {
            override fun targetAdded(target: PlacedTarget) {
                heard += targets.owner(target.id)
            }

            override fun targetRemoved(target: PlacedTarget) {}

            override fun targetChanged(target: PlacedTarget) {}
        })

        targets.add(box(), ResourceResolver.files())
        targets.add(box(), ResourceResolver.files(), owner = TargetOwner.EXERCISE)

        assertEquals(listOf(TargetOwner.USER, TargetOwner.EXERCISE), heard)
    }

    @Test
    fun theSnapshotCarriesEachTargetsBoundsOnTheSurface() {
        val target = targets.add(box(), ResourceResolver.files(), Placement(5.0, 6.0, 2.0, 1.0, true))

        // Scaled about its center (5, 10): 20 wide from x = 0
        assertEquals(Rect(0.0, 6.0, 20.0, 20.0), targets.drawn.value.single().bounds)
        assertEquals(target.bounds, targets.drawn.value.single().bounds)
    }

    @Test
    fun anImageThatCantBeReadIsLeftOutButTheTargetStays() {
        val broken = TargetDefinition(Optional.empty(), mapOf(), listOf(ImageRegion(0, 0.0, 0.0, "targets/no_such.png", 10, 10, mapOf())))

        val target = targets.add(broken, ResourceResolver.files())

        assertNull(targets.image(target.id, 0))
        assertEquals(1, targets.drawn.value.size)
    }

    @Test
    fun theLastPublishedPlacementIsAlwaysTheLatestOne() {
        // Eight threads racing to move the same target, over and over: after each burst, the published
        // snapshot must match the target's actual current placement, never an older one left behind by a
        // publish that took longer to compute (and so finished later) than one that started after it.
        val fresh = SurfaceTargets(clock = ManualClock())
        val target = fresh.add(box(), ResourceResolver.files())
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(3000) { round ->
                val tasks = (0 until 8).map { n ->
                    Callable {
                        for (i in 0 until 10) fresh.set.move(target.id, (round * 10_000 + n * 100 + i).toDouble(), 0.0)
                    }
                }
                pool.invokeAll(tasks)

                val expected = fresh.set.get(target.id).get().localToParent(0.0, 0.0)
                assertEquals(expected, fresh.drawn.value.single().origin, "round $round")
            }
        } finally {
            pool.shutdown()
        }
    }
}
