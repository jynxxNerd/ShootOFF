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

package com.shootoff.plugins;

import java.io.Serializable;
import java.util.Objects;

/**
 * Data about what an exercise is and who wrote it. A v2 exercise also says whether it only runs on
 * the projector arena; v1 exercises say that with their base class instead.
 *
 * @author phrack
 */
public class ExerciseMetadata implements Serializable {
	private static final long serialVersionUID = 1L;

	private final String name;
	private final String version;
	private final String creator;
	private final String description;
	private final boolean projectorOnly;

	public ExerciseMetadata(final String name, final String version, final String creator, final String description) {
		this(name, version, creator, description, false);
	}

	/**
	 * @param projectorOnly
	 *            <tt>true</tt> if the exercise only runs on the projector arena (v2 exercises)
	 */
	public ExerciseMetadata(final String name, final String version, final String creator, final String description,
			final boolean projectorOnly) {
		this.name = name;
		this.version = version;
		this.creator = creator;
		this.description = description;
		this.projectorOnly = projectorOnly;
	}

	public String getName() {
		return name;
	}

	public String getVersion() {
		return version;
	}

	public String getCreator() {
		return creator;
	}

	public String getDescription() {
		return description;
	}

	public boolean isProjectorOnly() {
		return projectorOnly;
	}

	@Override
	public int hashCode() {
		return Objects.hash(creator, description, name, version, projectorOnly);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj == null || getClass() != obj.getClass()) return false;
		final ExerciseMetadata other = (ExerciseMetadata) obj;
		return Objects.equals(creator, other.creator) && Objects.equals(description, other.description)
				&& Objects.equals(name, other.name) && Objects.equals(version, other.version)
				&& projectorOnly == other.projectorOnly;
	}

	@Override
	public String toString() {
		return name + " " + version + " " + creator;
	}
}
