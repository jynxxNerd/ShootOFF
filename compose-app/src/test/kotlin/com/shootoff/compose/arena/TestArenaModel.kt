package com.shootoff.compose.arena

import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.shots.FeedSurface
import com.shootoff.compose.shots.RegionCommandRunner
import com.shootoff.compose.shots.ShotMarkers
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class TestArenaModel {
    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    private lateinit var arena: ArenaModel

    private fun arena(): ArenaModel {
        arena = ArenaModel(settings, { ShotReceiver.None }, { RegionCommandRunner(arena.targets, settings, {}, { null }) }, ManualClock())
        return arena
    }

    private fun box(tags: Map<String, String> = mapOf()) =
        TargetDefinition(Optional.empty(), tags, listOf(RectangleRegion(0, 0.0, 0.0, 100.0, 50.0, "red", mapOf())))

    @Test
    fun theCalibrationPatternAndTheWhiteScreenAreShootoffsOwnImages() {
        val arena = arena()

        arena.showResource("pattern.png")
        assertEquals("pattern.png", arena.background.value!!.name)
        assertTrue(arena.background.value!!.image.width > 0)

        arena.showResource("white.png")
        assertEquals("white.png", arena.background.value!!.name)

        // An image ShootOFF doesn't have leaves the background as it was
        arena.showResource("no_such.png")
        assertEquals("white.png", arena.background.value!!.name)

        arena.showResource(null)
        assertNull(arena.background.value)
    }

    @Test
    fun calibrationHidesAndShowsEveryTarget() {
        val arena = arena()
        val first = arena.targets.add(box(), ResourceResolver.files())
        val second = arena.targets.add(box(), ResourceResolver.files(), Placement(200.0, 0.0, 1.0, 1.0, false))

        arena.setTargetsVisible(false)
        assertFalse(arena.targets.set.get(first.id).get().isVisible)

        arena.setTargetsVisible(true)
        assertTrue(arena.targets.set.get(first.id).get().isVisible)
        assertTrue(arena.targets.set.get(second.id).get().isVisible)
    }

    @Test
    fun aTargetThatFillsTheCanvasIsStretchedOverTheArena() {
        val arena = arena()
        arena.setSize(Size(1280.0, 720.0))
        val poi = arena.targets.add(box(mapOf("fillCanvas" to "true")), ResourceResolver.files())

        arena.placeNewTarget(poi)

        val placed = arena.targets.set.get(poi.id).get()
        assertEquals(1280.0, placed.size.width, 0.01)
        assertEquals(720.0, placed.size.height, 0.01)
    }

    @Test
    fun aShotAfterTheArenaChangedSizeIsScaledToItsNewSize() {
        val arena = arena()
        val feed = FeedSurface(
            "Default", settings, SurfaceTargets(clock = ManualClock()), ShotTimerModel(), ShotMarkers(),
            { ShotReceiver.None }, { RegionCommandRunner(arena.targets, settings, {}, { null }) },
            { arena.surface }, { Rect(100.0, 100.0, 320.0, 180.0) },
        )
        arena.setSize(Size(1280.0, 720.0))

        // The arena leaves full screen: 640x360 now
        arena.setSize(Size(640.0, 360.0))
        feed.add(ScaledShot(ShotColor.RED, 260.0, 190.0, 1000))

        assertEquals(listOf(320.0 to 180.0), arena.markers.markers.value.map { it.x to it.y })
    }

    @Test
    fun arenaMarkersFollowTheShowMarkersSetting() {
        settings.setShowArenaShotMarkers(false)

        assertFalse(arena().markers.visible.value)
    }
}
