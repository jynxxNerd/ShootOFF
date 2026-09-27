/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.compose.drill

import com.shootoff.camera.Shot
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.shots.Marker
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.config.Settings
import com.shootoff.exercise.ButtonHandle
import com.shootoff.exercise.Cancellable
import com.shootoff.exercise.DelayRange
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.exercise.ExercisePaths
import com.shootoff.exercise.RowStyle
import com.shootoff.exercise.ShotMarkerHandle
import com.shootoff.exercise.ShotStyle
import com.shootoff.exercise.TargetHandle
import com.shootoff.exercise.TextHandle
import com.shootoff.exercise.TextStyle
import com.shootoff.exercise.host.ExerciseHostSupport
import com.shootoff.exercise.host.ExerciseHostSupport.FileTarget
import com.shootoff.exercise.host.ExerciseHostSupport.JarTarget
import com.shootoff.exercise.host.ExerciseHostSupport.MissingTarget
import com.shootoff.exercise.host.SavedBackground
import com.shootoff.geom.Point
import com.shootoff.geom.Size
import com.shootoff.targets.model.Hit
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.PlacedTarget
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetDefinitions
import com.shootoff.targets.model.TargetFormatException
import com.shootoff.targets.model.TargetId
import com.shootoff.targets.model.TargetSetListener
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.time.Duration
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.function.DoubleConsumer

/**
 * What a [ComposeExerciseHost] works with.
 *
 * @param resources the exercise's class loader; for a plugin, its jar's
 * @param clearShots clears every camera's shots and the shot timer
 * @param setDetecting turns every camera's shot detection on or off
 * @param onFailure hears that one of the exercise's callbacks threw
 */
class HostContext(
    val settings: Settings,
    val surface: HostSurface,
    val timer: ShotTimerModel,
    val drill: DrillState,
    val resources: ClassLoader?,
    val sounds: SoundOutput,
    val clearShots: () -> Unit,
    val setDetecting: (Boolean) -> Unit,
    val onFailure: (ComposeExerciseHost, Throwable) -> Unit = { _, _ -> },
)

/**
 * Runs one v2 [Exercise] in the Compose app, on the projector arena or on a camera feed. What every
 * user interface's host shares (the exercise's thread, stopping, names, timer rows, the shared par time
 * and start delay) is plugin-api's [ExerciseHostSupport]; this puts the exercise's things into the
 * Compose app's state.
 * - Every exercise callback runs on the exercise's own thread. One that throws is reported to
 *   [HostContext.onFailure] (the app then stops the exercise and says why).
 * - Host methods may be called from any thread. They change the state at once, under one lock, in call
 *   order; Compose redraws from it. No host method waits for the UI thread.
 * - [stop] stops the exercise, cancels its scheduled tasks and removes everything it added. Calls the
 *   exercise makes afterwards change nothing.
 */
class ComposeExerciseHost(private val exercise: Exercise, private val context: HostContext) : ExerciseHost {
    private val logger = LoggerFactory.getLogger(ComposeExerciseHost::class.java)
    private val support = ExerciseHostSupport<TargetId>(exercise, context.resources)
    private val drill = context.drill
    private val targets = context.surface.targets
    private val nextId = AtomicLong()

    // Guarded by lock: what this host added to the shared state, removed on stop
    private val lock = Any()
    private var tornDown = false
    private val buttons = mutableListOf<Long>()
    private val settings = mutableListOf<Long>()
    private val texts = mutableListOf<Long>()
    private val markers = mutableListOf<Marker>()
    private val columns = mutableListOf<String>()
    private var showsTiming = false
    private var showsMessage = false
    private val savedBackground = SavedBackground<ArenaBackground>()

    // The exercise hears about targets joining and leaving its surface
    private val targetListener = object : TargetSetListener {
        override fun targetAdded(target: PlacedTarget) = targetsChanged()

        override fun targetRemoved(target: PlacedTarget) = targetsChanged()
    }

    val name: String get() = support.exerciseName()

    /** Whether the exercise has paused shot detection (a paused drill does) */
    val shotDetectionPaused: Boolean get() = support.isShotDetectionPaused

    // ---- Lifecycle, driven by the ExerciseRunner

    fun start() {
        drill.setName(name)
        targets.set.addListener(targetListener)
        support.run(guarded { exercise.start(this) })
    }

    /** Hands a shot to the exercise, in its surface's coordinates. */
    fun deliverShot(shot: Shot, hit: Hit?) = support.run(guarded { exercise.onShot(shot, Optional.ofNullable(hit)) })

    fun targetsChanged() = support.run(guarded { exercise.onTargetsChanged(targets()) })

    fun reset() = support.run(guarded { exercise.onReset() })

