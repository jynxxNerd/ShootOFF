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

import com.shootoff.plugins.ProjectorTrainingExerciseBase;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.plugins.TrainingExerciseBase;

/**
 * Loads exercises of plugin API version 1 (descriptors without <tt>apiVersion</tt>): direct
 * subclasses of {@link TrainingExerciseBase} or {@link ProjectorTrainingExerciseBase}.
 */
public final class LegacyExerciseLoader implements ExerciseLoader {
	@Override
	public int apiVersion() {
		return 1;
	}

	@Override
	public ExerciseEntry load(Class<?> exerciseClass) {
		final Class<?> superclass = exerciseClass.getSuperclass();
		final String superclassName = superclass == null ? "null" : superclass.getName();
		final boolean isStandard = TrainingExerciseBase.class.getName().equals(superclassName);
		final boolean isProjector = ProjectorTrainingExerciseBase.class.getName().equals(superclassName);

		if (!isStandard && !isProjector) {
			throw new IllegalArgumentException(String.format(
					"Configured exerciseClass (%s) does not have a known training exercise superclass, type is %s",
					exerciseClass.getName(), superclassName));
		}

		try {
			return new LegacyExerciseEntry((TrainingExercise) exerciseClass.getDeclaredConstructor().newInstance(),
					isProjector);
		} catch (final ReflectiveOperationException | ClassCastException e) {
			throw new IllegalArgumentException("Error instantiating configured exerciseClass " + exerciseClass.getName(),
					e);
		}
	}
}
