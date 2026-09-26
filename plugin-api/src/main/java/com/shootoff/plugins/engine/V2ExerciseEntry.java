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
 * A v2 exercise in the Training menu. Each run starts from a fresh instance.
 */
public record V2ExerciseEntry(Class<? extends Exercise> exerciseClass, ExerciseMetadata metadata)
		implements ExerciseEntry {
	@Override
	public boolean isProjectorOnly() {
		return metadata.isProjectorOnly();
	}

	public Exercise newInstance() throws ReflectiveOperationException {
		return exerciseClass.getDeclaredConstructor().newInstance();
	}
}
