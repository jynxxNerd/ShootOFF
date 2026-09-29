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

package com.shootoff.compose.feed

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.shootoff.camera.CameraManager
import com.shootoff.camera.CameraView
import com.shootoff.camera.DiagnosticMessage
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.geom.ArenaGeometry
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import java.awt.image.BufferedImage
import java.util.Optional

/**
 * Where a camera feed's shots go: the feed surface's shot pipeline (see FeedSurface).
 */
interface FeedShots {
    fun add(shot: ScaledShot)

    fun clear()

    fun reset()

    /** Whether the feed has targets of its own; asked on the camera's thread for every frame */
    fun hasTargets(): Boolean

    object None : FeedShots {
        override fun add(shot: ScaledShot) {}

        override fun clear() {}

        override fun reset() {}

        override fun hasTargets() = false
    }
}

/**
 * One camera feed in the Compose app, as its [CameraManager] sees it. Frames are converted for Compose on
 * the camera's thread, never the UI thread, and only the newest is kept ([FeedState]).
 */
class ComposeCameraView(
    val name: String,
    val feed: FeedState,
    @Volatile var shots: FeedShots = FeedShots.None,
) : CameraView {
    @Volatile
    var cameraManager: CameraManager? = null
        private set

    /** Also hears every frame, on the camera's thread (the calibration check takes them from here) */
    @Volatile
    var frameTap: ((BufferedImage) -> Unit)? = null

    override fun setCameraManager(cameraManager: CameraManager) {
        this.cameraManager = cameraManager
    }

    override fun updateBackground(frame: BufferedImage?, projectionBounds: Optional<Rect>) {
        if (frame == null) {
            feed.clearFrame()
            return
        }

        frameTap?.invoke(frame)

        val display = feed.displaySize
        val bounds = projectionBounds.map { toCanvas(it) }.orElse(Rect(0.0, 0.0, display.width, display.height))
        feed.showFrame(FeedFrame(frame.toComposeImageBitmap(), bounds))
    }

    // The projection, which the camera reports on its feed, on the feed's canvas
    private fun toCanvas(cameraBounds: Rect): Rect {
        val camera = cameraManager ?: return cameraBounds
        return ArenaGeometry.cameraToCanvas(cameraBounds, Size(camera.feedWidth.toDouble(), camera.feedHeight.toDouble()), feed.displaySize)
    }

    override fun addShot(shot: ScaledShot) = shots.add(shot)

    override fun addDiagnosticWarning(message: String): DiagnosticMessage {
        val banner = feed.addBanner(message, BannerKind.WARNING)
        return DiagnosticMessage { feed.removeBanner(banner) }
    }

    override fun clearShots() = shots.clear()

    override fun hasTargets() = shots.hasTargets()

    override fun reset() = shots.reset()

    override fun close() = feed.clearFrame()
}
