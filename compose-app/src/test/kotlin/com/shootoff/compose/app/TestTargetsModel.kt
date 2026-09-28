package com.shootoff.compose.app

import com.shootoff.compose.arena.ArenaLayout
import com.shootoff.compose.feed.BannerKind
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.courses.CourseBackground
import com.shootoff.courses.io.CourseIO
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinitions
import com.shootoff.targets.model.TargetSetListener
import com.shootoff.targets.model.PlacedTarget
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import javax.imageio.ImageIO

class TestTargetsModel {
    @TempDir
    lateinit var temp: Path

    private val home = File(System.getProperty("user.dir"))
    private val layout = ArenaLayout(ManualClock()).apply { setSize(Size(1280.0, 720.0)) }
    private val feed = SurfaceTargets(clock = ManualClock())
    private var picked: File? = null

    // Everything runs at once, on the calling thread
    private val model by lazy {
        TargetsModel(
            layout,
            feed,
            Size(640.0, 480.0),
            ArenaFiles(home, File(temp.toFile(), "targets").apply { mkdirs() }, File(temp.toFile(), "courses"), null),
            { picked },
            CoroutineScope(Dispatchers.Unconfined),
            Dispatchers.Unconfined,
        )
    }

    private fun targetFile(name: String, xml: String = BOX): TargetChoice {
        val file = File(temp.toFile(), "targets/$name.target")
        file.writeText(xml)
        return TargetChoice(file, name)
    }

    private fun shooters(targets: SurfaceTargets = layout.targets) = targets.targetsOf(TargetOwner.USER)

    private fun reset() = layout.targets.add(TargetDefinitions.load(File(home, "targets/Reset.target").toPath()), ResourceResolver.files(), Placement(5.0, 6.0, 1.0, 1.0, true))

    companion object {
        const val BOX = """<target><rectangle x="0" y="0" width="100" height="50" fill="red"/></target>"""
    }

    @Test
    fun anAddedTargetGoesInTheMiddleOfTheArenaAtItsOwnSizeSelected() {
        model.addTarget(targetFile("box"))

        val target = shooters().single()
        assertEquals(Rect(590.0, 335.0, 100.0, 50.0), target.bounds)
        assertEquals(target.id, model.arenaEditor.selected.value)
    }

    @Test
    fun onTheCameraATargetGoesInTheMiddleOfTheFeed() {
        model.show(EditedSurface.CAMERA)

        model.addTarget(targetFile("box"))

        assertTrue(layout.targets.set.targets.isEmpty())
        val target = shooters(feed).single()
        assertEquals(Rect(270.0, 215.0, 100.0, 50.0), target.bounds)
        assertEquals(target.id, model.cameraEditor.selected.value)
    }

    @Test
    fun aTargetThatAsksToFillTheCanvasFillsTheArena() {
        model.addTarget(targetFile("poi", """<target fillCanvas="true"><rectangle x="10" y="10" width="100" height="50" fill="red"/></target>"""))

        assertEquals(Rect(0.0, 0.0, 1280.0, 720.0), shooters().single().bounds)
    }

    @Test
    fun aTargetFileThatCantBeReadIsNamedAndNothingIsAdded() {
        model.addTarget(targetFile("broken", "<target><rectangle"))

        assertTrue(layout.targets.set.targets.isEmpty())
        assertEquals(BannerKind.ERROR, model.message.value!!.kind)
        assertTrue(model.message.value!!.text.startsWith("Couldn't load the target broken"), model.message.value!!.text)
    }

    @Test
    fun addTargetOffersTheTargetFilesInTheTargetsFolderByName() {
        val folder = ArenaFiles.inHome(home)

        val choices = folder.targetChoices()

        assertTrue(choices.any { it.name == "Steel Challenge Circle" && it.file == File(home, "targets/Steel_Challenge_Circle.target") })
        assertEquals(choices.map { it.name.lowercase() }.sorted(), choices.map { it.name.lowercase() })
        // Not the ones in its subfolders
        assertFalse(choices.any { it.file.parentFile != File(home, "targets") })
    }

    @Test
    fun loadCourseOffersTheCoursesInTheCoursesFolderAndItsSubfolders() {
        val courses = File(temp.toFile(), "courses/steel_challenge").apply { mkdirs() }
        File(courses, "five_to_go.course").writeText("")
        File(courses.parentFile, "mine.course").writeText("")
        File(courses.parentFile, ".arena-layout.course").writeText("")
        File(courses.parentFile, ".x.course").writeText("")

        val choices = model.files.courseChoices()

        assertEquals(listOf("" to "mine", "steel challenge" to "five to go"), choices.map { it.group to it.name })
    }

