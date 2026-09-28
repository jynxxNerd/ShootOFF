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

package com.shootoff.compose.app

import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.arena.ArenaLayout
import com.shootoff.compose.courses.CourseLoader
import com.shootoff.compose.feed.Banner
import com.shootoff.compose.feed.BannerKind
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.compose.targets.TargetEditor
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.courses.CourseBackground
import com.shootoff.courses.io.CourseIO
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetDefinitions
import com.shootoff.targets.model.TargetFormatException
import com.shootoff.targets.model.TargetSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** What the Targets screen says while calibration's pattern covers the arena (spec §6) */
const val CALIBRATING_NOTE = "Calibrating — the projector shows the pattern; your edits appear when it's done."

/** The surface the Targets screen edits */
enum class EditedSurface(val label: String) {
    ARENA("Arena"),
    CAMERA("Camera"),
}

/** What Clear took off a surface, for its Undo */
class Cleared(val surface: EditedSurface, val targets: List<Pair<TargetDefinition, Placement>>, val background: ArenaBackground?)

/** How a Save course went, as far as the screen needs to know at once (the file is written in the background) */
enum class SaveOutcome {
    /** Being written; the screen hears how it went in [TargetsModel.message] */
    SAVING,

    /** A course of that name exists: the screen asks before replacing it */
    EXISTS,

    /** The name is empty, or has a folder separator in it */
    BAD_NAME,
}

/**
 * The Targets screen's state and actions, apart from its composables (spec §4 and §5): which surface it
 * edits, each surface's [TargetEditor], and the toolbar's actions. Only the shooter's targets
 * ([TargetOwner.USER]) are added, cleared, loaded or saved; a running exercise's are left alone.
 *
 * The actions are called on the UI thread and run one at a time, in the order they were asked for (a Clear
 * pressed while a course loads takes the loaded targets off); their work, including reading files and
 * images, happens on [io].
 *
 * @param layout the shooter's arena, open or not
 * @param feedTargets the camera feed's targets, kept for the session only
 * @param feedSize the camera feed's canvas size
 * @param picker asks for an image file for the background
 */
