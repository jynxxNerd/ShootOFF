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

package com.shootoff.gui;

import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

import javafx.geometry.Bounds;
import javafx.geometry.Dimension2D;

/**
 * Converts JavaFX geometry to the UI-neutral core types where a JavaFX node or stage hands a
 * value to core code.
 */
public final class FxGeometry {
	private FxGeometry() {}

	public static Rect toRect(Bounds bounds) {
		return new Rect(bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight());
	}

	public static Size toSize(Dimension2D dimension) {
		return new Size(dimension.getWidth(), dimension.getHeight());
	}
}
