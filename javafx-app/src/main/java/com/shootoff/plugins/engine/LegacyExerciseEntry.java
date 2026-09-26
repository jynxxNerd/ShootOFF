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

import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.plugins.TrainingExercise;

/**
 * A v1 exercise in the Training menu. The app starts it by calling the prototype class's
 * <tt>(List&lt;Target&gt;)</tt> constructor, as it always has.
 */
public record LegacyExerciseEntry(TrainingExercise prototype, boolean isProjectorOnly) implements ExerciseEntry {
	@Override
	public ExerciseMetadata metadata() {
		return prototype.getInfo();
	}

	@Override
	public Class<?> exerciseClass() {
		return prototype.getClass();
	}
}
