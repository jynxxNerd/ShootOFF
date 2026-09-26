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

package com.shootoff.plugins.engine;

import com.shootoff.exercise.Exercise;
import com.shootoff.plugins.ExerciseMetadata;

/**
 * Loads exercises of plugin API version 2: classes that implement {@link Exercise}.
 */
public final class V2ExerciseLoader implements ExerciseLoader {
	@Override
	public int apiVersion() {
		return 2;
	}

	@Override
	public ExerciseEntry load(Class<?> exerciseClass) {
		if (!Exercise.class.isAssignableFrom(exerciseClass)) {
			throw new IllegalArgumentException(String.format("Configured exerciseClass (%s) does not implement %s",
					exerciseClass.getName(), Exercise.class.getName()));
		}

		final Class<? extends Exercise> type = exerciseClass.asSubclass(Exercise.class);
		final ExerciseMetadata metadata;
		try {
			metadata = type.getDeclaredConstructor().newInstance().metadata();
		} catch (final ReflectiveOperationException e) {
			throw new IllegalArgumentException("Error instantiating configured exerciseClass " + type.getName(), e);
		}

		if (metadata == null) {
			throw new IllegalArgumentException(type.getName() + " returned no metadata");
		}

		return new V2ExerciseEntry(type, metadata);
	}
}
