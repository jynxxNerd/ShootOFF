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

import com.shootoff.plugins.BuiltInRegistry
import com.shootoff.plugins.engine.ExerciseEntry
import com.shootoff.plugins.engine.PluginEngine
import com.shootoff.plugins.engine.PluginListener
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.plugins.engine.V2ExerciseLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The plugin engine that fills [catalog]: the exercises that ship with ShootOFF (exercise port spec §5), and
 * the v2 plugin jars in the shootoff.plugins folder.
 */
fun pluginEngine(catalog: ExerciseCatalog): PluginEngine {
    val builtIns: List<ExerciseEntry> = BuiltInRegistry.entries()
    return PluginEngine(catalog, listOf(V2ExerciseLoader()), builtIns)
}

/**
 * The exercises the Drills screen lists: the v2 exercises the plugin engine registers (the Compose app
 * runs no v1 exercise), by name.
 */
class ExerciseCatalog : PluginListener {
    private val entryState = MutableStateFlow<List<V2ExerciseEntry>>(emptyList())
    val entries: StateFlow<List<V2ExerciseEntry>> = entryState.asStateFlow()

    override fun registerExercise(exercise: ExerciseEntry) = add(exercise)

    override fun registerProjectorExercise(exercise: ExerciseEntry) = add(exercise)

    override fun unregisterExercise(exercise: ExerciseEntry) = entryState.update { entries -> entries.filterNot { it == exercise } }

    private fun add(exercise: ExerciseEntry) {
        if (exercise !is V2ExerciseEntry) return
        entryState.update { entries -> (entries + exercise).sortedBy { it.metadata().name } }
    }
}
