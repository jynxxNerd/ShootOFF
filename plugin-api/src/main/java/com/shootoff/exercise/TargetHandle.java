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

package com.shootoff.exercise;

import com.shootoff.geom.Point;
import com.shootoff.geom.Size;
import com.shootoff.targets.model.TargetDefinition;
import com.shootoff.targets.model.TargetId;

/**
 * A target on the exercise's surface. Hits name it by {@link #id()}.
 */
public interface TargetHandle {
	TargetId id();

	/**
	 * Moves the target's top-left corner to (<tt>x</tt>, <tt>y</tt>).
	 */
	void move(double x, double y);

	void resize(double width, double height);

	/**
	 * Shows or hides the target. Hidden targets take no hits.
	 */
	void setVisible(boolean visible);

	void remove();

	Point position();

	Size size();

	TargetDefinition definition();
}
