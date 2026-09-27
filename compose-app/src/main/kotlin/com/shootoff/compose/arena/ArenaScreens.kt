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

package com.shootoff.compose.arena

import com.shootoff.geom.Point
import com.shootoff.geom.ProjectorScreens
import com.shootoff.geom.Rect
import java.awt.GraphicsEnvironment
import java.util.Optional
import java.util.OptionalInt

/**
 * Where the arena window opens.
 *
 * @param screen the screen to go full screen on; null when no screen looks like a projector
 * @param position where the window opens, before it goes full screen
 */
data class ArenaPlacement(val screen: Rect?, val position: Point)

object ArenaScreens {
    /** The screens, in AWT's order and coordinates */
    fun screens(): List<Rect> {
        if (GraphicsEnvironment.isHeadless()) return emptyList()
        return GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map {
            val b = it.defaultConfiguration.bounds
            Rect(b.x.toDouble(), b.y.toDouble(), b.width.toDouble(), b.height.toDouble())
        }
    }

    /**
     * Picks the arena's screen with core's rule (see ProjectorScreens). The window opens where ShootOFF is,
     * then moves: to the saved position, or 10 px into the chosen screen, as the JavaFX app places it.
     *
     * @param appScreen the screen ShootOFF's main window is on
     */
    fun place(screens: List<Rect>, appScreen: Int, savedPosition: Point?): ArenaPlacement {
        val choice = ProjectorScreens.choose(screens, appScreen, OptionalInt.of(appScreen), Optional.ofNullable(savedPosition))
        if (choice.isEmpty) {
            val home = screens.getOrNull(appScreen)
            return ArenaPlacement(null, Point((home?.minX ?: 0.0) + 10, (home?.minY ?: 0.0) + 10))
        }

        val screen = screens[choice.get().screen()]
        val position = if (choice.get().reason() == ProjectorScreens.Reason.SAVED_POSITION) {
            savedPosition!!
        } else {
            Point(screen.minX + 10, screen.minY + 10)
        }
        return ArenaPlacement(screen, position)
    }

    /** The screen containing a point, e.g. the main window's top left corner */
    fun screenAt(screens: List<Rect>, point: Point): Int = screens.indexOfFirst { it.contains(point.x, point.y) }.coerceAtLeast(0)
}
