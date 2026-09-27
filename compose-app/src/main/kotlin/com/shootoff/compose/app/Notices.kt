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

import com.shootoff.util.UserNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

data class Notice(val id: Long, val title: String, val message: String)

/**
 * Problems found by code without a user interface of its own (a missing target file, an unwritable
 * shootoff.properties), shown as snackbars. Install with Settings.setUserNotifier.
 */
class Notices : UserNotifier {
    private val next = AtomicLong()
    private val noticeState = MutableStateFlow<List<Notice>>(emptyList())
    val notices: StateFlow<List<Notice>> = noticeState.asStateFlow()

    override fun showError(title: String, header: String, message: String) {
        noticeState.update { it + Notice(next.incrementAndGet(), "$title: $header", message) }
    }

    fun dismiss(notice: Notice) = noticeState.update { notices -> notices.filterNot { it.id == notice.id } }
}
