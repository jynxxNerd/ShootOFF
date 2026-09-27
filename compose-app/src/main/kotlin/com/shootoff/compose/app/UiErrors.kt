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

package com.shootoff.compose.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory
import java.awt.Window
import java.util.concurrent.ConcurrentHashMap

/**
 * The safety net under the Compose app's windows (spec §8 Revision 2, decision 8): an exception that
 * escapes event handling or composition is logged and shown as a notice, and never closes a window or
 * exits the app. Compose Desktop's own handler shows a dialog and closes the window the exception came
 * from, which for the main window exits the app. Provided to every window through
 * `LocalWindowExceptionHandlerFactory` (see Main).
 *
 * In Compose 1.12.1, an exception in composition, a `LaunchedEffect` or a `pointerInput` coroutine
 * cancels that window's own recomposer before this handler ever runs (Recomposer.kt:289-312, 826-846;
 * Modifier.kt:184-190): the window stays open but never redraws again, so its notice would never be seen.
 * [generation] is bumped once per newly-reported error so Main can rebuild the window (a fresh recomposer)
 * by keying it on `generation` (`key(generation) { Window(...) { ... } }`).
 */
@OptIn(ExperimentalComposeUiApi::class)
class UiErrors(
    private val notices: Notices,
    private val clock: () -> Long = System::currentTimeMillis,
) : WindowExceptionHandlerFactory {
    private val logger = LoggerFactory.getLogger(UiErrors::class.java)
    private val generationState = MutableStateFlow(0)
    private val lastReported = ConcurrentHashMap<Pair<String, String?>, Long>()

    /** Bumped once per newly-reported error (never for a de-duplicated repeat); see the class doc */
    val generation: StateFlow<Int> = generationState.asStateFlow()

    override fun exceptionHandler(window: Window): WindowExceptionHandler = WindowExceptionHandler(::report)

    /**
     * Logs [error] and tells the user, then returns: the window and the app carry on. An error with the
     * same class and message as one reported within the last [DEDUP_WINDOW_MILLIS] is a repeat: it is
     * dropped without a new log line, notice or [generation] bump. That keeps a render error that recurs
     * every frame from flooding the log and the notices, and from driving the window rebuild in a loop.
     */
    fun report(error: Throwable) {
        val key = error.javaClass.name to error.message
        val now = clock()
        var duplicate = false
        lastReported.compute(key) { _, last ->
            if (last != null && now - last < DEDUP_WINDOW_MILLIS) duplicate = true
            now
        }
        if (duplicate) return

        logger.error("Unexpected error in the user interface; ShootOFF kept running", error)
        val detail = error.message?.let { "$it. " } ?: ""
        notices.showError("Something went wrong", error.javaClass.simpleName, "${detail}ShootOFF kept running; the details are in the log.")
        generationState.update { it + 1 }
    }

    private companion object {
        const val DEDUP_WINDOW_MILLIS = 5000L
    }
}
