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

/**
 * One exercise in the Training menu: a built-in exercise or one loaded from a plugin jar, of either
 * API version. Each user interface knows how to run the kinds it supports.
 */
public interface ExerciseEntry {
	ExerciseMetadata metadata();

	/**
	 * @return <tt>true</tt> if the exercise only runs on the projector arena
	 */
	boolean isProjectorOnly();

	Class<?> exerciseClass();
}
