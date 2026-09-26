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
 * A .target file that can't be read: malformed XML, a missing or non-numeric attribute, a polygon
 * without points, or an image that can't be found or read. The message names the file (or
 * "target stream") and, when known, the line.
 */
public class TargetFormatException extends Exception {
	private static final long serialVersionUID = 1L;

	public TargetFormatException(String message) {
		super(message);
	}

	public TargetFormatException(String message, Throwable cause) {
		super(message, cause);
	}
}
