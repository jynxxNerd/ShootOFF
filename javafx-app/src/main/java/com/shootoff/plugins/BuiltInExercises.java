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

import java.util.ArrayList;
import java.util.List;

import com.shootoff.plugins.engine.ExerciseEntry;
import com.shootoff.plugins.engine.LegacyExerciseEntry;

/**
 * The exercises that ship with the JavaFX app: the ones ported to the v2 exercise API, which the Compose
 * app lists too ({@link BuiltInRegistry}), then the JavaFX ones still to port, in Training menu order.
 */
public final class BuiltInExercises {
	private BuiltInExercises() {}

	public static List<ExerciseEntry> entries() {
		final List<ExerciseEntry> entries = new ArrayList<>(BuiltInRegistry.entries());
		entries.addAll(List.of(standard(new ISSFStandardPistol()), projector(new BouncingTargets()),
				projector(new DuelingTree()), projector(new ShootDontShoot()), projector(new SteelChallenge())));
		return entries;
	}

	private static ExerciseEntry standard(TrainingExercise exercise) {
		return new LegacyExerciseEntry(exercise, false);
	}

	private static ExerciseEntry projector(TrainingExercise exercise) {
		return new LegacyExerciseEntry(exercise, true);
	}
}
