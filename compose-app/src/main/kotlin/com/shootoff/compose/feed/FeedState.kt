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

import androidx.compose.ui.graphics.ImageBitmap
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * A frame to draw, and where it goes on the feed's canvas (the whole canvas, or the arena's projection
 * when the feed is cropped to it).
 */
class FeedFrame(val image: ImageBitmap, val bounds: Rect)

enum class BannerKind { WARNING, INFO, CALIBRATION, ERROR }

data class Banner(val id: Long, val text: String, val kind: BannerKind)

/**
 * What one camera feed shows, for Compose to draw. Written from the camera's thread and others; only the
 * newest frame is kept, so frames never queue behind a slow draw.
 */
class FeedState(val displaySize: Size, private val clock: () -> Long = System::currentTimeMillis) {
    private val frameState = MutableStateFlow<FeedFrame?>(null)
    private val bannerState = MutableStateFlow<List<Banner>>(emptyList())
    private val fpsState = MutableStateFlow(0.0)
    private val frameTimes = ArrayDeque<Long>()
    private val nextBanner = AtomicLong()

    val frame: StateFlow<FeedFrame?> = frameState.asStateFlow()
    val banners: StateFlow<List<Banner>> = bannerState.asStateFlow()

    /** Frames shown in the last second */
    val fps: StateFlow<Double> = fpsState.asStateFlow()

    fun showFrame(frame: FeedFrame) {
        frameState.value = frame
        val now = clock()
        synchronized(frameTimes) {
            frameTimes.addLast(now)
            while (frameTimes.isNotEmpty() && now - frameTimes.first() >= 1000) frameTimes.removeFirst()
            fpsState.value = frameTimes.size.toDouble()
        }
    }

    fun clearFrame() {
        frameState.value = null
        synchronized(frameTimes) {
            frameTimes.clear()
            fpsState.value = 0.0
        }
    }

    fun addBanner(text: String, kind: BannerKind): Banner {
        val banner = Banner(nextBanner.incrementAndGet(), text, kind)
        bannerState.update { it + banner }
        return banner
    }

    fun removeBanner(banner: Banner) {
        bannerState.update { banners -> banners.filterNot { it.id == banner.id } }
    }
}
