package com.shootoff.compose.drill

import androidx.compose.ui.graphics.ImageBitmap
import com.shootoff.camera.Shot
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.compose.shots.ArenaPointShot
import com.shootoff.compose.shots.RegionCommandRunner
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.geom.Size
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.InputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class TestExerciseRunner {
    companion object {
        val heard = CopyOnWriteArrayList<String>()
        var resources: ClassLoader? = null
    }

    /** A projector drill that writes down its shots and throws on "boom" */
    class ArenaDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Arena drill", "1.0", "ShootOFF tests", "On the arena", true)

        override fun start(host: ExerciseHost) {
            heard += "start"
            host.addButton("Pause") {}
            host.setBackground("@backgrounds/black.png")
        }

        override fun onShot(shot: Shot, hit: Optional<Hit>) {
            if (shot.x == 13.0) throw IllegalStateException("boom")
            heard += "shot ${shot.x}"
        }

        override fun onReset() {}

        override fun stop() {
            heard += "stop"
        }
    }

    /** A projector drill that puts a target of its own on the arena */
    class TargetDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Target drill", "1.0", "ShootOFF tests", "Adds a target", true)

        override fun start(host: ExerciseHost) {
            host.addTarget("box.target", 300.0, 200.0)
            heard += "start"
        }

        override fun onShot(shot: Shot, hit: Optional<Hit>) {}

        override fun onReset() {}

        override fun stop() {}
    }

    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    private val drill = DrillState()
    private val timer = ShotTimerModel()
    private lateinit var arena: ArenaModel
    private val entry = V2ExerciseEntry(ArenaDrill::class.java, ArenaDrill().metadata())
    private var arenaOpen = true

    private val runner = ExerciseRunner { _, exercise, runner ->
        if (!arenaOpen) {
            null
        } else {
            ComposeExerciseHost(
                exercise,
                HostContext(settings, ArenaHostSurface(arena), timer, drill, resources, silent, {}, {}, runner::failed),
            )
        }
    }

    private val silent = object : SoundOutput {
        override fun play(name: String, sound: InputStream, whenDone: () -> Unit) = whenDone()

        override fun say(text: String) {}
    }

    init {
        heard.clear()
        arena = ArenaModel(settings, { ShotReceiver.None }, { RegionCommandRunner(arena.targets, settings, {}, { null }) }, ManualClock())
        arena.setSize(Size(1280.0, 720.0))
    }

    @AfterEach
    fun stop() = runner.stop()

    private fun jar(temp: Path) {
        Files.createDirectories(temp.resolve("backgrounds"))
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", temp.resolve("backgrounds/black.png").toFile())
        resources = URLClassLoader(arrayOf(temp.toUri().toURL()), null)
    }

    private fun awaitHeard(event: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (event !in heard) {
            if (System.nanoTime() > deadline) throw AssertionError("never heard $event in $heard")
            Thread.sleep(5)
        }
    }

    @Test
    fun onlyArenaShotsReachAProjectorDrill(@TempDir temp: Path) {
        jar(temp)
        assertTrue(runner.start(entry))
        awaitHeard("start")

        runner.deliver(ScaledShot(ShotColor.RED, 1.0, 1.0, 0), null, false)
        runner.deliver(ArenaPointShot(ScaledShot(ShotColor.RED, 1.0, 1.0, 0), 2.0, 2.0), null, true)
        awaitHeard("shot 2.0")

        assertTrue("shot 1.0" !in heard)
    }

    @Test
    fun aDrillThatThrowsIsStoppedAndTheUserToldWhy(@TempDir temp: Path) {
        jar(temp)
        runner.start(entry)
        awaitHeard("start")

        runner.deliver(ArenaPointShot(ScaledShot(ShotColor.RED, 1.0, 1.0, 0), 13.0, 13.0), null, true)
        awaitHeard("stop")

        assertEquals("Arena drill stopped: IllegalStateException: boom", runner.failure.value)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (runner.running.value != null && System.nanoTime() < deadline) Thread.sleep(5)
        assertNull(runner.running.value)
        assertEquals(emptyList<DrillButton>(), drill.buttons.value)
    }

    @Test
    fun calibrationStopsAProjectorDrillAndStartsItAfresh(@TempDir temp: Path) {
        jar(temp)
        runner.start(entry)
        val first = runner.running.value!!.host

        val restart = runner.stopProjectorExercise()
        assertNull(runner.running.value)
        restart.get().run()

        assertNotSame(first, runner.running.value!!.host)
        assertSame(entry, runner.running.value!!.entry)
    }

    @Test
    fun startingTheDrillAgainStopsTheRunningOneFirst(@TempDir temp: Path) {
        jar(temp)
        runner.start(entry)
        val first = runner.running.value!!.host
        awaitHeard("start")

        runner.start(entry)

        assertTrue(first.isStopped)
        assertEquals(1, heard.count { it == "stop" })
        // One drill's worth of buttons: the first drill's are gone
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (drill.buttons.value.size != 1 && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(listOf("Pause"), drill.buttons.value.map { it.label })
    }

    // Spec §5: what an exercise adds is its own, and goes when it stops; the shooter's targets stay
    @Test
    fun aDrillsTargetsAreTheExercisesAndGoWhenItStopsWhileTheShootersStay(@TempDir temp: Path) {
        Files.writeString(temp.resolve("box.target"), "<target><rectangle x=\"0\" y=\"0\" width=\"10\" height=\"10\" fill=\"red\"/></target>")
        resources = URLClassLoader(arrayOf(temp.toUri().toURL()), null)
        val shooters = arena.targets.add(
            TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 10.0, 10.0, "red", mapOf()))),
            ResourceResolver.files(),
        )

        runner.start(V2ExerciseEntry(TargetDrill::class.java, TargetDrill().metadata()))
        awaitHeard("start")
        val added = arena.targets.set.targets.single { it.id != shooters.id }
        assertEquals(TargetOwner.EXERCISE, arena.targets.owner(added.id))

        runner.stop()

        assertEquals(listOf(shooters.id), arena.targets.set.targets.map { it.id })
        assertEquals(TargetOwner.USER, arena.targets.owner(shooters.id))
    }

    @Test
    fun withoutTheArenaAProjectorDrillDoesntStart() {
        arenaOpen = false

        assertEquals(false, runner.start(entry))
        assertNull(runner.running.value)
    }

    @Test
    fun theArenasBackgroundComesBackWhenTheDrillStops(@TempDir temp: Path) {
        jar(temp)
        val before = ArenaBackground(ImageBitmap(2, 2), "indoor_range.gif")
        arena.setBackground(before)
        runner.start(entry)
        awaitHeard("start")
        assertEquals("/backgrounds/black.png", arena.background.value!!.name)

        runner.stop()

        assertSame(before, arena.background.value)
    }
}
