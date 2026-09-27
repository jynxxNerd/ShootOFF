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

package com.shootoff.compose.shots

import com.shootoff.camera.Shot
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.exercise.host.ExerciseHostSupport
import com.shootoff.shots.ShotTimer
import com.shootoff.shots.TimerRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Optional

/**
 * One shot timer row as the table shows it.
 *
 * @param values the running exercise's columns' values
 * @param highlight the row's highlight color as the exercise named it (e.g. "coral"), if any
 */
data class RowView(val row: TimerRow, val values: Map<String, String> = emptyMap(), val highlight: String? = null) {
    val shot: Shot get() = row.shot()
}

/**
 * The shot timer: the one ordered list of rows that the shot pipeline (a row per shot, on the shot
 * queue's thread) and the running exercise (its own rows, values and highlights, on its thread) both
 * write. Every change happens under one lock, in the order it was asked for, and publishes the whole new
 * list, so a value always lands on the row that was latest when the exercise set it.
 */
class ShotTimerModel : ShotTimer<ScaledShot> {
    private val lock = Any()
    private val rowState = MutableStateFlow<List<RowView>>(emptyList())
    private val columnState = MutableStateFlow<List<String>>(emptyList())

    val rows: StateFlow<List<RowView>> = rowState.asStateFlow()

    /** The running exercise's columns, after Time, Split and Laser */
    val columns: StateFlow<List<String>> = columnState.asStateFlow()

    override fun appendShotRow(shot: ScaledShot, hadMalfunction: Boolean, hadReload: Boolean) {
        synchronized(lock) {
            val rows = rowState.value
            rowState.value = rows + RowView(TimerRow.of(shot, previous(rows), hadMalfunction, hadReload))
        }
    }

    /** Adds a row no shot made (see ExerciseHost.addTimerRow) and makes it the latest. */
    fun addTimerRow(timeMillis: Long, highlight: String) {
        synchronized(lock) {
            val rows = rowState.value
            rowState.value = rows + RowView(ExerciseHostSupport.timerRow(timeMillis, previous(rows)), highlight = highlight)
        }
    }

    /** @return false if there is no row to set it on */
    fun setColumnValue(name: String, value: String): Boolean = synchronized(lock) {
        val rows = rowState.value
        if (rows.isEmpty()) return false
        val last = rows.last()
        rowState.value = rows.dropLast(1) + last.copy(values = last.values + (name to value))
        true
    }

    fun styleLastRow(highlight: String) {
        synchronized(lock) {
            val rows = rowState.value
            if (rows.isEmpty()) return
            rowState.value = rows.dropLast(1) + rows.last().copy(highlight = highlight)
        }
    }

    fun addColumn(name: String) {
        synchronized(lock) { columnState.value = columnState.value + name }
    }

    fun removeColumns(names: Collection<String>) {
        synchronized(lock) {
            val remaining = columnState.value.toMutableList()
            for (name in names) remaining.remove(name)
            columnState.value = remaining
        }
    }

    fun clear() {
        synchronized(lock) { rowState.value = emptyList() }
    }

    private fun previous(rows: List<RowView>): Optional<Shot> = Optional.ofNullable(rows.lastOrNull()?.shot)
}
