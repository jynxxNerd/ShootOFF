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

import java.util.concurrent.ConcurrentHashMap
import java.util.prefs.Preferences

/** Where the Compose app keeps its own preferences: not in shootoff.properties, which the JavaFX app rewrites. */
interface PrefsStore {
    fun get(key: String): String?

    fun put(key: String, value: String)

    /** The user's Java preferences, under com/shootoff/compose */
    class User(private val node: Preferences = Preferences.userRoot().node("com/shootoff/compose")) : PrefsStore {
        override fun get(key: String): String? = node.get(key, null)

        override fun put(key: String, value: String) = node.put(key, value)
    }

    /** For tests */
    class Memory : PrefsStore {
        private val values = ConcurrentHashMap<String, String>()

        override fun get(key: String): String? = values[key]

        override fun put(key: String, value: String) {
            values[key] = value
        }
    }
}

/** The main window's place and size, in dp */
data class WindowBounds(val x: Float, val y: Float, val width: Float, val height: Float)

/** The look and layout the user left the Compose app in. */
class UiPrefs(private val store: PrefsStore = PrefsStore.Memory()) {
    var dark: Boolean
        get() = store.get("theme") != "light"
        set(value) = store.put("theme", if (value) "dark" else "light")

    var trayHeight: Float
        get() = store.get("tray.height")?.toFloatOrNull() ?: DEFAULT_TRAY_HEIGHT
        set(value) = store.put("tray.height", value.toString())

    var trayCollapsed: Boolean
        get() = store.get("tray.collapsed") == "true"
        set(value) = store.put("tray.collapsed", value.toString())

    var window: WindowBounds?
        get() {
            val parts = store.get("window")?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: return null
            return if (parts.size == 4) WindowBounds(parts[0], parts[1], parts[2], parts[3]) else null
        }
        set(value) {
            if (value != null) store.put("window", "${value.x},${value.y},${value.width},${value.height}")
        }

    companion object {
        const val DEFAULT_TRAY_HEIGHT = 220f
    }
}
