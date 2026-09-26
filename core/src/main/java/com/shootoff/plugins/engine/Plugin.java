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

package com.shootoff.plugins.engine;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * An exercise loaded from a plugin jar, by the loader for its descriptor's API version.
 */
public class Plugin {
	private final Path jarPath;
	private final URLClassLoader loader;
	private final PluginDescriptor descriptor;
	private final ExerciseEntry entry;

	/**
	 * @throws IllegalArgumentException
	 *             if the jar isn't a plugin, or none of <tt>loaders</tt> can load its exercise
	 */
	public Plugin(final Path jarPath, final List<ExerciseLoader> loaders) throws IOException {
		this.jarPath = jarPath;
		loader = new URLClassLoader(new URL[] { jarPath.toUri().toURL() },
				Thread.currentThread().getContextClassLoader());

		try {
			descriptor = PluginDescriptor.read(loader, jarPath);

			final ExerciseLoader exerciseLoader = loaders.stream()
					.filter(candidate -> candidate.apiVersion() == descriptor.apiVersion()).findFirst()
					.orElseThrow(() -> new IllegalArgumentException(String.format(
							"%s needs plugin API version %d, which this ShootOFF doesn't support", jarPath,
							descriptor.apiVersion())));

			final Class<?> exerciseClass;
			try {
				exerciseClass = loader.loadClass(descriptor.exerciseClass());
			} catch (final ClassNotFoundException e) {
				throw new IllegalArgumentException(String.format("Configured exerciseClass (%s) was not found in %s",
						descriptor.exerciseClass(), jarPath), e);
			}

			entry = exerciseLoader.load(exerciseClass);
		} catch (final RuntimeException | LinkageError | IOException e) {
			// A mismatched build (a class the exercise's metadata needs is missing from its jar)
			// throws a LinkageError, typically NoClassDefFoundError, instead of an exception.
			loader.close();
			throw e;
		}
	}

	public URLClassLoader getLoader() {
		return loader;
	}

	public ExerciseEntry getEntry() {
		return entry;
	}

	public Path getJarPath() {
		return jarPath;
	}

	public int getApiVersion() {
		return descriptor.apiVersion();
	}

	public PluginType getType() {
		return entry.isProjectorOnly() ? PluginType.PROJECTOR_ONLY : PluginType.STANDARD;
	}

	@Override
	public int hashCode() {
		return jarPath.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof Plugin other && jarPath.equals(other.jarPath);
	}
}
