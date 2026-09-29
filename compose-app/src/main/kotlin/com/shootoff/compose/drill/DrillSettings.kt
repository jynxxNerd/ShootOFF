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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shootoff.compose.theme.NumberStyle
import com.shootoff.compose.theme.Range
import java.util.Locale

/**
 * The running drill's settings: its number, yes/no and choice settings, and the shared par time and start
 * delay while it listens to them. A typed value counts once the user presses Enter or leaves the field; −
 * and + step it.
 */
@Composable
fun DrillSettings(drill: DrillState, modifier: Modifier = Modifier) {
    val settings by drill.settings.collectAsState()
    val timing by drill.timing.collectAsState()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (setting in settings) {
            when (setting) {
                is NumberSetting -> NumberField(setting.label, setting.value, setting.min, setting.max, setting.step, "setting-${setting.label}") {
                    setting.onChange(it)
                }
                is YesNoSetting -> YesNoField(setting)
                is ChoiceSetting -> ChoiceField(setting)
            }
        }
        timing?.let { controls ->
            if (controls.showsParTime) {
                NumberField("Par time (s)", controls.parTime, 0.0, 3600.0, 0.1, "par-time") { controls.onParTime(it) }
            }
            NumberField("Delay from (s)", controls.delay.minSeconds().toDouble(), 0.0, 3600.0, 1.0, "delay-min") {
                controls.onDelay(it.toInt(), controls.delay.maxSeconds())
            }
            NumberField("Delay to (s)", controls.delay.maxSeconds().toDouble(), 0.0, 3600.0, 1.0, "delay-max") {
                controls.onDelay(controls.delay.minSeconds(), it.toInt())
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    tag: String,
    onCommit: (Double) -> Unit,
) {
    var text by remember(value) { mutableStateOf(format(value, step)) }
    val commit = {
        val typed = text.toDoubleOrNull()
        if (typed != null && typed in min..max) {
            if (typed != value) onCommit(typed)
        } else {
            text = format(value, step)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Range.colors.mutedStrong, modifier = Modifier.width(120.dp))
        TextButton(onClick = { stepped(value - step).takeIf { it >= min }?.let(onCommit) }, modifier = Modifier.testTag("$tag-down")) { Text("−") }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = NumberStyle,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .width(96.dp)
                .testTag(tag)
                .onFocusChanged { if (!it.isFocused) commit() }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        commit()
                        true
                    } else {
                        false
                    }
                },
        )
        TextButton(onClick = { stepped(value + step).takeIf { it <= max }?.let(onCommit) }, modifier = Modifier.testTag("$tag-up")) { Text("+") }
    }
}

@Composable
private fun YesNoField(setting: YesNoSetting) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(setting.label, color = Range.colors.mutedStrong, modifier = Modifier.width(120.dp))
        Checkbox(
            checked = setting.value,
            onCheckedChange = { setting.onChange(it) },
            modifier = Modifier.testTag("setting-${setting.label}"),
        )
    }
}

@Composable
private fun ChoiceField(setting: ChoiceSetting) {
    var open by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(setting.label, color = Range.colors.mutedStrong, modifier = Modifier.width(120.dp))
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("setting-${setting.label}")) {
                Text(setting.value, style = NumberStyle)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (choice in setting.choices) {
                    DropdownMenuItem(
                        text = { Text(choice) },
                        onClick = {
                            open = false
                            if (choice != setting.value) setting.onChange(choice)
                        },
                        modifier = Modifier.testTag("setting-${setting.label}-$choice"),
                    )
                }
            }
        }
    }
}

// A step's sum without floating point dust (2.0 - 0.1 is 1.9)
private fun stepped(value: Double): Double = Math.round(value * 1_000_000) / 1_000_000.0

// Whole numbers without a decimal point, others to two places at most
private fun format(value: Double, step: Double): String =
    if (step >= 1 && value == Math.rint(value)) {
        value.toLong().toString()
    } else {
        String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    }
