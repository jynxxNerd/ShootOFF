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

package com.shootoff.session.io;

import com.shootoff.camera.shot.ShotColor;

/**
 * Shot colors as session files hold them. Files have always stored the JavaFX marker color's
 * Color.toString() (red, green, and orange for infrared), and XML files without videos the
 * ShotColor's name.
 */
public final class SessionColors {
	private SessionColors() {}

	public static String paintString(ShotColor color) {
		return switch (color) {
		case RED -> "0xff0000ff";
		case GREEN -> "0x008000ff";
		case INFRARED -> "0xffa500ff";
		};
	}

	/**
	 * Reads a color written by any ShotOFF version: a paint string or a ShotColor name. Anything
	 * else is green, as it always was.
	 */
	public static ShotColor parse(String color) {
		if ("0xff0000ff".equals(color) || "RED".equals(color)) {
			return ShotColor.RED;
		} else if ("0xffa500ff".equals(color) || "INFRARED".equals(color)) {
			return ShotColor.INFRARED;
		} else {
			return ShotColor.GREEN;
		}
	}
}