    @Test
    fun aBundledBackgroundGoesUpAndNoneTakesItDown() {
        model.useBackground(BUNDLED_BACKGROUNDS.first { it.name == "Indoor Range" })
        assertEquals(CourseBackground("/arena/backgrounds/indoor_range.gif", true), layout.background.value!!.source)

        model.useBackground(null)
        assertNull(layout.background.value)
    }

    @Test
    fun everyBundledBackgroundCanBeRead() {
        for (background in BUNDLED_BACKGROUNDS) {
            assertTrue(model.loader.readBackground(CourseBackground(background.resource, true)) != null, background.name)
        }
    }

    @Test
    fun anImageFileTheShooterPicksGoesUpAndCancellingChangesNothing() {
        val file = temp.resolve("range.png").toFile()
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", file)
        picked = file

        model.pickBackground()
        assertEquals(CourseBackground(file.toURI().toString(), false), layout.background.value!!.source)

        picked = null
        model.pickBackground()
        assertEquals(CourseBackground(file.toURI().toString(), false), layout.background.value!!.source)
    }

    @Test
    fun aPickedFileThatIsntAnImageIsNamed() {
        picked = temp.resolve("notes.png").toFile().apply { writeText("not an image") }

        model.pickBackground()

        assertNull(layout.background.value)
        assertEquals("Couldn't read the background notes.png.", model.message.value!!.text)
    }

