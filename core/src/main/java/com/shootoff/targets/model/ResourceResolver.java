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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Opens the files a target refers to (its images). Paths are as written in the .target file:
 * absolute, relative to the ShootOFF home folder, or starting with <tt>@</tt> for a resource in
 * an exercise's jar.
 */
@FunctionalInterface
public interface ResourceResolver {
	/**
	 * @return the resource's contents, or empty if there is no such resource
	 */
	Optional<InputStream> open(String path) throws IOException;

	/**
	 * Resolves absolute paths as they are and relative paths against the <tt>shootoff.home</tt>
	 * system property (the working directory if it isn't set). <tt>@</tt> paths are never found.
	 */
	static ResourceResolver files() {
		return path -> {
			if (path.isEmpty() || path.charAt(0) == '@') return Optional.empty();

			File file = new File(path);
			if (!file.isAbsolute()) {
				file = new File(System.getProperty("shootoff.home", System.getProperty("user.dir")), path);
			}

			return file.isFile() ? Optional.of(new FileInputStream(file)) : Optional.empty();
		};
	}

	/**
	 * Resolves <tt>@</tt> paths as resources of <tt>loader</tt> (the <tt>@</tt> dropped and
	 * backslashes turned into slashes, as the JavaFX app always did), and every other path like
	 * {@link #files()}.
	 *
	 * @param loader
	 *            an exercise's class loader, or <tt>null</tt> for {@link #files()}
	 */
	static ResourceResolver classLoader(ClassLoader loader) {
		final ResourceResolver files = files();
		if (loader == null) return files;

		return path -> {
			if (!path.isEmpty() && path.charAt(0) == '@') {
				return Optional.ofNullable(loader.getResourceAsStream(path.substring(1).replace('\\', '/')));
			}

			return files.open(path);
		};
	}
}
