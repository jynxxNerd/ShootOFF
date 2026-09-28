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

import com.shootoff.compose.courses.LayoutMemory
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files

/** A target file Add target offers, by its name */
data class TargetChoice(val file: File, val name: String)

/**
 * A course file Load course offers.
 *
 * @param group the folder it is in under the courses folder ("steel challenge"), or "" at its top
 */
data class CourseChoice(val file: File, val name: String, val group: String)

/** One of ShootOFF's own arena backgrounds */
data class BundledBackground(val name: String, val resource: String)

/** The backgrounds ShootOFF comes with, by name, as the JavaFX app offered them */
val BUNDLED_BACKGROUNDS = listOf(
    BundledBackground("Hickok45 Autumn", "/arena/backgrounds/hickok45_autumn.gif"),
    BundledBackground("Hickok45 Summer", "/arena/backgrounds/hickok45_summer.gif"),
    BundledBackground("Indoor Range", "/arena/backgrounds/indoor_range.gif"),
    BundledBackground("Kiang West Savanna", "/arena/backgrounds/kiang_west_savanna.gif"),
    BundledBackground("Oradour-sur-Glane", "/arena/backgrounds/oradour-sur-glane.gif"),
    BundledBackground("Outdoor Range", "/arena/backgrounds/outdoor_range.gif"),
    BundledBackground("Steel Range Bay", "/arena/backgrounds/steel_range_bay.gif"),
    BundledBackground("Subterranean Parking Lot", "/arena/backgrounds/subterranean_parking_lot.gif"),
)

/** Asks the shooter for an image file for the arena's background */
fun interface ImagePicker {
    /** @return the file, or null if they cancelled */
    fun pick(): File?

    companion object {
        /** AWT's file dialog, on the UI thread */
        val Awt = ImagePicker {
            val dialog = FileDialog(null as Frame?, "Arena background", FileDialog.LOAD)
            dialog.setFilenameFilter { _, name -> name.lowercase().let { it.endsWith(".png") || it.endsWith(".gif") || it.endsWith(".jpg") || it.endsWith(".jpeg") } }
            dialog.isVisible = true
            dialog.file?.let { File(dialog.directory, it) }
        }
    }
}

/**
 * Where the Targets screen finds and keeps its files.
 *
 * @param home ShootOFF's folder: a course names its target files relative to it
 * @param targets the folder whose target files Add target offers
 * @param courses the folder (with its subfolders) Load course offers and Save course writes to
 * @param layout the file the arena's layout is remembered in; null remembers nothing
 */
class ArenaFiles(val home: File, val targets: File, val courses: File, val layout: File?) {
    companion object {
        /** ShootOFF's own folders, as the app uses them */
        fun inHome(home: File, courses: File = File(home, "courses")) =
            ArenaFiles(home, File(home, "targets"), courses, File(home, LayoutMemory.FILE_NAME))

        /**
         * The default, which touches none of the shooter's files: ShootOFF's own targets (read only), an empty
         * courses folder of its own, and no remembered layout. The app passes [inHome].
         */
        fun scratch(): ArenaFiles {
            val home = File(System.getProperty("user.dir"))
            val courses = Files.createTempDirectory("shootoff-courses").toFile().apply { deleteOnExit() }
            return ArenaFiles(home, File(home, "targets"), courses, null)
        }
    }

    /** The target files in [targets] (not its subfolders, as in the JavaFX app), by name */
    fun targetChoices(): List<TargetChoice> =
        (targets.listFiles { file -> file.isFile && file.name.endsWith(".target") } ?: emptyArray())
            .map { TargetChoice(it, displayName(it)) }
            .sortedBy { it.name.lowercase() }

    /** The course files in [courses] and its subfolders, by group then name; hidden files are left out */
    fun courseChoices(): List<CourseChoice> {
        if (!courses.isDirectory) return emptyList()
        return courses.walkTopDown()
            .onEnter { it == courses || !isHiddenFile(it) }
            .filter { it.isFile && !isHiddenFile(it) && it.name.endsWith(".course") }
            .map { file ->
                val folder = file.parentFile.relativeTo(courses).path
                CourseChoice(file, displayName(file), folder.replace('_', ' ').replace(File.separatorChar, '/'))
            }
            .sortedWith(compareBy({ it.group.lowercase() }, { it.name.lowercase() }))
            .toList()
    }

    // By name too: CourseLoader.save writes a temp ".name.course" beside the file, and on Windows isHidden ignores a leading dot
    private fun isHiddenFile(file: File) = file.isHidden || file.name.startsWith(".")

    // "Steel_Challenge_Circle.target" is "Steel Challenge Circle"
    private fun displayName(file: File) = file.nameWithoutExtension.replace('_', ' ')
}
