package com.shootoff.compose.shots

import com.shootoff.camera.Shot
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.Hit
import java.util.concurrent.CopyOnWriteArrayList

/** A camera feed and the arena wired as the Compose app wires them, with a recording exercise. */
class SurfaceFixture {
    data class Delivered(val shot: Shot, val hit: Hit?, val arenaShot: Boolean)

    val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    val clock = ManualClock()
    val delivered = CopyOnWriteArrayList<Delivered>()
    val resets = CopyOnWriteArrayList<String>()
    var arenaOpen = true
    var projection: Rect? = null

    val arena: ArenaSurface = ArenaSurface(
        settings,
        SurfaceTargets(clock = clock),
        ShotMarkers(),
        { Size(1280.0, 720.0) },
        { receiver },
        { arenaCommands },
    )

    val feed: FeedSurface = FeedSurface(
        "C270",
        settings,
        SurfaceTargets(clock = clock),
        ShotTimerModel(),
        ShotMarkers(),
        { receiver },
        { feedCommands },
        { if (arenaOpen) arena else null },
        { projection },
    )

    private val receiver: ShotReceiver = ShotReceiver { shot, hit, arenaShot ->
        delivered += Delivered(shot, hit, arenaShot)
        true
    }

    private val feedCommands: RegionCommandRunner = RegionCommandRunner(feed.targets, settings, { resets += "reset" }, { null })
    private val arenaCommands: RegionCommandRunner = RegionCommandRunner(arena.targets, settings, { resets += "reset" }, { null })
}
