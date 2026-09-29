package com.shootoff.compose.drill

import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.compose.shots.ArenaPointShot
import com.shootoff.compose.shots.RegionCommandRunner
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.exercise.ExerciseHostContract
import com.shootoff.exercise.ExerciseHostContract.TimerRowView
import com.shootoff.exercise.TargetHandle
import com.shootoff.geom.Size
import java.io.InputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [ComposeExerciseHost] on a camera feed, or on the projector arena (the one arena model that the
 * projector window and the in-app Arena view both draw), for [ExerciseHostContract].
 */
class ComposeHostHarness private constructor(
    exercise: Exercise,
    resources: ClassLoader,
    private val projector: Boolean,
) : ExerciseHostContract.Harness {
    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    private val played = CopyOnWriteArrayList<String>()
    val timer = ShotTimerModel()
    val drill = DrillState()
    private lateinit var arena: ArenaModel
    private val surface: HostSurface
    private val host: ComposeExerciseHost

    init {
        System.setProperty("shootoff.home", System.getProperty("user.dir"))
        surface = if (projector) {
            arena = ArenaModel(settings, { ShotReceiver.None }, { RegionCommandRunner(arena.targets, settings, {}, { null }) }, ManualClock())
            arena.setSize(Size(1280.0, 720.0))
            ArenaHostSurface(arena)
        } else {
            FeedHostSurface(SurfaceTargets(clock = ManualClock()), Size(640.0, 480.0))
        }

        host = ComposeExerciseHost(
            exercise,
            HostContext(
                settings,
                surface,
                timer,
                drill,
                resources,
                object : SoundOutput {
                    override fun play(name: String, sound: InputStream, whenDone: () -> Unit) {
                        sound.close()
                        played += name
                        whenDone()
                    }

                    override fun say(text: String) {}
                },
                clearShots = { timer.clear() },
                setDetecting = {},
            ),
        )
    }

    companion object {
        fun onCameraFeed(exercise: Exercise, jarEntries: Map<String, ByteArray>, temp: Path) =
            ComposeHostHarness(exercise, jar(jarEntries, temp), projector = false)

        fun onArena(exercise: Exercise, jarEntries: Map<String, ByteArray>, temp: Path) =
            ComposeHostHarness(exercise, jar(jarEntries, temp), projector = true)

        // The exercise's jar, as a folder
        private fun jar(entries: Map<String, ByteArray>, temp: Path): ClassLoader {
            val jar = Files.createDirectories(temp.resolve("jar"))
            for ((name, bytes) in entries) {
                val file = jar.resolve(name)
                Files.createDirectories(file.parent)
                Files.write(file, bytes)
            }
            return URLClassLoader(arrayOf(jar.toUri().toURL()), null)
        }
    }

    override fun host(): ExerciseHost = host

    override fun start() = host.start()

    override fun shoot(x: Double, y: Double) {
        val shot = ScaledShot(ShotColor.RED, x, y, System.currentTimeMillis())
        // A projector exercise takes shots in arena coordinates
        host.deliverShot(if (projector) ArenaPointShot(shot, x, y) else shot, null)
    }

    override fun reset() = host.reset()

    override fun stop() = host.stop()

    // Host calls change the state at once; nothing is queued for the UI
    override fun awaitUi() {}

    override fun click(label: String) {
        val button = drill.buttons.value.firstOrNull { it.label == label } ?: throw AssertionError("No button $label")
        button.onClick()
    }

    override fun changeSetting(label: String, value: Double) = setting<NumberSetting>(label).onChange(value)

    override fun changeYesNoSetting(label: String, value: Boolean) = setting<YesNoSetting>(label).onChange(value)

    override fun chooseSetting(label: String, choice: String) = setting<ChoiceSetting>(label).onChange(choice)

    private inline fun <reified S : DrillSetting> setting(label: String): S =
        drill.settings.value.filterIsInstance<S>().firstOrNull { it.label == label } ?: throw AssertionError("No setting $label")

    override fun userSetsParTime(seconds: Double) {
        val timing = drill.timing.value ?: throw AssertionError("No timing controls")
        if (!timing.showsParTime) throw AssertionError("No par time control")
        timing.onParTime(seconds)
    }

    override fun userSetsDelayedStart(minSeconds: Int, maxSeconds: Int) {
        val timing = drill.timing.value ?: throw AssertionError("No delay controls")
        timing.onDelay(minSeconds, maxSeconds)
    }

    override fun shownState(): Any = listOf(
        surface.targets.drawn.value.map { it.id to it.placement },
        drill.name.value,
        drill.buttons.value.map { it.label },
        drill.settings.value.map {
            it.label to when (it) {
                is NumberSetting -> it.value
                is YesNoSetting -> it.value
                is ChoiceSetting -> it.value
            }
        },
        drill.texts.value,
        drill.timing.value?.let { Triple(it.showsParTime, it.parTime, it.delay) },
        drill.message.value,
        drill.markers.markers.value,
        timer.columns.value,
        surface.background()?.name,
    )

    override fun addShotRow(timeMillis: Long) = timer.appendShotRow(ScaledShot(ShotColor.RED, 1.0, 2.0, timeMillis), false, false)

    override fun timerRows(column: String): List<TimerRowView> = timer.rows.value.map {
        TimerRowView(it.row.time(), it.row.split(), it.row.laser(), it.values[column] ?: "", it.highlight != null)
    }

    // Every view of the surface draws its one model: on the arena, the projector window and the Arena view
    override fun visibilityOnEachView(target: TargetHandle): List<Boolean> {
        val drawn = surface.targets.drawn.value.firstOrNull { it.id == target.id() } ?: return emptyList()
        return List(viewCount()) { drawn.placement.visible() }
    }

    override fun viewCount(): Int = if (projector) 2 else 1

    override fun playedSounds(): List<String> = played.toList()

    override fun close() = host.stop()
}
