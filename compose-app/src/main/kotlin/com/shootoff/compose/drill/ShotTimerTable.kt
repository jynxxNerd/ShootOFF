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

package com.shootoff.compose.drill

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.shots.RowView
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.theme.NumberStyle
import com.shootoff.compose.theme.Range

// The JavaFX shot timer's split colors for a shot after a malfunction or a reload
private val MALFUNCTION = Color(0xFFFFA500)
private val RELOAD = Color(0xFF87CEFA)

/**
 * The shot timer: #, Time, Split and Laser, then the running exercise's columns. A row the exercise
 * highlights (e.g. the par drill's coral par-miss row) is tinted in its color. New rows scroll into view.
 */
@Composable
fun ShotTimerTable(timer: ShotTimerModel, modifier: Modifier = Modifier) {
    val rows by timer.rows.collectAsState()
    val columns by timer.columns.collectAsState()
    val colors = Range.colors
    val list = rememberLazyListState()

    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) list.animateScrollToItem(rows.size - 1)
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Header("#", 28)
            Header("Time", 64)
            Header("Split", 64)
            Header("Laser", 64)
            for (column in columns) Header(column, 72)
        }
        HorizontalDivider(color = colors.cardBorder)
        LazyColumn(state = list, modifier = Modifier.testTag("shot-timer")) {
            itemsIndexed(rows, key = { index, _ -> index }) { index, row ->
                TimerRow(index, row, columns, Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun RowScope.Header(name: String, width: Int) {
    Text(name, color = Range.colors.muted, fontSize = 11.sp, modifier = Modifier.width(width.dp))
}

@Composable
private fun TimerRow(index: Int, row: RowView, columns: List<String>, modifier: Modifier) {
    val colors = Range.colors
    val tint = row.highlight?.let { webColor(it).copy(alpha = 0.55f) } ?: Color.Transparent
    Column(modifier) {
        Row(
            horizontalArrangement = Arrangement.Start,
            modifier = Modifier
                .fillMaxWidth()
                .background(tint, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .testTag("timer-row-$index")
                .semantics { stateDescription = row.highlight ?: "plain" },
        ) {
            Cell("${index + 1}", 28)
            Cell(row.row.time(), 64)
            val split = when {
                row.row.hadMalfunction() -> MALFUNCTION
                row.row.hadReload() -> RELOAD
                else -> null
            }
            Cell(row.row.split(), 64, background = split)
            Cell(row.row.laser(), 64)
            for (column in columns) Cell(row.values[column].orEmpty(), 72)
        }
        HorizontalDivider(color = colors.cardBorder)
    }
}

@Composable
private fun RowScope.Cell(text: String, width: Int, background: Color? = null) {
    Text(
        text,
        style = NumberStyle.copy(fontSize = 12.sp),
        color = Range.colors.text,
        modifier = Modifier.width(width.dp).let { if (background != null) it.background(background.copy(alpha = 0.6f)) else it },
    )
}