    /**
     * Stops the exercise and removes everything it added. Waits up to two seconds for the exercise's
     * thread, which never waits for the UI thread, so this may run on the UI thread.
     */
    fun stop() {
        val stopped = support.stop().orElse(null) ?: return
        targets.set.removeListener(targetListener)

        for (target in stopped.addedTargets()) targets.remove(target)
        if (stopped.restartDetection()) context.setDetecting(true)

        synchronized(lock) {
            tornDown = true
            buttons.forEach(drill::removeButton)
            settings.forEach(drill::removeSetting)
            texts.forEach(drill::removeText)
            drill.markers.removeAll(markers)
            context.timer.removeColumns(columns)
            if (showsTiming) drill.setTiming(null)
            if (showsMessage) drill.setMessage(null)
            drill.setName(null)
            savedBackground.restore { context.surface.setBackground(it.orElse(null)) }
        }
    }

    val isStopped: Boolean get() = support.isStopped

    // A callback of the exercise's, reporting a throw before the executor logs it
    private fun guarded(callback: () -> Unit) = Runnable {
        try {
            callback()
        } catch (e: RuntimeException) {
            context.onFailure(this, e)
            throw e
        } catch (e: Error) {
            context.onFailure(this, e)
            throw e
        }
    }

    // Changes the shared state unless the exercise has been torn down
    private inline fun ui(change: () -> Unit) {
        synchronized(lock) {
            if (!tornDown) change()
        }
    }

    // ---- The user, from the drill panel's timing controls

    private fun userChangedParTime(seconds: Double) {
        support.userChangedParTime(seconds)
        ui { drill.updateTiming { it.copy(parTime = seconds) } }
    }

    private fun userChangedDelayedStart(minSeconds: Int, maxSeconds: Int) {
        support.userChangedDelayedStart(minSeconds, maxSeconds)
        ui { drill.updateTiming { it.copy(delay = support.delayedStart()) } }
    }

    // ---- Surface

    override fun surfaceSize(): Size = context.surface.size()

    override fun isProjector(): Boolean = context.surface.isProjector

    override fun setBackground(imageResource: String) {
        if (!isProjector) {
            logger.warn("{} set a background, but only the projector arena has one", name)
            return
        }

        val resource = ExercisePaths.resourceName(imageResource)
        val background = try {
            val image = support.openImage(resource).orElse(null)
            if (image == null) {
                logger.error("Can't find background {}", imageResource)
                return
            }
            ArenaBackground.read(image, "/$resource")
        } catch (e: IOException) {
            logger.error("Can't read background {}", imageResource, e)
            return
        } ?: return

        ui {
            savedBackground.beforeChange { Optional.ofNullable(context.surface.background()) }
            context.surface.setBackground(background)
        }
    }

    // ---- Targets

    override fun addTarget(targetFile: String, x: Double, y: Double): Optional<TargetHandle> {
        if (support.isStopped) return Optional.empty()

        val (definition, resolver) = loadTarget(targetFile) ?: return Optional.empty()
        val target = targets.add(definition, resolver, Placement(x, y, 1.0, 1.0, true))
        context.surface.placeNewTarget(target)

        if (support.track(target.id)) return Optional.of(Handle(target))

        // Stopped while loading
        targets.remove(target.id)
        return Optional.empty()
    }

    private fun loadTarget(targetFile: String): Pair<TargetDefinition, ResourceResolver>? {
        val jarResolver = ResourceResolver.classLoader(context.resources)
        return try {
            when (val source = support.findTarget(targetFile)) {
                is JarTarget -> ExerciseHostSupport.open(source.url()).use { stream: InputStream ->
                    TargetDefinitions.load(stream, jarResolver).withFile(File("@" + source.name())) to jarResolver
                }
                is FileTarget -> TargetDefinitions.load(source.file().toPath()) to ResourceResolver.files()
                is MissingTarget -> {
                    logger.error("Can't find target {}", source.requested())
                    null
                }
            }
        } catch (e: TargetFormatException) {
            logger.error("Can't read target {}: {}", targetFile, e.message)
            null
        } catch (e: IOException) {
            logger.error("Can't read target {}", targetFile, e)
            null
        }
    }

    override fun targets(): List<TargetHandle> = targets.set.targets.map { Handle(it) }

    private inner class Handle(private val target: PlacedTarget) : TargetHandle {
        override fun id(): TargetId = target.id

        override fun move(x: Double, y: Double) = targets.set.move(target.id, x, y)

        override fun resize(width: Double, height: Double) = targets.set.resize(target.id, width, height)

        override fun setVisible(visible: Boolean) = targets.set.setVisible(target.id, visible)

        override fun remove() {
            support.untrack(target.id)
            targets.remove(target.id)
        }

        override fun position(): Point = target.position

        override fun size(): Size = target.size

        override fun definition(): TargetDefinition = target.definition

        override fun equals(other: Any?): Boolean = other is Handle && other.target.id == target.id

        override fun hashCode(): Int = target.id.hashCode()
    }

    // ---- Text

    override fun showText(text: String, x: Double, y: Double, style: TextStyle): TextHandle {
        val id = nextId.incrementAndGet()
        ui {
            drill.addText(DrillText(id, text, x, y, style))
            texts += id
        }

        return object : TextHandle {
            override fun setText(newText: String) = ui { drill.updateText(id) { it.copy(text = newText) } }

            override fun move(newX: Double, newY: Double) = ui { drill.updateText(id) { it.copy(x = newX, y = newY) } }

            override fun remove() = ui {
                drill.removeText(id)
                texts -= id
            }
        }
    }

