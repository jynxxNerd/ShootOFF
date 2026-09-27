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

import com.shootoff.camera.CameraErrorView
import com.shootoff.camera.cameratypes.Camera
import com.shootoff.compose.feed.BannerKind
import com.shootoff.compose.feed.FeedState
import com.shootoff.config.Settings

/**
 * The camera's troubles, as the Compose app shows them: a camera that can't be opened or stops
 * answering leaves the feed on its "No camera" panel (with a camera picker), never a frozen frame; a low
 * frame rate or a very bright picture is a banner on the feed.
 *
 * @param lost the camera stopped answering (on its own thread): the app closes it, if it is still the open one
 */
class CameraProblems(
    private val settings: Settings,
    private val feed: FeedState,
    private val problem: (String) -> Unit,
    private val lost: (Camera) -> Unit,
) : CameraErrorView {
    private fun name(camera: Camera): String = settings.getWebcamsUserName(camera).orElse(camera.name)

    override fun showCameraLockError(webcam: Camera, allCamerasFailed: Boolean) =
        problem("Cannot open the webcam ${name(webcam)}. It is being used by another program or it is an IPCam with the wrong credentials.")

    // Only the app knows whether [webcam] is still the open camera (a replaced one can report late): it
    // clears the feed and shows [missingMessage] only if so
    override fun showMissingCameraError(webcam: Camera) = lost(webcam)

    /** [webcam] was picked from a list made before it was unplugged: it isn't opened */
    fun showNotConnected(webcam: Camera) = problem("${name(webcam)} is not connected. Plug it in, or pick another camera.")

    /** What the feed says when [webcam] stops answering */
    fun missingMessage(webcam: Camera): String = String.format(CameraErrorView.MISSING_ERROR, name(webcam))

    /** [webcam]'s driver threw [error] as it opened */
    fun showOpenError(webcam: Camera, error: Throwable) =
        problem("Cannot open the webcam ${name(webcam)}: ${error.javaClass.simpleName}" + (error.message?.let { ": $it" } ?: ""))

    override fun showFPSWarning(webcam: Camera, fps: Double) {
        feed.addBanner(String.format(CameraErrorView.FPS_WARNING, name(webcam), fps), BannerKind.WARNING)
    }

    override fun showBrightnessWarning(webcam: Camera) {
        feed.addBanner(String.format(CameraErrorView.BRIGHTNESS_WARNING, name(webcam)), BannerKind.WARNING)
    }
}
