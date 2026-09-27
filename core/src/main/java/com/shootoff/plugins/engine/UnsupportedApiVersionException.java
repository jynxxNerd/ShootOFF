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

import java.nio.file.Path;

/**
 * A plugin jar for an API version none of the app's loaders runs, e.g. a v1 exercise in an app that runs
 * only v2 exercises. The jar isn't broken, so it is skipped quietly.
 */
public final class UnsupportedApiVersionException extends IllegalArgumentException {
	private static final long serialVersionUID = 1L;

	private final Path jarPath;
	private final int apiVersion;

	public UnsupportedApiVersionException(Path jarPath, int apiVersion) {
		super(String.format("%s needs plugin API version %d, which this ShootOFF doesn't support", jarPath,
				apiVersion));
		this.jarPath = jarPath;
		this.apiVersion = apiVersion;
	}

	public Path getJarPath() {
		return jarPath;
	}

	public int getApiVersion() {
		return apiVersion;
	}
}
