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

/**
 * Where a target is drawn: its position (the translation of its target coordinates, JavaFX's
 * layoutX/layoutY), its scale about the center of its visible regions' bounds, and whether it is
 * shown.
 */
public record Placement(double x, double y, double scaleX, double scaleY, boolean visible) {
	public static final Placement ORIGIN = new Placement(0, 0, 1, 1, true);

	public Placement withPosition(double x, double y) {
		return new Placement(x, y, scaleX, scaleY, visible);
	}

	public Placement withScale(double scaleX, double scaleY) {
		return new Placement(x, y, scaleX, scaleY, visible);
	}

	public Placement withVisible(boolean visible) {
		return new Placement(x, y, scaleX, scaleY, visible);
	}
}
