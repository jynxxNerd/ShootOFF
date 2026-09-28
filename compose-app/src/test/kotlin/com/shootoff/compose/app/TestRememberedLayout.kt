package com.shootoff.compose.app

import com.shootoff.compose.courses.LayoutMemory
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.courses.io.CourseIO
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinitions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// Spec §5: the arena's layout is remembered in arena-layout.course and comes back at launch, once the arena is open
class TestRememberedLayout {
    @TempDir
    lateinit var temp: Path

    private val home = File(System.getProperty("user.dir"))
    private var app: AppState? = null

    @AfterEach
    fun close() {
        app?.close()
    }

    private fun layoutFile() = temp.resolve("arena-layout.course").toFile()

    private fun app(screens: List<Rect> = AppFixture.ownerScreens): AppState = AppState(
        Settings(ScratchConfig.emptyFile().path, arrayOf()),
        ExerciseCatalog(),
        CameraSource.None,
        { screens },
        ManualClock(),
        { it.run() },
        arenaFiles = ArenaFiles(home, File(home, "targets"), temp.resolve("courses").toFile(), layoutFile()),
    ).also { app = it }

    // A layout saved on the owner's 1280x720 arena, with one Reset target at (100, 200)
    private fun remember() {
        layoutFile().writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <course>
            	<target file="targets/Reset.target" x="100.000000" y="200.000000" width="50.000000" height="50.000000" />
            	<resolution width="1280.000000" height="720.000000" />
            </course>
            """.trimIndent(),
        )
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("never: $what")
            Thread.sleep(5)
        }
    }

    private fun shooters(app: AppState) = app.arenaLayout.targets.targetsOf(TargetOwner.USER)

    @Test
    fun theRememberedLayoutComesBackOnceTheArenaFillsTheProjector() {
        remember()
        val app = app()

        app.openArena()
        // Still the window's first size, on its way to the projector
        Thread.sleep(100)
        assertTrue(shooters(app).isEmpty())

        AppFixture.putOnTheProjector(app)

        awaitTrue("the layout is back") { shooters(app).size == 1 }
        assertEquals(Placement(100.0, 200.0, 1.0, 1.0, true), shooters(app).single().placement)
    }

    @Test
    fun theArenaFillingTheProjectorDoesNotOverwriteTheFileBeforeItIsRestored() {
        remember()
        val before = layoutFile().readText()
        val app = app()

        app.openArena()
        AppFixture.putOnTheProjector(app)
        awaitTrue("the layout is back") { shooters(app).size == 1 }
        // Longer than the quiet time a save would wait out
        Thread.sleep(LayoutMemory.QUIET_MILLIS + 300)

        assertEquals(before, layoutFile().readText())
    }

    @Test
    fun withoutAProjectorScreenTheLayoutComesBackAsTheArenaOpens() {
        remember()
        val app = app(listOf(Rect(0.0, 0.0, 1920.0, 1080.0)))
        app.arenaLayout.setSize(Size(1280.0, 720.0))

        app.openArena()

        awaitTrue("the layout is back") { shooters(app).size == 1 }
    }

    @Test
    fun theLayoutComesBackOnlyOnceASession() {
        remember()
        val app = app()
        app.openArena()
        AppFixture.putOnTheProjector(app)
        awaitTrue("the layout is back") { shooters(app).size == 1 }
        app.targetsModel.clear()

        app.closeArena()
        app.openArena()
        AppFixture.putOnTheProjector(app)
        Thread.sleep(100)

        assertTrue(shooters(app).isEmpty())
    }

    @Test
    fun aChangeIsRememberedEvenIfTheAppClosesAtOnce() {
        val app = app()
        app.arenaLayout.setSize(Size(1280.0, 720.0))
        app.arenaLayout.targets.add(TargetDefinitions.load(File(home, "targets/Reset.target").toPath()), ResourceResolver.files(), Placement(10.0, 20.0, 1.0, 1.0, true))

        app.close()
        this.app = null

        assertEquals(listOf(10.0 to 20.0), CourseIO.loadCourse(layoutFile()).get().targets.map { it.x() to it.y() })
    }

    @Test
    fun theDefaultAppRemembersNothing() {
        assertNull(ArenaFiles.scratch().layout)
    }
}