    override fun showMessage(message: String) {
        if (support.isStopped) return

        if (context.settings.inDebugMode()) println(message)
        context.settings.sessionRecorder.ifPresent { it.recordExerciseFeedMessage(message) }

        ui {
            drill.setMessage(message)
            showsMessage = true
        }
    }

    // ---- Controls

    override fun addButton(label: String, onClick: Runnable): ButtonHandle {
        val id = nextId.incrementAndGet()
        ui {
            drill.addButton(DrillButton(id, label) { support.run(guarded { onClick.run() }) })
            buttons += id
        }

        return object : ButtonHandle {
            override fun setLabel(newLabel: String) = ui { drill.relabelButton(id, newLabel) }

            override fun remove() = ui {
                drill.removeButton(id)
                buttons -= id
            }
        }
    }

    override fun addNumberSetting(label: String, initial: Double, min: Double, max: Double, step: Double, onChange: DoubleConsumer) {
        val id = nextId.incrementAndGet()
        ui {
            drill.addSetting(
                NumberSetting(id, label, initial, min, max, step) { value ->
                    drill.setSettingValue(id, value)
                    support.run(guarded { onChange.accept(value) })
                },
            )
            settings += id
        }
    }

    // ---- Shot timer

    override fun addColumn(name: String) = ui {
        context.timer.addColumn(name)
        columns += name
    }

    override fun setColumnValue(name: String, value: String) = ui {
        if (!context.timer.setColumnValue(name, value)) logger.warn("{} set {} on an empty shot timer", this.name, name)
    }

    override fun styleLastRow(style: RowStyle) = ui { context.timer.styleLastRow(style.highlightColor()) }

    override fun addTimerRow(timeMillis: Long, style: RowStyle) = ui { context.timer.addTimerRow(timeMillis, style.highlightColor()) }

    // ---- Shots

    override fun showShotMarker(x: Double, y: Double, style: ShotStyle): ShotMarkerHandle {
        var marker: Marker? = null
        ui {
            marker = drill.markers.add(x, y, style.color(), context.settings.markerRadius).also { markers += it }
        }
        return ShotMarkerHandle {
            ui {
                marker?.let {
                    drill.markers.remove(it)
                    markers -= it
                }
            }
        }
    }

    override fun clearShots() {
        if (support.isStopped) return

        context.clearShots()
        ui {
            drill.markers.removeAll(markers)
            markers.clear()
        }
    }

    override fun pauseShotDetection(paused: Boolean) = support.pauseShotDetection(paused) { context.setDetecting(it) }

    // ---- Sound

    override fun playSound(resourceOrFile: String) = support.playSound(resourceOrFile) { name, sound, whenDone ->
        context.sounds.play(name, sound) { whenDone.run() }
    }

    override fun playSounds(resourcesOrFiles: List<String>) = support.playSounds(resourcesOrFiles) { name, sound, whenDone ->
        context.sounds.play(name, sound) { whenDone.run() }
    }

    override fun say(text: String) {
        if (!support.isStopped) context.sounds.say(text)
    }

    // ---- Time

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun schedule(task: Runnable, delay: Duration): Cancellable = support.schedule(guarded { task.run() }, delay)

    override fun scheduleRepeating(task: Runnable, initialDelay: Duration, period: Duration): Cancellable =
        support.scheduleRepeating(guarded { task.run() }, initialDelay, period)

    // ---- Resources

    override fun resource(path: String): Optional<InputStream> = support.resource(path)

    override fun dataDirectory(): Path = support.dataDirectory()

    // ---- Shared settings

    override fun parTime(): Double = support.parTime()

    override fun setParTime(seconds: Double) {
        support.setParTime(seconds)
        ui { drill.updateTiming { it.copy(parTime = seconds) } }
    }

    override fun onParTimeChanged(listener: DoubleConsumer) {
        support.onParTimeChanged { seconds -> guarded { listener.accept(seconds) }.run() }
        showTimingControls(withParTime = true)
    }

    override fun delayedStart(): DelayRange = support.delayedStart()

    override fun setDelayedStart(range: DelayRange) {
        support.setDelayedStart(range)
        ui { drill.updateTiming { it.copy(delay = range) } }
    }

    override fun onDelayedStartChanged(listener: Consumer<DelayRange>) {
        support.onDelayedStartChanged { range -> guarded { listener.accept(range) }.run() }
        showTimingControls(withParTime = false)
    }

    // The controls show the current values
    private fun showTimingControls(withParTime: Boolean) = ui {
        val current = drill.timing.value
        drill.setTiming(
            TimingControls(
                showsParTime = withParTime || (current?.showsParTime ?: false),
                parTime = support.parTime(),
                delay = support.delayedStart(),
                onParTime = ::userChangedParTime,
                onDelay = ::userChangedDelayedStart,
            ),
        )
        showsTiming = true
    }
}