class TargetsModel(
    val layout: ArenaLayout,
    val feedTargets: SurfaceTargets,
    private val feedSize: Size,
    val files: ArenaFiles,
    private val picker: ImagePicker,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    val loader = CourseLoader(files.home)
    val arenaEditor = TargetEditor(layout.targets) { layout.size.value }
    val cameraEditor = TargetEditor(feedTargets) { feedSize }

    private val surfaceState = MutableStateFlow(EditedSurface.ARENA)
    private val messageState = MutableStateFlow<Banner?>(null)
    private val undoState = MutableStateFlow<Cleared?>(null)
    private val nextMessage = AtomicLong()

    // Fair, so actions get their turn in the order they were asked for
    private val turns = Mutex()

    /** The surface being edited */
    val surface: StateFlow<EditedSurface> = surfaceState.asStateFlow()

    /** What the screen's banner says: how the last action that read or wrote a file went; null for nothing */
    val message: StateFlow<Banner?> = messageState.asStateFlow()

    /** What the last Clear took off, while its Undo is offered */
    val undo: StateFlow<Cleared?> = undoState.asStateFlow()

    fun show(surface: EditedSurface) {
        surfaceState.value = surface
    }

    fun editor(surface: EditedSurface): TargetEditor = if (surface == EditedSurface.ARENA) arenaEditor else cameraEditor

    fun targets(surface: EditedSurface): SurfaceTargets = if (surface == EditedSurface.ARENA) layout.targets else feedTargets

    fun dismissMessage() {
        messageState.value = null
    }

    /**
     * Adds [choice] to the surface being edited, at its own size in the surface's middle (a target that asks
     * to fill the canvas fills it), and selects it.
     */
    fun addTarget(choice: TargetChoice) {
        val surface = surfaceState.value
        inOrder {
            endUndoOffer()
            val definition = try {
                TargetDefinitions.load(choice.file.toPath())
            } catch (e: TargetFormatException) {
                say("Couldn't load the target ${choice.name}: ${e.message}", BannerKind.ERROR)
                return@inOrder
            }
            val targets = targets(surface)
            val size = if (surface == EditedSurface.ARENA) layout.size.value else feedSize
            val target = targets.add(definition, ResourceResolver.files(), placedInMiddle(definition, size), TargetOwner.USER)
            editor(surface).select(target.id)
        }
    }

    /** Puts up one of ShootOFF's backgrounds, or none for null */
    fun useBackground(background: BundledBackground?) {
        inOrder {
            endUndoOffer()
            if (background == null) {
                layout.setBackground(null)
            } else {
                readBackground(CourseBackground(background.resource, true), background.name)
            }
        }
    }

    /** Asks for an image file and puts it up as the background; cancelling leaves the background as it was */
    fun pickBackground() {
        val file = picker.pick() ?: return
        inOrder {
            endUndoOffer()
            readBackground(CourseBackground(file.toURI().toString(), false), file.name)
        }
    }

    /**
     * Replaces the shooter's arena targets and background with [choice]'s. A course that can't be read leaves
     * the arena as it was; one with missing target files loads the rest and names them.
     */
    fun loadCourse(choice: CourseChoice) {
        inOrder {
            endUndoOffer()
            arenaEditor.deselect()
            val course = CourseIO.loadCourse(choice.file).orElse(null)
            if (course == null) {
                say("Couldn't read the course ${choice.name}; the arena is as it was.", BannerKind.ERROR)
                return@inOrder
            }
            val applied = loader.apply(course, layout)
            val problems = buildList {
                if (applied.missing.isNotEmpty()) add("these target files are missing, so they were left out: ${applied.missing.joinToString(", ")}")
                applied.backgroundMissing?.let { add("its background $it couldn't be read") }
            }
            if (problems.isNotEmpty()) say("Loaded ${choice.name}, but ${problems.joinToString("; ")}.", BannerKind.WARNING)
        }
    }

    /**
     * Saves the shooter's arena targets and background as the course [name] in the courses folder.
     *
     * @param replace whether a course of that name may be replaced (the shooter said yes)
     */
    fun saveCourse(name: String, replace: Boolean = false): SaveOutcome {
        // A trailing ".course" is the file's, not part of the name
        val trimmed = name.trim().let { if (it.endsWith(".course", ignoreCase = true)) it.dropLast(".course".length) else it }
        if (trimmed.isEmpty() || trimmed.startsWith('.') || trimmed.contains('/') || trimmed.contains('\\')) return SaveOutcome.BAD_NAME
        val file = File(files.courses, "$trimmed.course")
        if (file.exists() && !replace) return SaveOutcome.EXISTS
        inOrder {
            endUndoOffer()
            val course = loader.course(layout)
            files.courses.mkdirs()
            if (loader.save(course, file)) {
                say("Saved the course $trimmed.", BannerKind.INFO)
            } else {
                say("Couldn't save the course $trimmed.", BannerKind.ERROR)
            }
        }
        return SaveOutcome.SAVING
    }

    /** Takes the shooter's targets (and, on the arena, the background) off the surface being edited, offering Undo */
    fun clear() {
        val surface = surfaceState.value
        inOrder {
            val targets = targets(surface)
            val shooters = targets.targetsOf(TargetOwner.USER)
            val background = if (surface == EditedSurface.ARENA) layout.background.value else null
            if (shooters.isEmpty() && background == null) {
                endUndoOffer()
                return@inOrder
            }
            undoState.value = Cleared(surface, shooters.map { it.definition to it.placement }, background)
            for (target in shooters) targets.remove(target.id)
            if (surface == EditedSurface.ARENA) layout.setBackground(null)
        }
    }

    /** Puts back what the last Clear took off, while its Undo is offered */
    fun undoClear() {
        inOrder {
            val cleared = undoState.value ?: return@inOrder
            undoState.value = null
            if (cleared.surface == EditedSurface.ARENA) layout.setBackground(cleared.background)
            val targets = targets(cleared.surface)
            for ((definition, placement) in cleared.targets) targets.add(definition, ResourceResolver.files(), placement, TargetOwner.USER)
        }
    }

    /** The Undo offer ran out */
    fun dropUndo() {
        inOrder { endUndoOffer() }
    }

    private fun endUndoOffer() {
        undoState.value = null
    }

    // Runs [work] on io once every action asked for before it has finished. The turn is taken on the calling
    // thread, so it is the order of the calls that counts, not which thread gets going first.
    private fun inOrder(work: () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            turns.withLock { withContext(io) { work() } }
        }
    }

    // Where [definition] goes: the middle of a surface of [size] at its own size, or all of it if it fills the canvas
    private fun placedInMiddle(definition: TargetDefinition, size: Size): Placement {
        val trial = TargetSet()
        val target = trial.add(definition, Placement.ORIGIN)
        if (definition.fillsCanvas()) trial.resize(target.id, size.width, size.height)
        val bounds = trial.get(target.id).get().bounds
        val (x, y) = if (definition.fillsCanvas()) {
            0.0 to 0.0
        } else {
            (size.width - bounds.width) / 2 to (size.height - bounds.height) / 2
        }
        val placement = trial.get(target.id).get().placement
        return placement.withPosition(placement.x() + x - bounds.minX, placement.y() + y - bounds.minY)
    }

    private fun readBackground(source: CourseBackground, name: String) {
        val background = loader.readBackground(source)
        if (background == null) {
            say("Couldn't read the background $name.", BannerKind.ERROR)
        } else {
            layout.setBackground(background)
        }
    }

    private fun say(text: String, kind: BannerKind) {
        messageState.value = Banner(nextMessage.incrementAndGet(), text, kind)
    }
}
