package com.shootoff.compose.courses

import com.shootoff.compose.arena.ArenaLayout
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.courses.Course
import com.shootoff.courses.CourseBackground
import com.shootoff.courses.CourseTarget
import com.shootoff.courses.io.CourseIO
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.Optional

class TestCourseLoader {
    private val home = File(System.getProperty("user.dir"))
    private val loader = CourseLoader(home)
    private val layout = ArenaLayout(ManualClock()).apply { setSize(Size(1280.0, 720.0)) }

    private fun shooters() = layout.targets.targetsOf(TargetOwner.USER)

    private fun add(file: String, x: Double, y: Double, owner: TargetOwner = TargetOwner.USER) =
        layout.targets.add(TargetDefinitions.load(File(home, file).toPath()), ResourceResolver.files(), Placement(x, y, 1.0, 1.0, true), owner)

    private fun assertBounds(expected: Rect, actual: Rect, tolerance: Double = 0.01) {
        val close = listOf(
            expected.minX to actual.minX,
            expected.minY to actual.minY,
            expected.width to actual.width,
            expected.height to actual.height,
        ).all { (e, a) -> Math.abs(e - a) < tolerance }
        assertTrue(close, "expected $expected but was $actual")
    }

    // A course's x and y are the target's Placement position (JavaFX's layoutX/layoutY), not its bounds' corner.
    // These are the bounds the JavaFX app's ProjectorArenaPane.setCourse gave accelerator.course (saved at
    // 640x483) on a 1280x720 arena, printed from a JavaFX test at this plan's commit.
    @Test
    fun aBundledSteelChallengeCourseLandsWhereTheJavaFxAppPutIt() {
        val course = CourseIO.loadCourse(File(home, "courses/steel_challenge/accelerator.course")).get()

        val applied = loader.apply(course, layout)

        assertEquals(CourseApplied(emptyList(), null), applied)
        val bounds = shooters().map { it.bounds }
        val javaFx = listOf(
            Rect(316.24, 365.22, 103.51, 274.29),
            Rect(1144.36, 52.92, 79.27, 159.50),
            Rect(623.0, 238.51, 148.0, 211.68),
            Rect(929.44, 60.37, 107.13, 156.52),
            Rect(60.34, 411.43, 149.31, 229.57),
        )
        assertEquals(javaFx.size, bounds.size)
        for ((expected, actual) in javaFx.zip(bounds)) assertBounds(expected, actual)
        // Its first target's position, as JavaFX gave it: well off the target's bounds
        assertEquals(254.0, shooters()[0].placement.x(), 0.01)
        assertEquals(280.36, shooters()[0].placement.y(), 0.01)
    }

    @Test
    fun everyBundledCourseLandsWhollyOnTheOwnersArena() {
        val folder = File(home, "courses/steel_challenge")
        val files = folder.listFiles { f -> f.name.endsWith(".course") }!!.sorted()
        assertEquals(8, files.size)
        for (file in files) {
            loader.apply(CourseIO.loadCourse(file).get(), layout)
            for (target in shooters()) {
                val b = target.bounds
                assertTrue(b.minX >= 0 && b.minY >= 0 && b.maxX <= 1280 && b.maxY <= 720, "${file.name}: $b")
            }
        }
    }

    @Test
    fun aCourseSavedAtTheArenasSizeKeepsEachTargetsPositionAndSize() {
        val course = Course(
            Optional.empty(),
            listOf(CourseTarget(File("targets/IPSC.target"), 200.0, 50.0, 90.0, 114.0)),
            Optional.of(Size(1280.0, 720.0)),
        )

        loader.apply(course, layout)

        val target = shooters().single()
        assertEquals(200.0, target.placement.x(), 0.001)
        assertEquals(50.0, target.placement.y(), 0.001)
        assertEquals(90.0, target.bounds.width, 0.001)
        assertEquals(114.0, target.bounds.height, 0.001)
    }

