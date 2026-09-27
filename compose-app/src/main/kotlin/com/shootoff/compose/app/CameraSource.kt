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

import com.shootoff.camera.CameraFactory
import com.shootoff.camera.cameratypes.Camera
import com.shootoff.config.Settings

/** The cameras the app can open. */
interface CameraSource {
    /** Every camera plugged in */
    fun cameras(): List<Camera>

    /** The camera to open at start: the configured one, else the system default */
    fun startCamera(settings: Settings): Camera?

    /**
     * [camera] as it is plugged in now, found by its name (it may be back under another device number), or
     * null if it is no longer plugged in. Called off the UI thread: it may list the cameras.
     */
    fun current(camera: Camera): Camera? = camera

    object System : CameraSource {
        override fun cameras(): List<Camera> = CameraFactory.getWebcams()

        override fun startCamera(settings: Settings): Camera? =
            settings.webcams.values.firstOrNull() ?: CameraFactory.getDefault().orElse(null)

        override fun current(camera: Camera): Camera? {
            val found = cameras()
            return found.firstOrNull { it.name == camera.name } ?: found.firstOrNull { sameCamera(it.name, camera.name) }
        }
    }

    object None : CameraSource {
        override fun cameras(): List<Camera> = emptyList()

        override fun startCamera(settings: Settings): Camera? = null
    }
}

private val DEVICE_PATH = Regex(""" /dev/video\d+$""")

/**
 * Whether [a] and [b] name the same physical camera: an exact match, or a match once a trailing
 * ` /dev/videoN` device path is stripped from both sides. Sarxos camera names include the device node
 * (e.g. "UVC Camera (046d:0825) /dev/video0"), and the node can change when a camera re-enumerates after
 * being unplugged and replugged. Internal to the module so Task 6's reconnect can reuse it.
 */
internal fun sameCamera(a: String, b: String): Boolean = a == b || stripDevicePath(a) == stripDevicePath(b)

private fun stripDevicePath(name: String): String = name.replace(DEVICE_PATH, "")
