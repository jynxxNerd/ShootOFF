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
import com.shootoff.compose.feed.FeedShots
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.Settings
import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.shots.ShotPipeline
import com.shootoff.shots.ShotTimer
import com.shootoff.targets.model.Hit
import com.shootoff.targets.model.HitTester
import com.shootoff.targets.model.TargetSet
import java.util.Optional

/**
 * The projector arena as the shot pipeline sees it: its targets, its markers and its size. Shots reach it
 * from the calibrated camera feed, already in arena coordinates, and have no timer row of their own (the
 * feed added it). The Compose app has one arena model, so no shot here is ever a mirrored copy.
 */
class ArenaSurface(
    private val settings: Settings,
    val targets: SurfaceTargets,
    val markers: ShotMarkers,
    private val size: () -> Size,
    private val receiver: () -> ShotReceiver,
    private val commands: () -> RegionCommandRunner,
) : ShotPipeline.Surface<ScaledShot> {
    val pipeline = ShotPipeline(this, settings)

    fun arenaSize(): Size = size()

    override fun name(): String = "arena"

    override fun targets(): TargetSet = targets.set

    override fun hitTest(x: Double, y: Double): Optional<Hit> = HitTester.hit(targets.set, x, y)

    override fun shotTimer(): Optional<ShotTimer<ScaledShot>> = Optional.empty()

    override fun show(shot: ScaledShot) {
        markers.add(shot.x, shot.y, shot.color, settings.markerRadius)
    }

    override fun markerRadius(shot: ScaledShot): Int = settings.markerRadius

    override fun runRegionCommands(shot: ScaledShot, hit: Hit, mirrored: Boolean) = commands().run(shot, hit, mirrored)

    override fun deliver(shot: ScaledShot, hit: Optional<Hit>, arenaShot: Boolean): Boolean =
        receiver().deliver(shot, hit.orElse(null), arenaShot)

    override fun arena(): Optional<ShotPipeline.Arena<ScaledShot>> = Optional.empty()
}

/**
 * The arena as a camera feed passes shots on to it: shots inside the calibrated projection (on the feed's
 * canvas) go to the arena in arena coordinates.
 */
class ArenaLink(private val arena: ArenaSurface, private val projection: () -> Rect?) : ShotPipeline.Arena<ScaledShot> {
    override fun projection(): Optional<Rect> = Optional.ofNullable(projection.invoke())

    override fun size(): Size = arena.arenaSize()

    override fun toArenaShot(shot: ScaledShot, arenaPoint: Point): ScaledShot = ArenaPointShot(shot, arenaPoint.x, arenaPoint.y)

    override fun addArenaShot(shot: ScaledShot, videoString: Optional<String>, mirrored: Boolean): Boolean =
        arena.pipeline.addArenaShot(shot, videoString, mirrored)
}

/**
 * A camera feed as the shot pipeline sees it: its targets, the shot timer, its markers, and the arena its
 * shots may go on to. Its camera hands it detected shots on the shot queue's thread ([FeedShots]).
 */
class FeedSurface(
    private val name: String,
    private val settings: Settings,
    val targets: SurfaceTargets,
    val timer: ShotTimerModel,
    val markers: ShotMarkers,
    private val receiver: () -> ShotReceiver,
    private val commands: () -> RegionCommandRunner,
    private val openArena: () -> ArenaSurface?,
    private val projection: () -> Rect?,
) : ShotPipeline.Surface<ScaledShot>, FeedShots {
    val pipeline = ShotPipeline(this, settings)

    // ---- From the camera

    override fun add(shot: ScaledShot) = pipeline.addShot(shot, false)

    /** Clears the feed's markers and the shot timer, and the arena's markers, as the JavaFX app does. */
    override fun clear() {
        markers.clear()
        timer.clear()
        openArena()?.markers?.clear()
    }

    /** Puts the feed's targets' animations back to their first frames, and the arena's, then clears. */
    override fun reset() {
        targets.animations.resetAll()
        openArena()?.targets?.animations?.resetAll()
        clear()
    }

    // ---- The pipeline's view of the feed

    override fun name(): String = name

    override fun targets(): TargetSet = targets.set

    override fun hitTest(x: Double, y: Double): Optional<Hit> = HitTester.hit(targets.set, x, y)

    override fun shotTimer(): Optional<ShotTimer<ScaledShot>> = Optional.of(timer)

    override fun show(shot: ScaledShot) {
        markers.add(shot.x, shot.y, shot.color, settings.markerRadius)
    }

    override fun markerRadius(shot: ScaledShot): Int = settings.markerRadius

    override fun runRegionCommands(shot: ScaledShot, hit: Hit, mirrored: Boolean) = commands().run(shot, hit, mirrored)

    override fun deliver(shot: ScaledShot, hit: Optional<Hit>, arenaShot: Boolean): Boolean =
        receiver().deliver(shot, hit.orElse(null), arenaShot)

    /** While the arena is open, shots inside its calibrated projection go on to it */
    override fun arena(): Optional<ShotPipeline.Arena<ScaledShot>> =
        Optional.ofNullable(openArena()?.let { ArenaLink(it, projection) })
}
