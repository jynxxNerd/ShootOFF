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
import java.util.concurrent.ConcurrentHashMap

/** Which top-level window an error escaped from: each is tracked, rebuilt and rate-limited independently */
enum class WindowRole { MAIN, ARENA }

/**
 * The safety net under the Compose app's windows (spec §8 Revision 2, decision 8): an exception that
 * escapes event handling or composition is logged and shown as a notice, and never closes a window or
 * exits the app. Compose Desktop's own handler shows a dialog and closes the window the exception came
 * from, which for the main window exits the app. [forWindow] gives each window (see Main) its own
 * `WindowExceptionHandlerFactory` for `LocalWindowExceptionHandlerFactory`, so an error is always
 * attributed to the [WindowRole] it actually escaped from, never the other window.
 *
 * In Compose 1.12.1, an exception in composition, a `LaunchedEffect` or a `pointerInput` coroutine
 * cancels that window's own recomposer before this handler ever runs (Recomposer.kt:289-312, 826-846;
 * Modifier.kt:184-190): the window stays open but never redraws again, so its notice would never be seen.
 * [generation] is bumped, per window, once per newly-reported, non-throttled error, so Main can rebuild
 * just that window (a fresh recomposer) by keying it on its own generation
 * (`key(generation(role)) { Window(...) { ... } }`). Keeping the two windows' generations separate
 * matters: a key-event or render error that `catchExceptions` catches without killing the recomposer
 * still reaches this handler, and it must never rebuild the *other* window (for the arena that would mean
 * leaving full screen, restarting a calibration check, or resizing mid-drill).
 */
@OptIn(ExperimentalComposeUiApi::class)
class UiErrors(
    private val notices: Notices,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(UiErrors::class.java)
    private val generations = WindowRole.entries.associateWith { MutableStateFlow(0) }
    private val lastReported = ConcurrentHashMap<ReportKey, Long>()
    private val lastRebuilt = ConcurrentHashMap<WindowRole, Long>()

    /** Bumped once per newly-reported, non-throttled error attributed to [role]; see the class doc */
    fun generation(role: WindowRole): StateFlow<Int> = generations.getValue(role).asStateFlow()

    /** [role]'s own factory: every error `LocalWindowExceptionHandlerFactory` reports through it is [role]'s */
    fun forWindow(role: WindowRole): WindowExceptionHandlerFactory =
        WindowExceptionHandlerFactory { WindowExceptionHandler { error -> report(role, error) } }

    /**
     * Logs [error] and tells the user, then returns: the window and the app carry on.
     *
     * An error with the same class and message as one reported for [role] within the last
     * [DEDUP_WINDOW_MILLIS] is a repeat: it is dropped without a new log line, notice or generation bump.
     *
     * A distinct error (a different class, or a message that carries changing data, such as "Index 37 out
     * of bounds…", which defeats that check every time) is always logged; but [role]'s notice and
     * generation bump are floored to at most one per [REBUILD_FLOOR_MILLIS], whatever the error, so a
     * message that changes every frame can't grow the notices, or rebuild the window, at frame rate.
     */
    fun report(role: WindowRole, error: Throwable) {
        val key = ReportKey(role, error.javaClass.name, error.message)
        val now = clock()
        var duplicate = false
        lastReported.compute(key) { _, last ->
            if (last != null && now - last < DEDUP_WINDOW_MILLIS) duplicate = true
            now
        }
        if (duplicate) return

        logger.error("Unexpected error in the user interface; ShootOFF kept running", error)

        var throttled = false
        lastRebuilt.compute(role) { _, last ->
            if (last != null && now - last < REBUILD_FLOOR_MILLIS) {
                throttled = true
                last
            } else {
                now
            }
        }
        if (throttled) return

        val detail = error.message?.let { "$it. " } ?: ""
        notices.showError("Something went wrong", error.javaClass.simpleName, "${detail}ShootOFF kept running; the details are in the log.")
        generations.getValue(role).update { it + 1 }
    }

    private data class ReportKey(val role: WindowRole, val exceptionClass: String, val message: String?)

    private companion object {
        const val DEDUP_WINDOW_MILLIS = 5000L
        const val REBUILD_FLOOR_MILLIS = 3000L
    }
}
