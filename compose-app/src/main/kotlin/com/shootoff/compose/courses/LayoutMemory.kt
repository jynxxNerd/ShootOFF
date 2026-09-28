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

package com.shootoff.compose.courses

import com.shootoff.calibration.CalibrationFlow
import com.shootoff.compose.arena.ArenaLayout
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.courses.io.CourseIO
import com.shootoff.targets.model.PlacedTarget
import com.shootoff.targets.model.TargetId
import com.shootoff.targets.model.TargetSetListener
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future

/**
 * Remembers the shooter's arena between sessions: after a change to their targets or background, and
 * [quietMillis] without another, it saves the layout as an ordinary course, on [timers]' thread; [restore]
 * loads it back. An exercise's targets are neither saved nor, as they come and go, a reason to save.
 *
 * @param file the course the layout is kept in, `arena-layout.course` in ShootOFF's folder
 * @param timers runs the save off the UI thread once the changes stop
 */
class LayoutMemory(
    private val layout: ArenaLayout,
    private val loader: CourseLoader,
    private val file: File,
    private val timers: CalibrationFlow.Scheduler,
    private val quietMillis: Long = QUIET_MILLIS,
) {
    companion object {
        const val FILE_NAME = "arena-layout.course"
        const val QUIET_MILLIS = 500L
    }

    private val logger = LoggerFactory.getLogger(LayoutMemory::class.java)
    private val lock = Any()

    // Guarded by lock: the save waiting for the changes to stop
    private var pending: Future<*>? = null

    // Set while restore applies the remembered layout, whose own changes aren't the shooter's
    @Volatile
    private var restoring = false

    @Volatile
    private var changed = false

    // Whether the remembered file already reflects this session: the shooter has changed the layout or a restore loaded it
    @Volatile
    private var fileReflectsSession = false

    // The shooter's targets, since a removed target's owner is already forgotten
    private val shootersTargets: MutableSet<TargetId> = ConcurrentHashMap.newKeySet()

    private var started = false

    /** Whether the shooter has changed the layout this session: once they have, [restore] leaves it alone */
    val changedThisSession: Boolean get() = changed

    /** Starts watching the layout for the shooter's changes */
    fun start() {
        if (started) return // one watch only, however often it is asked for
        started = true
        layout.targets.set.addListener(object : TargetSetListener {
            override fun targetAdded(target: PlacedTarget) {
                if (layout.targets.owner(target.id) == TargetOwner.USER) {
                    shootersTargets += target.id
                    changedByTheShooter()
                }
            }

            // Its owner is already gone, so the shooter's own targets are the ones remembered here
            override fun targetRemoved(target: PlacedTarget) {
                if (shootersTargets.remove(target.id)) changedByTheShooter()
            }

            override fun targetChanged(target: PlacedTarget) {
                if (layout.targets.owner(target.id) == TargetOwner.USER) changedByTheShooter()
            }
        })
        layout.addBackgroundListener { changedByTheShooter() }
        // A save before the layout is the shooter's would replace the remembered one, so only after an edit or a restore
        layout.addSizeListener { if (fileReflectsSession && !restoring) scheduleSave() }
    }

    /**
     * Loads the remembered layout through [CourseLoader], unless the shooter has changed this session's
     * already (their changes have replaced it). A missing or unreadable file leaves the arena empty, and a
     * target file it names that can't be loaded is left out; each is logged, with no word to the shooter.
     *
     * @return whether the layout was loaded
     */
    fun restore(): Boolean {
        if (changed) {
            logger.info("The arena layout changed before it could be restored: {} is left as it is", file)
            return false
        }
        if (!file.isFile) {
            logger.info("No remembered arena layout ({}): the arena starts empty", file)
            return false
        }
        val course = CourseIO.loadCourse(file).orElse(null)
        if (course == null) {
            logger.warn("Can't read the remembered arena layout {}: the arena starts empty", file)
            return false
        }
        restoring = true
        val applied = try {
            loader.apply(course, layout)
        } finally {
            restoring = false
        }
        for (missing in applied.missing) logger.warn("The remembered arena layout's target {} can't be loaded: it is left out", missing)
        applied.backgroundMissing?.let { logger.warn("The remembered arena layout's background {} can't be read: it is left out", it) }
        fileReflectsSession = true
        logger.info("Restored the arena layout from {}", file)
        return true
    }

    private fun changedByTheShooter() {
        if (restoring) return
        changed = true
        fileReflectsSession = true
        scheduleSave()
    }

    private fun scheduleSave() {
        synchronized(lock) {
            pending?.cancel(false)
            pending = timers.schedule(::save, quietMillis)
            if (pending == null) logger.warn("The arena layout can't be scheduled for saving to {}", file)
        }
    }

    // One save at a time: a slow one still running when the next is due finishes first
    @Synchronized
    private fun save() {
        try {
            if (loader.save(loader.course(layout), file)) logger.debug("Saved the arena layout to {}", file)
        } catch (e: RuntimeException) {
            logger.error("Couldn't save the arena layout to {}", file, e)
        }
    }
}
