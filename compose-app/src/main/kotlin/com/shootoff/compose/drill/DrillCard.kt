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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.theme.NumberStyle
import com.shootoff.compose.theme.Range

/**
 * The running drill's card, floating over the big view: its name, its texts' first lines (the first
 * one big, as the score is in the mockup), its buttons, then [actions] (Range's Stop). Nothing shows
 * while no drill runs.
 *
 * @param pauseEnabled false disables only the Pause/Resume button (calibrating, or a pattern showing,
 *   Task 8 review fix round 1): other buttons, and [actions] (Stop), are unaffected.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrillCard(drill: DrillState, pauseEnabled: Boolean = true, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    val name by drill.name.collectAsState()
    val texts by drill.texts.collectAsState()
    val buttons by drill.buttons.collectAsState()
    val colors = Range.colors
    val shown = name ?: return

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.highlightCard.copy(alpha = 0.93f),
        border = BorderStroke(1.dp, colors.highlightBorder),
        modifier = modifier.widthIn(min = 180.dp, max = 300.dp).animateContentSize().testTag("drill-card"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                shown.uppercase(),
                color = colors.mutedStrong,
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            texts.forEachIndexed { index, text ->
                val line = text.text.lineSequence().firstOrNull().orEmpty()
                AnimatedContent(line, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "drill text") { value ->
                    if (index == 0) {
                        Text(value, style = Range.bigNumber, color = colors.bigNumber, modifier = Modifier.testTag("drill-text-$index"))
                    } else {
                        Text(value, style = NumberStyle.copy(fontSize = 12.sp), color = colors.mutedStrong, modifier = Modifier.testTag("drill-text-$index"))
                    }
                }
            }
            if (buttons.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    buttons.forEachIndexed { index, button ->
                        val enabled = pauseEnabled || (button.label != PAUSE_LABEL && button.label != RESUME_LABEL)
                        if (index == 0) {
                            Button(
                                onClick = button.onClick,
                                enabled = enabled,
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                                modifier = Modifier.testTag("drill-button-${button.label}"),
                            ) { Text(button.label) }
                        } else {
                            FilledTonalButton(onClick = button.onClick, enabled = enabled, modifier = Modifier.testTag("drill-button-${button.label}")) {
                                Text(button.label)
                            }
                        }
                    }
                }
            }
            actions()
        }
    }
}
