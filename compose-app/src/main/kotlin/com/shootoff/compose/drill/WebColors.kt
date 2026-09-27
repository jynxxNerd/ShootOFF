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

import androidx.compose.ui.graphics.Color
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.shootoff.compose.drill.WebColors")

// The CSS names exercises use for text, backgrounds and row highlights
private val NAMED = mapOf(
    "transparent" to Color(0x00000000),
    "black" to Color(0xFF000000),
    "white" to Color(0xFFFFFFFF),
    "red" to Color(0xFFFF0000),
    "green" to Color(0xFF008000),
    "lime" to Color(0xFF00FF00),
    "blue" to Color(0xFF0000FF),
    "yellow" to Color(0xFFFFFF00),
    "orange" to Color(0xFFFFA500),
    "coral" to Color(0xFFFF7F50),
    "tomato" to Color(0xFFFF6347),
    "gold" to Color(0xFFFFD700),
    "gray" to Color(0xFF808080),
    "grey" to Color(0xFF808080),
    "lightgray" to Color(0xFFD3D3D3),
    "darkgray" to Color(0xFFA9A9A9),
    "silver" to Color(0xFFC0C0C0),
    "lightskyblue" to Color(0xFF87CEFA),
    "lightblue" to Color(0xFFADD8E6),
    "lightgreen" to Color(0xFF90EE90),
    "limegreen" to Color(0xFF32CD32),
    "cyan" to Color(0xFF00FFFF),
    "magenta" to Color(0xFFFF00FF),
    "pink" to Color(0xFFFFC0CB),
    "purple" to Color(0xFF800080),
    "brown" to Color(0xFFA52A2A),
    "navy" to Color(0xFF000080),
)

/**
 * A color an exercise names, as JavaFX's Color.web reads the common ones: a CSS name, or "#rgb",
 * "#rrggbb" or "#rrggbbaa". Anything else is gray, and logged.
 */
fun webColor(name: String): Color {
    val key = name.trim().lowercase()
    NAMED[key]?.let { return it }

    if (key.startsWith("#")) {
        val hex = key.substring(1)
        val expanded = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
        val value = expanded.toLongOrNull(16)
        when {
            value != null && expanded.length == 6 -> return Color(0xFF000000 or value)
            value != null && expanded.length == 8 -> return Color(((value and 0xFF) shl 24) or (value shr 8))
        }
    }

    logger.warn("Unknown color {}; using gray", name)
    return Color(0xFF808080)
}
