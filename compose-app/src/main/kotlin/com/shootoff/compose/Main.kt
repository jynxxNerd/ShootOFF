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

package com.shootoff.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.shootoff.compose.app.AppState
import com.shootoff.compose.app.ArenaFiles
import com.shootoff.compose.app.CameraSource
import com.shootoff.compose.app.ExerciseCatalog
import com.shootoff.compose.app.ImagePicker
import com.shootoff.compose.app.PrefsStore
import com.shootoff.compose.app.UiPrefs
import com.shootoff.compose.app.WindowBounds
import com.shootoff.compose.app.handleKey
import com.shootoff.compose.app.ShootOffApp
import com.shootoff.compose.app.UiErrors
import com.shootoff.compose.app.WindowRole
import com.shootoff.compose.arena.ArenaWindow
import com.shootoff.compose.drill.ExerciseOverlay
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.config.Settings
import com.shootoff.geom.Point
import com.shootoff.plugins.TextToSpeech
import com.shootoff.plugins.engine.PluginEngine
import com.shootoff.plugins.engine.V2ExerciseLoader
import com.shootoff.util.TimerPool
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.opencv_java
import java.io.File

/**
 * The Compose app. It shares the JavaFX app's folder: shootoff.properties, targets/, sounds/,
 * exercises/ and exercise-data/ in the working directory.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val home = System.getProperty("shootoff.home") ?: System.getProperty("user.dir").also { System.setProperty("shootoff.home", it) }
    System.setProperty("shootoff.sessions", home + File.separator + "sessions")
    System.setProperty("shootoff.courses", home + File.separator + "courses")
    System.setProperty("shootoff.plugins", home + File.separator + "exercises")

    Loader.load(opencv_java::class.java)

    val settings = Settings(home + File.separator + "shootoff.properties", arrayOf())
    // Speech synthesis starts slowly: warm it up in the background
    Thread({ TextToSpeech.say("") }, "Speech warm-up").apply { isDaemon = true }.start()

    val catalog = ExerciseCatalog()
    val plugins = PluginEngine(catalog, listOf(V2ExerciseLoader()), emptyList())
    plugins.startWatching()

    val app = AppState(
        settings,
        catalog,
        CameraSource.System,
        prefs = UiPrefs(PrefsStore.User()),
        // The arena's layout is remembered in ShootOFF's folder, beside the courses (spec §5)
        arenaFiles = ArenaFiles.inHome(File(home)),
        imagePicker = ImagePicker.Awt,
    )
    Settings.setUserNotifier(app.notices)
    // The camera opens in the background; the arena opens on the projector, if there is one, once the main
    // window below reports where it really landed. The window shows at once and nothing calibrates (spec §8
    // rules 1 and 6, and Revision 2, decision 4)
    app.launch()

    // An exception in a window's event handling or composition is logged and shown, and never exits the
    // app. Each window gets its own factory, so an error is always attributed to the window it escaped
    // from, and never rebuilds the other one
    val uiErrors = UiErrors(app.notices)
    val mainWindowErrors = uiErrors.forWindow(WindowRole.MAIN)
    val arenaWindowErrors = uiErrors.forWindow(WindowRole.ARENA)

    application {
        val arena by app.arena.collectAsState()
        val placement by app.arenaPlacement.collectAsState()
        val running by app.runner.running.collectAsState()
        val dark by app.dark.collectAsState()
        // Bumped by uiErrors.report, one counter per window: keying a window on its own generation
        // discards it (whose composition an escaped exception left frozen, its own recomposer cancelled)
        // and rebuilds it with a fresh one, without disturbing the other window
        val mainGeneration by uiErrors.generation(WindowRole.MAIN).collectAsState()
        val arenaGeneration by uiErrors.generation(WindowRole.ARENA).collectAsState()
        // A saved place that no longer reaches a current screen (a monitor unplugged) is discarded
        val remembered = app.prefs.window?.let { placeMainWindow(it, app.screensNow()) }
        val state = rememberWindowState(
            position = remembered?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
            size = remembered?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(1280.dp, 860.dp),
        )

        fun exit() {
            plugins.stopWatching()
            app.close()
            TimerPool.close()
            exitApplication()
        }

        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides mainWindowErrors) {
            key(mainGeneration) {
                Window(
                    onCloseRequest = ::exit,
                    state = state,
                    title = "ShootOFF",
                    onPreviewKeyEvent = { app.handleKey(it.key, it.type) },
                ) {
                    // Where the window is tells which screen ShootOFF is on; its place and size are remembered
                    LaunchedEffect(state) {
                        snapshotFlow { state.position to state.size }.collect { (position, size) ->
                            app.mainWindowPlaced(Point(window.x.toDouble(), window.y.toDouble()))
                            if (position.isSpecified) {
                                app.prefs.window = WindowBounds(position.x.value, position.y.value, size.width.value, size.height.value)
                            }
                        }
                    }
                    RangeTheme(dark = dark) { ShootOffApp(app) }
                }
            }
        }

        val shownArena = arena
        val shownPlacement = placement
        if (shownArena != null && shownPlacement != null) {
            CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides arenaWindowErrors) {
                // The arena's own state (ArenaModel) lives in AppState, outside composition, so rebuilding
                // this window loses only its chrome (position, full-screen), never the arena itself
                key(arenaGeneration) {
                    ArenaWindow(shownArena, shownPlacement, onCloseRequest = app::closeArena, onKey = { app.handleKey(it.key, it.type) }) { transform ->
                        if (running?.host?.isProjector == true) ExerciseOverlay(app.drill, transform)
                    }
                }
            }
        }
    }
}
