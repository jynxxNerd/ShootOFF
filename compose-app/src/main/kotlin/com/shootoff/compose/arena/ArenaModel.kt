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

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.shootoff.camera.perspective.PerspectiveManager
import com.shootoff.compose.shots.ArenaSurface
import com.shootoff.compose.shots.RegionCommandRunner
import com.shootoff.compose.shots.ShotMarkers
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.compose.targets.AnimationClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.PlacedTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.io.InputStream
import javax.imageio.ImageIO

/**
 * An arena background.
 *
 * @param name where it came from (a resource name), for logs
 */
class ArenaBackground(val image: ImageBitmap, val name: String) {
    companion object {
        fun read(stream: InputStream, name: String): ArenaBackground? =
            stream.use { ImageIO.read(it) }?.let { ArenaBackground(it.toComposeImageBitmap(), name) }
    }
}

/**
 * The projector arena: one model that both the projector window and Setup's preview draw, so
 * whatever an exercise does to it (a target hidden, a background set) shows on both.
 */
class ArenaModel(
    private val settings: Settings,
    receiver: () -> ShotReceiver,
    commands: () -> RegionCommandRunner,
    clock: AnimationClock = AnimationClock.background,
) {
    private val logger = LoggerFactory.getLogger(ArenaModel::class.java)

    val targets = SurfaceTargets(clock = clock)
    val markers = ShotMarkers().also { it.setVisible(settings.showArenaShotMarkers()) }

    private val sizeState = MutableStateFlow(Size(640.0, 480.0))
    private val backgroundState = MutableStateFlow<ArenaBackground?>(null)
    private val projectionState = MutableStateFlow<Rect?>(null)
    private val fullScreenState = MutableStateFlow(false)
    private val labelState = MutableStateFlow(true)
    private val gridState = MutableStateFlow(false)

    /** The arena window's size, in dp: the arena's coordinates */
    val size: StateFlow<Size> = sizeState.asStateFlow()

    val background: StateFlow<ArenaBackground?> = backgroundState.asStateFlow()

    /** The arena's projection on the calibrating camera feed's canvas; null until calibrated */
    val projection: StateFlow<Rect?> = projectionState.asStateFlow()

    val fullScreen: StateFlow<Boolean> = fullScreenState.asStateFlow()

    /** Whether the arena says "Needs calibration" (until it is first calibrated) */
    val needsCalibrationLabel: StateFlow<Boolean> = labelState.asStateFlow()

    /** Whether the arena shows Setup's alignment grid in place of everything else */
    val grid: StateFlow<Boolean> = gridState.asStateFlow()

    @Volatile
    var perspective: PerspectiveManager? = null

    val surface = ArenaSurface(settings, targets, markers, { sizeState.value }, receiver, commands)

    fun setSize(size: Size) {
        sizeState.value = size
    }

    fun setFullScreen(fullScreen: Boolean) {
        fullScreenState.value = fullScreen
    }

    fun setBackground(background: ArenaBackground?) {
        backgroundState.value = background
    }

    /**
     * Shows one of ShootOFF's own images as the background (the calibration pattern, the exposure step's
     * white screen), or none for null.
     */
    fun showResource(name: String?) {
        if (name == null) {
            setBackground(null)
            return
        }
        val stream = ArenaModel::class.java.getResourceAsStream("/" + name.removePrefix("/"))
        if (stream == null) {
            logger.error("ShootOFF has no image {}", name)
            return
        }
        setBackground(ArenaBackground.read(stream, name))
    }

    fun setProjection(projection: Rect?) {
        projectionState.value = projection
    }

    fun setCalibrationLabelVisible(visible: Boolean) {
        labelState.value = visible
    }

    fun showGrid(show: Boolean) {
        gridState.value = show
    }

    /** Shows or hides every arena target, as calibration does. */
    fun setTargetsVisible(visible: Boolean) {
        for (target in targets.set.targets) targets.set.setVisible(target.id, visible)
    }

    fun showShots(visible: Boolean) = markers.setVisible(visible)

    /**
     * Fits a target that just joined the arena, as the JavaFX arena does: to its real-world size once the
     * perspective is known, and over the whole arena if it asks to fill it.
     */
    fun placeNewTarget(target: PlacedTarget) {
        val perspective = perspective
        val perception = target.definition.defaultPerception()
        if (perspective != null && perspective.isInitialized && perception.isPresent) {
            val real = perception.get()
            perspective.calculateObjectSize(real.width().toDouble(), real.height().toDouble(), real.distance().toDouble())
                .ifPresent { targets.set.resize(target.id, it.width, it.height) }
        }

        if (target.definition.fillsCanvas()) {
            val arena = sizeState.value
            targets.set.resize(target.id, arena.width, arena.height)
            val origin = targets.set.get(target.id).map { it.localToParent(0.0, 0.0) }.orElse(null) ?: return
            targets.set.move(target.id, -origin.x, -origin.y)
        }
    }
}
