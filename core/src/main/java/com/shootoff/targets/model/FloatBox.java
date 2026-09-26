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

package com.shootoff.targets.model;

import com.shootoff.geom.Rect;

/**
 * A bounding box stored the way JavaFX stores one (RectBounds): float edges, empty when a maximum
 * is below its minimum.
 */
record FloatBox(float minX, float minY, float maxX, float maxY) {
	static final FloatBox EMPTY = new FloatBox(0, 0, -1, -1);

	boolean isEmpty() {
		return maxX < minX || maxY < minY;
	}

	FloatBox union(FloatBox other) {
		if (other.isEmpty()) return this;
		if (isEmpty()) return other;

		return new FloatBox(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.max(maxX, other.maxX),
				Math.max(maxY, other.maxY));
	}

	/**
	 * @return the box as JavaFX turns RectBounds into a BoundingBox: the width and height are the
	 *         float differences of the edges
	 */
	Rect toRect() {
		return new Rect(minX, minY, maxX - minX, maxY - minY);
	}

	/**
	 * @return the box through <tt>x' = x * scaleX + tx</tt> (and likewise for y), rounded to
	 *         float as JavaFX's bounds transforms do
	 */
	FloatBox transform(double scaleX, double scaleY, double tx, double ty) {
		if (isEmpty()) return this;

		final float x1 = (float) (minX * scaleX + tx);
		final float x2 = (float) (maxX * scaleX + tx);
		final float y1 = (float) (minY * scaleY + ty);
		final float y2 = (float) (maxY * scaleY + ty);

		return new FloatBox(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2));
	}
}
