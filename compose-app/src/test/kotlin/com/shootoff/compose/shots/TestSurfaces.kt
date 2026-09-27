package com.shootoff.compose.shots

import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.targets.RegionKey
import com.shootoff.geom.Rect
import com.shootoff.shots.ShotQueue
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TestSurfaces {
    private val fixture = SurfaceFixture()
    private val feed = fixture.feed
    private val arena = fixture.arena

    private fun box(x: Double, y: Double) = TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 40.0, 40.0, "red", mapOf())))
        .let { it to Placement(x, y, 1.0, 1.0, true) }

    @Test
    fun aShotGetsARowAndAMarkerThenReachesTheExercise() {
        feed.add(ScaledShot(ShotColor.RED, 100.0, 120.0, 1000))

        assertEquals(1, feed.timer.rows.value.size)
        assertEquals(listOf(100.0 to 120.0), feed.markers.markers.value.map { it.x to it.y })
        val delivered = fixture.delivered.single()
        assertNull(delivered.hit)
        assertFalse(delivered.arenaShot)
    }

    @Test
    fun aShotOnAFeedTargetHitsIt() {
        val (definition, placement) = box(50.0, 50.0)
        val target = feed.targets.add(definition, ResourceResolver.files(), placement)

        feed.add(ScaledShot(ShotColor.RED, 60.0, 60.0, 1000))

        assertEquals(target.id, fixture.delivered.single().hit!!.targetId())
    }

    @Test
    fun aShotInsideTheProjectionGoesToTheArenaInArenaCoordinates() {
        fixture.projection = Rect(100.0, 100.0, 320.0, 180.0)
        val (definition, placement) = box(620.0, 340.0)
        val target = arena.targets.add(definition, ResourceResolver.files(), placement)

        feed.add(ScaledShot(ShotColor.RED, 260.0, 190.0, 1000))

        val delivered = fixture.delivered.single()
        assertTrue(delivered.arenaShot)
        assertEquals(640.0, delivered.shot.x)
        assertEquals(360.0, delivered.shot.y)
        assertEquals(target.id, delivered.hit!!.targetId())
        // The feed made the row and drew its marker; the arena drew its own
        assertEquals(1, feed.timer.rows.value.size)
        assertEquals(1, feed.markers.markers.value.size)
        assertEquals(listOf(640.0 to 360.0), arena.markers.markers.value.map { it.x to it.y })
    }

    @Test
    fun withoutACalibrationOrAnArenaShotsStayOnTheFeed() {
        feed.add(ScaledShot(ShotColor.RED, 260.0, 190.0, 1000))
        fixture.arenaOpen = false
        fixture.projection = Rect(100.0, 100.0, 320.0, 180.0)
        feed.add(ScaledShot(ShotColor.RED, 260.0, 190.0, 2000))

        assertEquals(listOf(false, false), fixture.delivered.map { it.arenaShot })
        assertEquals(emptyList<Marker>(), arena.markers.markers.value)
    }

    @Test
    fun twoShotsFromOneFrameMakeTwoRowsInDetectionOrder() {
        val done = CountDownLatch(2)
        for (millis in listOf(1000L, 1004L)) {
            ShotQueue.shared().submit {
                feed.add(ScaledShot(ShotColor.RED, 10.0, 10.0, millis))
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals(listOf(1000L, 1004L), feed.timer.rows.value.map { it.shot.timestamp })
    }

    @Test
    fun clearingClearsTheTimerAndBothSurfacesMarkersAndResetStandsTargetsBackUp() {
        fixture.projection = Rect(100.0, 100.0, 320.0, 180.0)
        feed.add(ScaledShot(ShotColor.RED, 260.0, 190.0, 1000))
        val popper = arena.targets.add(TargetDefinitions.load(Paths.get("targets/Pepper_Popper.target")), ResourceResolver.files())
        arena.targets.animations.play(RegionKey(popper.id, 0))
        fixture.clock.runAll()

        feed.reset()

        assertEquals(emptyList<RowView>(), feed.timer.rows.value)
        assertEquals(emptyList<Marker>(), feed.markers.markers.value)
        assertEquals(emptyList<Marker>(), arena.markers.markers.value)
        assertEquals(0, arena.targets.animations.frameOf(RegionKey(popper.id, 0)))
    }
}