    @Test
    fun whatIsSavedLoadsBackAsItWas(@TempDir temp: Path) {
        val ipsc = add("targets/IPSC.target", 100.0, 80.0)
        layout.targets.set.resize(ipsc.id, 45.0, 57.0)
        add("targets/Reset.target", 900.0, 600.0)
        val source = CourseBackground("/arena/backgrounds/indoor_range.gif", true)
        layout.setBackground(loader.readBackground(source))
        val before = shooters().map { it.bounds }
        val file = temp.resolve("mine.course").toFile()

        assertTrue(loader.save(loader.course(layout), file))
        val again = ArenaLayout(ManualClock()).apply { setSize(Size(1280.0, 720.0)) }
        loader.apply(CourseIO.loadCourse(file).get(), again)

        for ((expected, target) in before.zip(again.targets.targetsOf(TargetOwner.USER))) assertBounds(expected, target.bounds, 0.001)
        assertEquals(source, again.background.value!!.source)
        // Named as the JavaFX app named them, relative to ShootOFF's folder
        assertEquals(listOf(File("targets/IPSC.target"), File("targets/Reset.target")), CourseIO.loadCourse(file).get().targets.map { it.file() })
        assertEquals(Optional.of(Size(1280.0, 720.0)), CourseIO.loadCourse(file).get().resolution)
    }

    @Test
    fun aMissingTargetFileIsSkippedAndNamedAndTheRestLoad() {
        val course = Course(
            Optional.empty(),
            listOf(
                CourseTarget(File("targets/no_such_target.target"), 0.0, 0.0, 10.0, 10.0),
                CourseTarget(File("targets/Reset.target"), 10.0, 100.0, 10.0, 1.0),
            ),
            Optional.of(Size(1280.0, 720.0)),
        )

        val applied = loader.apply(course, layout)

        assertEquals(listOf("targets/no_such_target.target"), applied.missing)
        assertEquals(1, shooters().size)
    }

    @Test
    fun anExercisesTargetsAreLeftOutOfACourseAndLeftAloneByALoad() {
        add("targets/Reset.target", 10.0, 10.0)
        val exercises = add("targets/IPSC.target", 300.0, 300.0, TargetOwner.EXERCISE)

        assertEquals(listOf(File("targets/Reset.target")), loader.course(layout).targets.map { it.file() })

        loader.apply(Course(Optional.empty(), listOf(CourseTarget(File("targets/IPSC.target"), 0.0, 0.0, 90.0, 114.0)), Optional.empty()), layout)

        assertEquals(listOf(exercises.id), layout.targets.targetsOf(TargetOwner.EXERCISE).map { it.id })
        assertEquals(Placement(300.0, 300.0, 1.0, 1.0, true), layout.targets.set.get(exercises.id).get().placement)
        assertEquals(listOf("targets/IPSC.target"), shooters().map { home.toPath().relativize(it.definition.file().get().toPath()).toString() })
    }

    @Test
    fun aCourseWithoutAReadableBackgroundKeepsTheCurrentOneAndOneItCantReadIsNamed() {
        val current = loader.readBackground(CourseBackground("/arena/backgrounds/indoor_range.gif", true))
        layout.setBackground(current)

        loader.apply(Course(Optional.empty(), emptyList(), Optional.empty()), layout)
        assertSame(current, layout.background.value)

        val applied = loader.apply(Course(Optional.of(CourseBackground("/arena/backgrounds/no_such.gif", true)), emptyList(), Optional.empty()), layout)
        assertSame(current, layout.background.value)
        assertEquals("/arena/backgrounds/no_such.gif", applied.backgroundMissing)

        loader.apply(Course(Optional.of(CourseBackground("/arena/backgrounds/outdoor_range.gif", true)), emptyList(), Optional.empty()), layout)
        assertEquals("/arena/backgrounds/outdoor_range.gif", layout.background.value?.source?.url())
    }

    @Test
    fun aBackgroundFileIsReadFromItsUrl(@TempDir temp: Path) {
        val file = temp.resolve("range.png").toFile()
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", file)
        val source = CourseBackground(file.toURI().toString(), false)

        val background = loader.readBackground(source)!!

        assertEquals(source, background.source)
        assertEquals(4, background.image.width)
    }

    @Test
    fun aCourseThatCantBeWrittenLeavesTheOldFileAlone(@TempDir temp: Path) {
        add("targets/Reset.target", 10.0, 10.0)
        val file = temp.resolve("mine.course").toFile()
        file.writeText("old")

        assertFalse(loader.save(loader.course(layout), temp.resolve("no_such_folder/mine.course").toFile()))
        assertEquals("old", file.readText())
        assertTrue(loader.save(loader.course(layout), file))
        assertTrue(CourseIO.loadCourse(file).isPresent)
        assertFalse(File(temp.toFile(), ".mine.course").exists())
    }
}
