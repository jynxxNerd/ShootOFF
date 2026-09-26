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

package com.shootoff.courses;

import java.util.List;
import java.util.Optional;

import com.shootoff.geom.Size;

/**
 * A saved projector arena: its background, its targets with their positions and sizes, and the
 * arena's size when it was saved.
 */
public class Course {
	private final Optional<CourseBackground> background;
	private final List<CourseTarget> targets;
	private final Optional<Size> resolution;

	public Course(Optional<CourseBackground> background, List<CourseTarget> targets, Optional<Size> resolution) {
		this.background = background;
		this.targets = List.copyOf(targets);
		this.resolution = resolution;
	}

	public Optional<CourseBackground> getBackground() {
		return background;
	}

	public List<CourseTarget> getTargets() {
		return targets;
	}

	/**
	 * The dimensions of the arena when the course was saved.
	 *
	 * @return Optional.empty for courses saved prior to 3.7
	 */
	public Optional<Size> getResolution() {
		return resolution;
	}
}
