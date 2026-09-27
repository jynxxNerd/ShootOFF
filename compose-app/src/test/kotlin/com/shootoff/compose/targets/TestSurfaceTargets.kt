package com.shootoff.compose.targets

import com.shootoff.geom.Point
import com.shootoff.targets.model.ImageRegion
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.Optional

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
    fun anImageThatCantBeReadIsLeftOutButTheTargetStays() {
        val broken = TargetDefinition(Optional.empty(), mapOf(), listOf(ImageRegion(0, 0.0, 0.0, "targets/no_such.png", 10, 10, mapOf())))

        val target = targets.add(broken, ResourceResolver.files())

        assertNull(targets.image(target.id, 0))
        assertEquals(1, targets.drawn.value.size)
    }
}
