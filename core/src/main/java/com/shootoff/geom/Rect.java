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

package com.shootoff.geom;

import java.util.Objects;

/**
 * An immutable axis-aligned rectangle. Replaces JavaFX's {@code Bounds}/{@code BoundingBox} in
 * UI-neutral code: the getter names match, and {@link #contains(double, double)} behaves exactly
 * like {@code BoundingBox.contains} (edges inclusive, nothing inside an empty rectangle).
 */
public final class Rect {
	private final double minX;
	private final double minY;
	private final double width;
	private final double height;

	public Rect(double minX, double minY, double width, double height) {
		this.minX = minX;
		this.minY = minY;
		this.width = width;
		this.height = height;
	}

	public double getMinX() {
		return minX;
	}

	public double getMinY() {
		return minY;
	}

	public double getWidth() {
		return width;
	}

	public double getHeight() {
		return height;
	}

	public double getMaxX() {
		return minX + width;
	}

	public double getMaxY() {
		return minY + height;
	}

	/**
	 * @return <code>true</code> if the width or height is negative
	 */
	public boolean isEmpty() {
		return width < 0 || height < 0;
	}

	public boolean contains(double x, double y) {
		if (isEmpty()) return false;

		return x >= minX && x <= getMaxX() && y >= minY && y <= getMaxY();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof Rect)) return false;

		final Rect other = (Rect) o;
		return Double.compare(minX, other.minX) == 0 && Double.compare(minY, other.minY) == 0
				&& Double.compare(width, other.width) == 0 && Double.compare(height, other.height) == 0;
	}

	@Override
	public int hashCode() {
		return Objects.hash(minX, minY, width, height);
	}

	@Override
	public String toString() {
		return "Rect[minX=" + minX + ", minY=" + minY + ", width=" + width + ", height=" + height + "]";
	}
}
