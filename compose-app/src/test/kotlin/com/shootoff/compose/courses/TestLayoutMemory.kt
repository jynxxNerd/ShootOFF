package com.shootoff.compose.courses

import com.shootoff.calibration.CalibrationFlow
import com.shootoff.compose.arena.ArenaLayout
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.courses.CourseBackground
import com.shootoff.courses.io.CourseIO
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

class TestLayoutMemory {
    /** Timers that run only when told to */
    private class ManualTimers : CalibrationFlow.Scheduler {
        val scheduled = mutableListOf<Pair<Long, FutureTask<Unit>>>()

        override fun schedule(task: Runnable, delayMillis: Long): Future<*> = FutureTask(task, Unit).also { scheduled += delayMillis to it }

        fun live() = scheduled.filterNot { it.second.isCancelled }

        fun runLive() = live().forEach { it.second.run() }
    }

    @TempDir
    lateinit var temp: Path

    private val home = File(System.getProperty("user.dir"))
    private val loader = CourseLoader(home)
    private val timers = ManualTimers()

    private fun layout() = ArenaLayout(ManualClock()).apply { setSize(Size(1280.0, 720.0)) }

    private fun file() = temp.resolve(LayoutMemory.FILE_NAME).toFile()

    private fun memory(layout: ArenaLayout) = LayoutMemory(layout, loader, file(), timers).also { it.start() }

    private fun add(layout: ArenaLayout, owner: TargetOwner = TargetOwner.USER) = layout.targets.add(
        TargetDefinitions.load(File(home, "targets/Reset.target").toPath()),
        ResourceResolver.files(),
        Placement(10.0, 20.0, 1.0, 1.0, true),
        owner,
    )

    @Test
    fun theLayoutIsSavedOnceTheChangesStopNotOncePerChange() {
        val layout = layout()
        memory(layout)

        val target = add(layout)
        layout.targets.set.move(target.id, 30.0, 40.0)
        layout.targets.set.move(target.id, 50.0, 60.0)

        assertEquals(listOf(500L, 500L, 500L), timers.scheduled.map { it.first })
        assertEquals(1, timers.live().size)
        assertFalse(file().exists())

        timers.runLive()

        val saved = CourseIO.loadCourse(file()).get()
        assertEquals(listOf(50.0 to 60.0), saved.targets.map { it.x() to it.y() })
        assertEquals(Size(1280.0, 720.0), saved.resolution.get())
    }

    @Test
    fun aNewBackgroundIsSaved() {
        val layout = layout()
        memory(layout)
        val source = CourseBackground("/arena/backgrounds/indoor_range.gif", true)

        layout.setBackground(loader.readBackground(source))
        timers.runLive()

        assertEquals(source, CourseIO.loadCourse(file()).get().background.get())
    }

    @Test
    fun anExercisesTargetsComingAndMovingAreNoReasonToSave() {
        val layout = layout()
        memory(layout)

        val target = add(layout, TargetOwner.EXERCISE)
        layout.targets.set.move(target.id, 30.0, 40.0)

        assertTrue(timers.scheduled.isEmpty())
    }

    @Test
    fun theRememberedLayoutComesBackAndBringingItBackIsNoChange() {
        val first = layout()
        memory(first)
        add(first)
        first.setBackground(loader.readBackground(CourseBackground("/arena/backgrounds/indoor_range.gif", true)))
        timers.runLive()
        timers.scheduled.clear()

        val next = layout()
        val memory = memory(next)
        assertTrue(memory.restore())

        assertEquals(listOf(Placement(10.0, 20.0, 1.0, 1.0, true)), next.targets.targetsOf(TargetOwner.USER).map { it.placement })
        assertEquals("/arena/backgrounds/indoor_range.gif", next.background.value!!.source!!.url())
        assertTrue(timers.scheduled.isEmpty())
        assertFalse(memory.changedThisSession)
    }

    @Test
    fun withoutARememberedLayoutTheArenaStartsEmpty() {
        val layout = layout()

        assertFalse(memory(layout).restore())

        assertTrue(layout.targets.set.targets.isEmpty())
        assertNull(layout.background.value)
    }

    @Test
    fun aRememberedLayoutThatCantBeReadLeavesTheArenaEmpty() {
        file().writeText("<course><target file=")
        val layout = layout()

        assertFalse(memory(layout).restore())

        assertTrue(layout.targets.set.targets.isEmpty())
    }

    @Test
    fun aTargetTheRememberedLayoutNamesThatIsGoneIsLeftOut() {
        file().writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <course>
            	<target file="targets/no_such_target.target" x="0" y="0" width="10" height="10" />
            	<target file="targets/Reset.target" x="10" y="20" width="10" height="1" />
            	<resolution width="1280" height="720" />
            </course>
            """.trimIndent(),
        )
        val layout = layout()

        assertTrue(memory(layout).restore())

        assertEquals(1, layout.targets.set.targets.size)
    }

    @Test
    fun onceTheShooterHasChangedTheLayoutTheRememberedOneIsLeftAlone() {
        val first = layout()
        memory(first)
        add(first)
        timers.runLive()

        val next = layout()
        val memory = memory(next)
        next.setBackground(null)

        assertFalse(memory.restore())
        assertTrue(next.targets.set.targets.isEmpty())
        assertTrue(memory.changedThisSession)
    }
}
