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

package com.shootoff.compose.targets

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.shootoff.targets.model.AlphaMask
import com.shootoff.targets.model.GifFrames
import com.shootoff.targets.model.ImageRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import org.slf4j.LoggerFactory
import java.io.File
import javax.imageio.ImageIO

/**
 * An image region's pixels: one frame, or an animation's frames. [cycleMillis] is how long the JavaFX app
 * takes to play the whole animation once: the first frame's delay, or 100 ms without one.
 */
class RegionImage(val frames: List<ImageBitmap>, val masks: List<AlphaMask>, val cycleMillis: Long) {
    val animated: Boolean get() = frames.size > 1
}

object RegionImages {
    private val logger = LoggerFactory.getLogger(RegionImages::class.java)
    const val DEFAULT_CYCLE_MILLIS = 100L

    /**
     * Reads every image region of a target, by region index. A region whose image can't be read is left
     * out and logged; the target is still drawn.
     */
    fun load(definition: TargetDefinition, resolver: ResourceResolver): Map<Int, RegionImage> {
        val images = mutableMapOf<Int, RegionImage>()
        for (region in definition.regions()) {
            if (region !is ImageRegion) continue
            try {
                read(region, resolver)?.let { images[region.index()] = it }
            } catch (e: Exception) {
                logger.error("Can't read image {} of a target", region.imagePath(), e)
            }
        }
        return images
    }

    private fun read(region: ImageRegion, resolver: ResourceResolver): RegionImage? {
        val path = region.imagePath()
        val stream = resolver.open(path).orElse(null) ?: run {
            logger.error("Can't find image {} of a target", path)
            return null
        }

        stream.use { input ->
            if (isGif(path)) {
                val frames = GifFrames.read(input)
                if (frames.isEmpty()) return null
                val delay = frames[0].delayMillis().toLong().let { if (it < 1) DEFAULT_CYCLE_MILLIS else it }
                return RegionImage(
                    frames.map { it.image().toComposeImageBitmap() },
                    frames.map { AlphaMask.of(it.image()) },
                    delay,
                )
            }

            val image = ImageIO.read(input) ?: return null
            return RegionImage(listOf(image.toComposeImageBitmap()), listOf(AlphaMask.of(image)), DEFAULT_CYCLE_MILLIS)
        }
    }

    // The JavaFX app animates an image when the part of its file name after the first "." ends with "gif"
    private fun isGif(path: String): Boolean {
        val name = File(path).name
        return name.substring(name.indexOf('.') + 1).endsWith("gif")
    }
}
