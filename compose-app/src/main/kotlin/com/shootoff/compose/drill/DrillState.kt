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

import com.shootoff.compose.shots.ShotMarkers
import com.shootoff.exercise.DelayRange
import com.shootoff.exercise.TextStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DrillButton(val id: Long, val label: String, val onClick: () -> Unit)

/** The par drill's pause button reads this while the drill runs */
const val PAUSE_LABEL = "Pause"

/** … and this while it is paused */
const val RESUME_LABEL = "Resume"

/** One of the running exercise's settings, in the drill panel */
sealed interface DrillSetting {
    val id: Long
    val label: String
}

data class NumberSetting(
    override val id: Long,
    override val label: String,
    val value: Double,
    val min: Double,
    val max: Double,
    val step: Double,
    val onChange: (Double) -> Unit,
) : DrillSetting

/** A check box */
data class YesNoSetting(
    override val id: Long,
    override val label: String,
    val value: Boolean,
    val onChange: (Boolean) -> Unit,
) : DrillSetting

/** A drop-down of [choices] */
data class ChoiceSetting(
    override val id: Long,
    override val label: String,
    val choices: List<String>,
    val value: String,
    val onChange: (String) -> Unit,
) : DrillSetting

/** A text the exercise shows on its surface, in the surface's coordinates */
data class DrillText(val id: Long, val text: String, val x: Double, val y: Double, val style: TextStyle)

/**
 * The shared par time and start delay controls, while the running exercise listens to them.
 *
 * @param showsParTime false when the exercise listens only to the start delay
 * @param onParTime the user set the par time
 * @param onDelay the user set the start delay's minimum and maximum
 */
data class TimingControls(
    val showsParTime: Boolean,
    val parTime: Double,
    val delay: DelayRange,
    val onParTime: (Double) -> Unit,
    val onDelay: (Int, Int) -> Unit,
)

/**
 * What the running exercise shows outside its surface's targets: the drill panel's buttons, settings
 * and timing controls, its texts and markers, and its banner message. Its host writes it from any
 * thread; Compose draws it.
 */
class DrillState {
    private val nameState = MutableStateFlow<String?>(null)
    private val buttonState = MutableStateFlow<List<DrillButton>>(emptyList())
    private val settingState = MutableStateFlow<List<DrillSetting>>(emptyList())
    private val textState = MutableStateFlow<List<DrillText>>(emptyList())
    private val timingState = MutableStateFlow<TimingControls?>(null)
    private val messageState = MutableStateFlow<String?>(null)

    /** The running exercise's name, while one runs */
    val name: StateFlow<String?> = nameState.asStateFlow()
    val buttons: StateFlow<List<DrillButton>> = buttonState.asStateFlow()
    val settings: StateFlow<List<DrillSetting>> = settingState.asStateFlow()
    val texts: StateFlow<List<DrillText>> = textState.asStateFlow()
    val timing: StateFlow<TimingControls?> = timingState.asStateFlow()

    /** The exercise's banner, shown on every camera feed */
    val message: StateFlow<String?> = messageState.asStateFlow()

    /** The markers the exercise drew itself, on its surface */
    val markers = ShotMarkers()

    fun setName(name: String?) {
        nameState.value = name
    }

    fun addButton(button: DrillButton) = buttonState.update { it + button }

    fun relabelButton(id: Long, label: String) = buttonState.update { buttons -> buttons.map { if (it.id == id) it.copy(label = label) else it } }

    fun removeButton(id: Long) = buttonState.update { buttons -> buttons.filterNot { it.id == id } }

    fun addSetting(setting: DrillSetting) = settingState.update { it + setting }

    fun removeSetting(id: Long) = settingState.update { settings -> settings.filterNot { it.id == id } }

    /** The user set a number setting's value */
    fun setSettingValue(id: Long, value: Double) = updateSetting(id) { if (it is NumberSetting) it.copy(value = value) else it }

    /** The user ticked or cleared a yes/no setting */
    fun setSettingValue(id: Long, value: Boolean) = updateSetting(id) { if (it is YesNoSetting) it.copy(value = value) else it }

    /** The user picked one of a choice setting's choices */
    fun setSettingChoice(id: Long, choice: String) = updateSetting(id) { if (it is ChoiceSetting) it.copy(value = choice) else it }

    private fun updateSetting(id: Long, change: (DrillSetting) -> DrillSetting) =
        settingState.update { settings -> settings.map { if (it.id == id) change(it) else it } }

    fun addText(text: DrillText) = textState.update { it + text }

    fun updateText(id: Long, change: (DrillText) -> DrillText) = textState.update { texts -> texts.map { if (it.id == id) change(it) else it } }

    fun removeText(id: Long) = textState.update { texts -> texts.filterNot { it.id == id } }

    fun setTiming(timing: TimingControls?) {
        timingState.value = timing
    }

    fun updateTiming(change: (TimingControls) -> TimingControls) = timingState.update { it?.let(change) }

    fun setMessage(message: String?) {
        messageState.value = message
    }

    /** Everything goes, as when the exercise stops */
    fun clear() {
        nameState.value = null
        buttonState.value = emptyList()
        settingState.value = emptyList()
        textState.value = emptyList()
        timingState.value = null
        messageState.value = null
        markers.clear()
    }
}
