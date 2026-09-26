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

package com.shootoff.shots;

import com.shootoff.camera.Shot;

/**
 * A user interface's shot timer, as the {@link ShotPipeline} fills it.
 */
@FunctionalInterface
public interface ShotTimer<S extends Shot> {
	/**
	 * Appends the row for <tt>shot</tt> after the latest row (see {@link TimerRow#of}).
	 *
	 * @param hadMalfunction
	 *            <tt>true</tt> if a malfunction rejected the shot before this one
	 * @param hadReload
	 *            <tt>true</tt> if the virtual magazine rejected the shot before this one
	 */
	void appendShotRow(S shot, boolean hadMalfunction, boolean hadReload);
}
