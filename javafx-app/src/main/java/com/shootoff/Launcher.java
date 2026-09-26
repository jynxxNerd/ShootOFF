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

package com.shootoff;

/**
 * Entry point for running ShootOFF from the classpath. The JVM refuses to
 * launch a main class that extends javafx.application.Application unless
 * JavaFX is on the module path, so this class delegates to Main.
 */
public final class Launcher {
	private Launcher() {}

	public static void main(String[] args) {
		Main.main(args);
	}
}
