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

package com.shootoff.plugins;

import java.util.List;

import com.shootoff.exercise.Exercise;
import com.shootoff.plugins.engine.V2ExerciseEntry;

/**
 * The exercises that ship with ShootOFF, in the Training menu's order. Both apps list them.
 */
public final class BuiltInRegistry {
	private BuiltInRegistry() {}

	private static final List<Class<? extends Exercise>> EXERCISES = List.of(RandomShoot.class, ShootForScore.class,
			TimedHolsterDrill.class, ParForScore.class, ParRandomShot.class);

	public static List<V2ExerciseEntry> entries() {
		return EXERCISES.stream().map(BuiltInRegistry::entry).toList();
	}

	// A throwaway instance tells the exercise's name and whether it runs only on the projector
	private static V2ExerciseEntry entry(Class<? extends Exercise> exerciseClass) {
		try {
			return new V2ExerciseEntry(exerciseClass, exerciseClass.getDeclaredConstructor().newInstance().metadata());
		} catch (final ReflectiveOperationException e) {
			throw new IllegalStateException("Can't make a " + exerciseClass.getSimpleName(), e);
		}
	}
}
