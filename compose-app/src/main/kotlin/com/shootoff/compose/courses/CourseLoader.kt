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

import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.arena.ArenaLayout
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.courses.Course
import com.shootoff.courses.CourseBackground
import com.shootoff.courses.CourseTarget
import com.shootoff.courses.io.CourseIO
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinitions
import com.shootoff.targets.model.TargetFormatException
import com.shootoff.targets.model.TargetId
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Optional
import kotlin.math.abs

/**
 * What applying a course did.
 *
 * @param missing the course's target files that couldn't be loaded, as the course names them
 * @param backgroundMissing the course's background, if it couldn't be read
 */
data class CourseApplied(val missing: List<String>, val backgroundMissing: String?)

/**
 * Applies courses to the arena's layout and makes courses from it, as the JavaFX arena's setCourse and
 * getCourse did. Only the shooter's targets ([TargetOwner.USER]) are touched or saved: a running
 * exercise's stay where they are and are never part of a course.
 *
 * A course target's x and y are its [Placement] position (JavaFX's layoutX and layoutY): where the
 * target's own (0, 0) would be unscaled. A target is scaled about its center, so a small target made from
 * a big image has a position well off its bounds, even a negative one, as in the bundled Steel Challenge
 * courses. Its width and height are its bounds' size on the arena.
 *
 * @param home ShootOFF's folder: course target files are named relative to it
 */
class CourseLoader(private val home: File) {
    private val logger = LoggerFactory.getLogger(CourseLoader::class.java)

    /**
     * Replaces the shooter's targets with [course]'s, and the background too when the course has one it can
     * read (a course without one, or with one that can't be read, keeps the current background, as the JavaFX
     * app did). A course saved at another arena size is scaled to [layout]'s lasting size, by width and height separately.
     * A target file that can't be loaded is skipped, and a background that can't be read is reported.
     */
    fun apply(course: Course, layout: ArenaLayout): CourseApplied {
        val targets = layout.targets
        for (target in targets.targetsOf(TargetOwner.USER)) targets.remove(target.id)

        val background = course.background.orElse(null)
        val image = background?.let(::readBackground)
        if (image != null) layout.setBackground(image)

        val arena = layout.lastingSize
        val resolution = course.resolution.orElse(null)
        val scale = resolution != null && (abs(resolution.width - arena.width) > .0001 || abs(resolution.height - arena.height) > .0001)
        val widthFactor = if (scale) arena.width / resolution.width else 1.0
        val heightFactor = if (scale) arena.height / resolution.height else 1.0

        val missing = mutableListOf<String>()
        for (courseTarget in course.targets) {
            val file = resolve(courseTarget.file())
            val definition = try {
                TargetDefinitions.load(file.toPath())
            } catch (e: TargetFormatException) {
                logger.warn("Can't load the course's target {}: {}", courseTarget.file().path, e.message)
                missing += courseTarget.file().path
                continue
            }
            val placed = targets.add(definition, ResourceResolver.files(), Placement(courseTarget.x(), courseTarget.y(), 1.0, 1.0, true), TargetOwner.USER)
            targets.set.resize(placed.id, courseTarget.width(), courseTarget.height())
            if (scale) scale(placed.id, layout, widthFactor, heightFactor)
        }

        return CourseApplied(missing, if (background != null && image == null) background.url() else null)
    }

    /** The shooter's targets and background, and the arena's size, as a course */
    fun course(layout: ArenaLayout): Course {
        val targets = layout.targets.targetsOf(TargetOwner.USER).mapNotNull { target ->
            val file = target.definition.file().orElse(null) ?: return@mapNotNull null
            val bounds = target.bounds
            CourseTarget(relative(file), target.placement.x(), target.placement.y(), bounds.width, bounds.height)
        }
        return Course(Optional.ofNullable(layout.background.value?.source), targets, Optional.of(layout.lastingSize))
    }

    /**
     * Writes [course] to [file], replacing it whole: it is written beside it first, so a failure leaves the
     * old file as it was.
     *
     * @return false if it couldn't be written
     */
    fun save(course: Course, file: File, write: (Course, File) -> Unit = CourseIO::saveCourse): Boolean {
        // Hidden, and still ending in "course", which CourseIO insists on
        val part = File(file.absoluteFile.parentFile, ".${file.name}")
        part.delete()
        write(course, part)
        // CourseIO swallows write errors, so a partial file is only caught by reading it back
        if (!part.isFile || !CourseIO.loadCourse(part).isPresent) {
            logger.error("Couldn't write the course {}", file)
            part.delete()
            return false
        }
        return try {
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: IOException) {
            logger.error("Couldn't write the course {}", file, e)
            part.delete()
            false
        }
    }

    /** A course's background, or null if it can't be read */
    fun readBackground(source: CourseBackground): ArenaBackground? {
        val image = try {
            val stream = if (source.isResource()) {
                ArenaBackground::class.java.getResourceAsStream(source.url())
            } else {
                URI(source.url()).toURL().openStream()
            }
            stream?.let { ArenaBackground.read(it, source.url(), source) }
        } catch (e: Exception) {
            logger.warn("Can't read the arena background {}", source.url(), e)
            return null
        }
        if (image == null) logger.warn("Can't read the arena background {}", source.url())
        return image
    }

    // TargetView.scale, step for step: the target's bounds scale about the arena's origin
    private fun scale(id: TargetId, layout: ArenaLayout, widthFactor: Double, heightFactor: Double) {
        val set = layout.targets.set
        val target = set.get(id).orElse(null) ?: return
        val bounds = target.bounds
        val newWidth = bounds.width * widthFactor
        val deltaX = bounds.minX * widthFactor - bounds.minX + (newWidth - bounds.width) / 2
        val newHeight = bounds.height * heightFactor
        val deltaY = bounds.minY * heightFactor - bounds.minY + (newHeight - bounds.height) / 2
        set.move(id, target.placement.x() + deltaX, target.placement.y() + deltaY)
        set.resize(id, newWidth, newHeight)
    }

    // A course saved on Windows names its targets with backslashes
    private fun resolve(file: File): File = if (file.isAbsolute) file else File(home, file.path.replace('\\', '/'))

    // Relative to ShootOFF's folder when inside it, as the JavaFX app saved them
    private fun relative(file: File): File {
        val path = file.absoluteFile.toPath().normalize()
        val base = home.absoluteFile.toPath().normalize()
        return if (path.startsWith(base)) File(base.relativize(path).toFile().invariantSeparatorsPath) else path.toFile()
    }
}
