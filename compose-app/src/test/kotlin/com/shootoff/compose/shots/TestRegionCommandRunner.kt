package com.shootoff.compose.shots

import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.RegionKey
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import com.shootoff.sound.SoundPlayer
import com.shootoff.targets.model.AlphaMask
import com.shootoff.targets.model.HitTester
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetDefinitions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Optional

class TestRegionCommandRunner {
    private val clock = ManualClock()
    private val targets = SurfaceTargets(clock = clock)
    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    private val played = mutableListOf<String>()
    private var resets = 0
    private var loader: ClassLoader? = null
    private val before = ScratchConfig.workingTreeFingerprint()

    private val sounds = object : RegionCommandRunner.Sounds {
        override fun play(file: String) {
            played += file
        }

        override fun play(stream: InputStream) {
            played += "stream:" + stream.use { String(it.readAllBytes()) }
        }
    }

    private val runner = RegionCommandRunner(targets, settings, { resets++ }, { loader }, sounds = sounds)

    @AfterEach
    fun theOwnersSettingsAreUntouched() {
        assertEquals(before, ScratchConfig.workingTreeFingerprint())
    }

    private fun shoot(point: Point) = shoot(point.x, point.y)

    // A point on the region where its first frame isn't transparent, so the shot hits it
    private fun opaquePoint(bounds: Rect, mask: AlphaMask): Point {
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            if (mask.isOpaque(x, y)) return Point(bounds.minX + x + 0.5, bounds.minY + y + 0.5)
        }
        throw AssertionError("The region is transparent")
    }

    private fun shoot(x: Double, y: Double) {
        val hit = HitTester.hit(targets.set, x, y).get()
        runner.run(ScaledShot(ShotColor.RED, x, y, 1000), hit, false)
    }

    @Test
    fun aPepperPopperFallsOnceAndRingsOnlyWhileStanding() {
        val popper = targets.add(TargetDefinitions.load(Paths.get("targets/Pepper_Popper.target")), ResourceResolver.files())
        val image = RegionKey(popper.id, 0)

        // Its hidden plate region names the popper image: animate(pepper_popper) and the steel sound
        shoot(266.0, 60.0)
        assertEquals(listOf("sounds/steel_sound_1.wav"), played)
        clock.runAll()
        assertFalse(targets.animations.isOnFirstFrame(image))

        // Fallen: the next hit neither animates it again nor rings
        played.clear()
        shoot(266.0, 60.0)
        assertEquals(emptyList<String>(), played)
    }

    @Test
    fun aDuelingTreePaddleSwingsAndIsReversedForItsNextHit() {
        val tree = targets.add(TargetDefinitions.load(Paths.get("targets/Duel_Tree.target")), ResourceResolver.files())
        val paddle = RegionKey(tree.id, 1)
        shoot(opaquePoint(tree.regionBounds(tree.definition.regions()[1]), targets.image(tree.id, 1)!!.masks[0]))
        clock.runAll()

        // animate, then reverse once the swing is done: it starts from its last frame next time
        assertTrue(targets.animations.isOnFirstFrame(paddle))
        assertTrue(targets.animations.frameOf(paddle) > 0)
        assertEquals(listOf("sounds/steel_sound_1.wav"), played)
    }

    @Test
    fun resetDoesWhatResetDoes() {
        targets.add(command("reset"), ResourceResolver.files())

        shoot(5.0, 5.0)

        assertEquals(1, resets)
    }

    @Test
    fun anAtSoundComesFromTheRunningExercisesJar(@TempDir jar: Path) {
        Files.createDirectories(jar.resolve("sounds"))
        Files.write(jar.resolve("sounds/cue.wav"), "cue".toByteArray())
        loader = URLClassLoader(arrayOf(jar.toUri().toURL()), null)
        targets.add(command("play_sound(@sounds/cue.wav)"), ResourceResolver.files())

        shoot(5.0, 5.0)

        assertEquals(listOf("stream:cue"), played)
    }

    @Test
    fun fivePoiHitsSetTheAdjustmentAndASixthTurnsItOff() {
        val wasSilenced = SoundPlayer.isSilenced()
        SoundPlayer.silence(true)
        try {
            targets.add(command("poi_adjust", x = 100.0, y = 100.0), ResourceResolver.files())

            // 5 right and 2 down of the region's center (110, 110)
            repeat(5) { shoot(115.0, 112.0) }
            assertTrue(settings.isAdjustingPOI)
            assertEquals(Optional.of(-5.0), settings.poiAdjustmentX)
            assertEquals(Optional.of(-2.0), settings.poiAdjustmentY)

            shoot(115.0, 112.0)
            assertFalse(settings.isAdjustingPOI)
        } finally {
            SoundPlayer.silence(wasSilenced)
        }
    }

    private fun command(command: String, x: Double = 0.0, y: Double = 0.0) = TargetDefinition(
        Optional.empty(),
        mapOf(),
        listOf(RectangleRegion(0, x, y, 20.0, 20.0, "red", mapOf("command" to command))),
    )
}
