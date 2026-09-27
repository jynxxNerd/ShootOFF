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
import org.slf4j.LoggerFactory
import java.awt.Window

/**
 * The safety net under the Compose app's windows (spec §8 Revision 2, decision 8): an exception that
 * escapes event handling or composition is logged and shown as a notice, and never closes a window or
 * exits the app. Compose Desktop's own handler shows a dialog and closes the window the exception came
 * from, which for the main window exits the app. Provided to every window through
 * `LocalWindowExceptionHandlerFactory` (see Main).
 */
@OptIn(ExperimentalComposeUiApi::class)
class UiErrors(private val notices: Notices) : WindowExceptionHandlerFactory {
    private val logger = LoggerFactory.getLogger(UiErrors::class.java)

    override fun exceptionHandler(window: Window): WindowExceptionHandler = WindowExceptionHandler(::report)

    /** Logs [error] and tells the user, then returns: the window and the app carry on */
    fun report(error: Throwable) {
        logger.error("Unexpected error in the user interface; ShootOFF kept running", error)
        val detail = error.message?.let { "$it. " } ?: ""
        notices.showError("Something went wrong", error.javaClass.simpleName, "${detail}ShootOFF kept running; the details are in the log.")
    }
}
