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

/**
 * Turns a plugin's exercise class into a Training menu entry, for one plugin API version (the
 * <tt>apiVersion</tt> in <tt>shootoff.xml</tt>).
 */
public interface ExerciseLoader {
	int apiVersion();

	/**
	 * @throws IllegalArgumentException
	 *             if <tt>exerciseClass</tt> isn't an exercise of this API version
	 */
	ExerciseEntry load(Class<?> exerciseClass);
}
