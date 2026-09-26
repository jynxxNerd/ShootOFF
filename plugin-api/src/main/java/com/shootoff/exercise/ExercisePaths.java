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

import java.io.File;
import java.util.Optional;

/**
 * How hosts turn the names exercises use into jar resources and files.
 */
public final class ExercisePaths {
	private ExercisePaths() {}

	/**
	 * @return <tt>path</tt> as a resource name in an exercise's jar: without a leading <tt>@</tt> or
	 *         <tt>/</tt>, with <tt>/</tt> separators
	 */
	public static String resourceName(String path) {
		String name = path.replace('\\', '/');
		if (name.startsWith("@")) name = name.substring(1);
		while (name.startsWith("/")) name = name.substring(1);
		return name;
	}

	/**
	 * Finds a file in ShootOFF's folder (the <tt>shootoff.home</tt> system property, or the working
	 * directory): <tt>path</tt> itself if absolute, otherwise <tt>path</tt> in the home folder,
	 * otherwise <tt>path</tt> in its <tt>folder</tt> subfolder (so "IPSC.target" finds
	 * "targets/IPSC.target"). A path starting with <tt>@</tt> names a jar resource, never a file.
	 */
	public static Optional<File> shootoffFile(String path, String folder) {
		if (path.isEmpty() || path.startsWith("@")) return Optional.empty();

		final File file = new File(path);
		if (file.isAbsolute()) return file.isFile() ? Optional.of(file) : Optional.empty();

		final File home = new File(System.getProperty("shootoff.home", System.getProperty("user.dir")));
		final File inHome = new File(home, path);
		if (inHome.isFile()) return Optional.of(inHome);

		final File inFolder = new File(new File(home, folder), path);
		return inFolder.isFile() ? Optional.of(inFolder) : Optional.empty();
	}
}