    @Test
    fun aCourseLoadsAndItsMissingTargetFilesAreNamed() {
        val file = File(temp.toFile(), "courses/mine.course").apply { parentFile.mkdirs() }
        file.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <course>
            	<target file="targets/no_such_target.target" x="0" y="0" width="10" height="10" />
            	<target file="targets/Reset.target" x="10" y="20" width="10" height="1" />
            	<resolution width="1280" height="720" />
            </course>
            """.trimIndent(),
        )

        model.loadCourse(CourseChoice(file, "mine", ""))

        assertEquals(1, shooters().size)
        assertEquals(BannerKind.WARNING, model.message.value!!.kind)
        assertEquals("Loaded mine, but these target files are missing, so they were left out: targets/no_such_target.target.", model.message.value!!.text)
    }

    @Test
    fun aCourseThatCantBeReadLeavesTheArenaAsItWas() {
        val kept = reset()
        val file = File(temp.toFile(), "courses/broken.course").apply { parentFile.mkdirs() }
        file.writeText("<course><target")

        model.loadCourse(CourseChoice(file, "broken", ""))

        assertEquals(listOf(kept.id), layout.targets.set.targets.map { it.id })
        assertEquals("Couldn't read the course broken; the arena is as it was.", model.message.value!!.text)
    }

    @Test
    fun aCourseIsSavedUnderItsNameAndAnExistingOneOnlyOnceTheShooterSaysSo() {
        reset()
        val file = File(temp.toFile(), "courses/Mine.course")

        assertEquals(SaveOutcome.BAD_NAME, model.saveCourse("  "))
        assertEquals(SaveOutcome.BAD_NAME, model.saveCourse("a/b"))
        assertEquals(SaveOutcome.SAVING, model.saveCourse(" Mine "))
        assertEquals("Saved the course Mine.", model.message.value!!.text)
        assertEquals(1, CourseIO.loadCourse(file).get().targets.size)

        reset()
        assertEquals(SaveOutcome.EXISTS, model.saveCourse("Mine"))
        assertEquals(1, CourseIO.loadCourse(file).get().targets.size)
        assertEquals(SaveOutcome.SAVING, model.saveCourse("Mine", replace = true))
        assertEquals(2, CourseIO.loadCourse(file).get().targets.size)
    }

    @Test
    fun clearTakesTheShootersTargetsAndBackgroundOffAndUndoPutsThemBack() {
        val shooters = reset()
        layout.targets.set.resize(shooters.id, 40.0, 4.0)
        val placement = layout.targets.set.get(shooters.id).get().placement
        val exercises = layout.targets.add(TargetDefinitions.load(File(home, "targets/Reset.target").toPath()), ResourceResolver.files(), owner = TargetOwner.EXERCISE)
        model.useBackground(BUNDLED_BACKGROUNDS.first())
        val background = layout.background.value

        model.clear()

        assertEquals(listOf(exercises.id), layout.targets.set.targets.map { it.id })
        assertNull(layout.background.value)
        assertTrue(model.undo.value != null)

        model.undoClear()

        assertEquals(listOf(placement), shooters().map { it.placement })
        assertEquals(background, layout.background.value)
        assertNull(model.undo.value)
    }

    @Test
    fun theUndoOfferEndsWithTheNextChange() {
        reset()
        model.clear()

        model.addTarget(targetFile("box"))

        assertNull(model.undo.value)
    }

    @Test
    fun clearOnTheCameraLeavesTheArenaAlone() {
        reset()
        model.show(EditedSurface.CAMERA)
        model.addTarget(targetFile("box"))

        model.clear()

        assertTrue(shooters(feed).isEmpty())
        assertEquals(1, shooters().size)
        model.undoClear()
        assertEquals(1, shooters(feed).size)
    }

    @Test
    fun clearingNothingOffersNoUndo() {
        model.clear()

        assertNull(model.undo.value)
    }

    // Everything waits in a queue until the test runs it, as the shooter's clicks would while file work is going on
    private class Queue : CoroutineDispatcher() {
        val waiting = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            waiting.addLast(block)
        }

        fun runAll() {
            while (waiting.isNotEmpty()) waiting.removeFirst().run()
        }
    }

    private fun queuedModel(queue: Queue) = TargetsModel(
        layout,
        feed,
        Size(640.0, 480.0),
        ArenaFiles(home, File(temp.toFile(), "targets").apply { mkdirs() }, File(temp.toFile(), "courses"), null),
        { picked },
        CoroutineScope(Dispatchers.Unconfined),
        queue,
    )

    private fun course(name: String, target: String): CourseChoice {
        val file = File(temp.toFile(), "courses/$name.course").apply { parentFile.mkdirs() }
        file.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <course>
            	<target file="targets/$target.target" x="10" y="20" width="10" height="1" />
            	<resolution width="1280" height="720" />
            </course>
            """.trimIndent(),
        )
        return CourseChoice(file, name, "")
    }

    @Test
    fun aClearPressedWhileACourseLoadsTakesTheLoadedTargetsAndUndoBringsThemBack() {
        reset()
        val queue = Queue()
        val queued = queuedModel(queue)

        queued.loadCourse(course("mine", "Reset"))
        queued.clear()
        queue.runAll()

        assertTrue(layout.targets.set.targets.isEmpty())
        queued.undoClear()
        queue.runAll()
        assertEquals(1, shooters().size)
        assertEquals(10.0, shooters().single().bounds.width, 0.001)
    }

    @Test
    fun twoLoadsInARowLeaveOnlyTheSecondCourse() {
        val queue = Queue()
        val queued = queuedModel(queue)

        queued.loadCourse(course("first", "Reset"))
        queued.loadCourse(course("second", "Steel_Challenge_Circle"))
        queue.runAll()

        assertEquals(1, shooters().size)
        assertEquals(1.0, shooters().single().bounds.height, 0.001)
    }

    @Test
    fun aBackgroundPickedBeforeAClearIsClearedToo() {
        val queue = Queue()
        val queued = queuedModel(queue)

        queued.useBackground(BUNDLED_BACKGROUNDS.first())
        queued.clear()
        queue.runAll()

        assertNull(layout.background.value)
        queued.undoClear()
        queue.runAll()
        assertEquals(CourseBackground(BUNDLED_BACKGROUNDS.first().resource, true), layout.background.value!!.source)
    }

    @Test
    fun aNameStartingWithADotIsRefusedAndACourseSuffixIsntDoubled() {
        assertEquals(SaveOutcome.BAD_NAME, model.saveCourse("."))
        assertEquals(SaveOutcome.BAD_NAME, model.saveCourse(".."))
        assertEquals(SaveOutcome.BAD_NAME, model.saveCourse(".hidden"))

        assertEquals(SaveOutcome.SAVING, model.saveCourse("Nice.COURSE"))

        assertTrue(File(temp.toFile(), "courses/Nice.course").exists())
        assertFalse(File(temp.toFile(), "courses/Nice.COURSE.course").exists())
        assertEquals("Saved the course Nice.", model.message.value!!.text)
    }

    @Test
    fun savingEndsTheUndoOffer() {
        reset()
        model.clear()

        model.saveCourse("Mine")

        assertNull(model.undo.value)
    }

    @Test
    fun aTargetIsAddedWhereItStaysWithoutBeingMovedAfterwards() {
        var changes = 0
        var added: PlacedTarget? = null
        layout.targets.set.addListener(object : TargetSetListener {
            override fun targetAdded(target: PlacedTarget) {
                added = target
            }

            override fun targetChanged(target: PlacedTarget) {
                changes++
            }
        })

        model.addTarget(targetFile("box"))

        assertEquals(0, changes)
        assertEquals(Rect(590.0, 335.0, 100.0, 50.0), added!!.bounds)
    }
}
