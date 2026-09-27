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

package com.shootoff.compose.shots

import com.shootoff.camera.shot.ScaledShot
import com.shootoff.compose.targets.RegionKey
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.Settings
import com.shootoff.exercise.ExercisePaths
import com.shootoff.geom.Point
import com.shootoff.shots.PoiAdjustment
import com.shootoff.sound.SoundPlayer
import com.shootoff.targets.model.Hit
import com.shootoff.targets.model.ImageRegion
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.Region
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * Carries out a hit region's commands on the Compose app's model, as the JavaFX app's TargetCommands does
 * on its nodes: <tt>reset</tt>, <tt>animate</tt>, <tt>reverse</tt>, <tt>play_sound</tt> and
 * <tt>poi_adjust</tt>.
 *
 * @param reset what Reset does (see RangeReset)
 * @param exerciseResources the running exercise's class loader, for "@" sounds from its jar
 * @param toCamera maps a point on this surface to the camera feed, for <tt>poi_adjust</tt>
 */
class RegionCommandRunner(
    private val targets: SurfaceTargets,
    private val settings: Settings,
    private val reset: () -> Unit,
    private val exerciseResources: () -> ClassLoader?,
    private val toCamera: (Point) -> Point = { it },
    private val sounds: Sounds = Sounds.Speakers,
) {
    interface Sounds {
        fun play(file: String)

        fun play(stream: InputStream)

        object Speakers : Sounds {
            override fun play(file: String) = SoundPlayer.play(file)

            override fun play(stream: InputStream) = SoundPlayer.play(stream)
        }
    }

    private val logger = LoggerFactory.getLogger(RegionCommandRunner::class.java)

    /**
     * @param mirrored true for a copy of a shot another surface handles (never in the Compose app)
     */
    fun run(shot: ScaledShot, hit: Hit, mirrored: Boolean) {
        for (command in hit.region().commands()) {
            when (command.name()) {
                "reset" -> reset()
                "animate" -> animate(hit, command.args())
                "reverse" -> reverse(hit)
                "play_sound" -> playSound(hit, command.args())
                "poi_adjust" -> if (!mirrored) poiAdjust(shot, hit)
            }
        }
    }

    private fun animate(hit: Hit, args: List<String>) {
        val resetAfter = args.firstOrNull() == "true"
        val region = if (args.isEmpty() || resetAfter) hit.region() else named(hit, args[0])
        if (region == null) {
            logger.error("Request to animate region named {}, but it doesn't exist.", args[0])
            return
        }

        val key = RegionKey(hit.targetId(), region.index())
        // Don't repeat animations for fallen targets
        if (!targets.animations.isOnFirstFrame(key)) return

        if (targets.animations.isAnimated(key)) {
            targets.animations.play(key, resetAfter)
        } else {
            logger.error("Request to animate region, but region does not contain an animation.")
        }
    }

    private fun reverse(hit: Hit) {
        if (hit.region() !is ImageRegion) {
            logger.error("A reversal was requested on a non-image region.")
            return
        }

        val key = RegionKey(hit.targetId(), hit.region().index())
        if (targets.animations.isAnimated(key)) {
            targets.animations.reverse(key)
        } else {
            logger.error("A reversal was requested on an image region that isn't animated.")
        }
    }

    private fun playSound(hit: Hit, args: List<String>) {
        // With a second argument, stay quiet if that image region has already fallen
        if (args.size == 2) {
            val named = named(hit, args[1])
            if (named is ImageRegion && !targets.animations.isOnFirstFrame(RegionKey(hit.targetId(), named.index()))) return
        }

        val sound = args.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return
        if (sound[0] != '@') {
            sounds.play(sound)
            return
        }

        // A sound in the running exercise's jar
        val loader = exerciseResources()
        val stream = loader?.getResourceAsStream(ExercisePaths.resourceName(sound))
        if (stream == null) {
            logger.error("Can't play {} because it is a resource in an exercise but no exercise is loaded.", sound)
            return
        }
        sounds.play(BufferedInputStream(stream))
    }

    private fun poiAdjust(shot: ScaledShot, hit: Hit) {
        val region = hit.region() as? RectangleRegion ?: return
        val target = targets.set.get(hit.targetId()).orElse(null) ?: return

        // The region's center on this surface, then on the camera feed, as the JavaFX app measures it:
        // the target's position plus the region's own center, before the target's scale
        val position = target.position
        val center = toCamera(Point(position.x + region.x() + region.width() / 2.0, position.y + region.y() + region.height() / 2.0))

        val offset = PoiAdjustment.offset(center, Point(shot.boundsX, shot.boundsY), target.scaleX, target.scaleY)
        PoiAdjustment.apply(settings, offset)
    }

    // A region of the hit target with this name tag
    private fun named(hit: Hit, name: String): Region? {
        val target = targets.set.get(hit.targetId()).orElse(null) ?: return null
        return target.definition.regions().firstOrNull { it.tag("name").orElse(null) == name }
    }
}
