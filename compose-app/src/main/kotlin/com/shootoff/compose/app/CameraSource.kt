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

    object System : CameraSource {
        override fun cameras(): List<Camera> = CameraFactory.getWebcams()

        override fun startCamera(settings: Settings): Camera? =
            settings.webcams.values.firstOrNull() ?: CameraFactory.getDefault().orElse(null)
    }

    object None : CameraSource {
        override fun cameras(): List<Camera> = emptyList()

        override fun startCamera(settings: Settings): Camera? = null
    }
}
