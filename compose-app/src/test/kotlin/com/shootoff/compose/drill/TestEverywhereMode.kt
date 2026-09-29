package com.shootoff.compose.drill

import com.shootoff.camera.Shot
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.shots.ArenaPointShot
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.shots.SurfaceFixture
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.exercise.TargetHandle
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Exercise port spec §5: a camera exercise runs everywhere, on the camera feed and on the arena's targets. */
class TestEverywhereMode {
    companion object {
        val heard = CopyOnWriteArrayList<String>()

        @Volatile
        var host: ExerciseHost? = null
    }

    /** A camera drill that writes down its shots and the targets it hears about */
    class CameraDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Camera drill", "1.0", "ShootOFF tests", "On the camera feed")

        override fun start(host: ExerciseHost) {
            TestEverywhereMode.host = host
            heard += "start"
        }

        override fun onShot(shot: Shot, hit: Optional<Hit>) {
            heard += "shot ${shot.x},${shot.y} on ${hit.map { it.targetId() }.orElse(null)}"
        }

        override fun onTargetsChanged(targets: List<TargetHandle>) {
            heard += "targets ${targets.size}"
        }

        override fun onReset() {}

        override fun stop() {}
    }

    private val entry = V2ExerciseEntry(CameraDrill::class.java, CameraDrill().metadata())
    private val fixture = SurfaceFixture { shot, hit, arenaShot -> runner.deliver(shot, hit, arenaShot) }
    private val drill = DrillState()
    private var everywhere = true

    private val silent = object : SoundOutput {
        override fun play(name: String, sound: InputStream, whenDone: () -> Unit) = whenDone()

        override fun say(text: String) {}
    }

    private val runner: ExerciseRunner = ExerciseRunner { _, exercise, runner ->
        val surface = FeedHostSurface(fixture.feed.targets, Size(640.0, 480.0), if (everywhere) fixture.arena.targets else null)
        ComposeExerciseHost(exercise, HostContext(fixture.settings, surface, ShotTimerModel(), drill, null, silent, {}, {}, runner::failed))
    }

    init {
        heard.clear()
        host = null
    }

    @AfterEach
    fun stop() = runner.stop()

    private fun box() = TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 40.0, 40.0, "red", mapOf())))

    private fun awaitHeard(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (heard.size < count) {
            if (System.nanoTime() > deadline) throw AssertionError("heard only $heard")
            Thread.sleep(5)
        }
        // Nothing more is on its way
        Thread.sleep(100)
    }

    private fun start() {
        assertTrue(runner.start(entry))
        awaitHeard(1)
    }

    @Test
    fun itSeesTheFeedsTargetsThenTheArenasAndHearsAboutChangesOnEither() {
        val onFeed = fixture.feed.targets.add(box(), ResourceResolver.files(), Placement(50.0, 50.0, 1.0, 1.0, true))
        val onArena = fixture.arena.targets.add(box(), ResourceResolver.files(), Placement(620.0, 340.0, 1.0, 1.0, true))
        start()

        assertEquals(listOf(onFeed.id, onArena.id), host!!.targets().map { it.id() })

        // The exercise reads the targets when it hears of the change, on its own thread
        fixture.arena.targets.add(box(), ResourceResolver.files(), Placement(10.0, 10.0, 1.0, 1.0, true))
        awaitHeard(2)
        fixture.feed.targets.remove(onFeed.id)
        awaitHeard(3)

        assertEquals(listOf("start", "targets 3", "targets 2"), heard)
    }

    @Test
    fun aCameraShotAndAnArenaShotEachReachItOnce() {
        fixture.projection = Rect(100.0, 100.0, 320.0, 180.0)
        val onFeed = fixture.feed.targets.add(box(), ResourceResolver.files(), Placement(50.0, 50.0, 1.0, 1.0, true))
        val onArena = fixture.arena.targets.add(box(), ResourceResolver.files(), Placement(620.0, 340.0, 1.0, 1.0, true))
        start()

        // Beside the projection on the camera target, then inside it, where the pipeline passes it on to the arena
        fixture.feed.add(ScaledShot(ShotColor.RED, 60.0, 60.0, 1000))
        fixture.feed.add(ScaledShot(ShotColor.RED, 260.0, 190.0, 2000))
        awaitHeard(3)

        assertEquals(listOf("start", "shot 60.0,60.0 on ${onFeed.id}", "shot 640.0,360.0 on ${onArena.id}"), heard)
    }

    @Test
    fun itsOwnTargetsGoOnTheCameraFeed() {
        start()

        val added = host!!.addTarget("IPSC.target", 5.0, 5.0).get()

        assertEquals(listOf(added.id()), fixture.feed.targets.set.targets.map { it.id })
        assertEquals(TargetOwner.EXERCISE, fixture.feed.targets.owner(added.id()))
        assertEquals(emptyList<Any>(), fixture.arena.targets.set.targets)
    }

    @Test
    fun withoutTheArenaACameraDrillTakesCameraShotsOnly() {
        everywhere = false
        start()

        runner.deliver(ArenaPointShot(ScaledShot(ShotColor.RED, 1.0, 1.0, 0), 2.0, 2.0), null, true)
        runner.deliver(ScaledShot(ShotColor.RED, 3.0, 3.0, 0), null, false)
        awaitHeard(2)

        assertEquals(listOf("start", "shot 3.0,3.0 on null"), heard)
    }
}
