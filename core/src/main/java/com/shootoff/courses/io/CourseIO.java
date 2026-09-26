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

package com.shootoff.courses.io;

import java.io.File;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.courses.Course;
import com.shootoff.courses.CourseTarget;

public class CourseIO {
	private static final Logger logger = LoggerFactory.getLogger(CourseIO.class);

	public static void saveCourse(Course course, final File courseFile) {
		CourseVisitor visitor;

		if (courseFile.getName().endsWith("course")) {
			visitor = new XMLCourseWriter(courseFile);
		} else {
			logger.error("Unknown course file type.");
			return;
		}

		if (course.getBackground().isPresent()) {
			visitor.visitBackground(course.getBackground().get().url(), course.getBackground().get().isResource());
		}

		for (final CourseTarget t : course.getTargets()) {
			visitor.visitTarget(t.file(), t.x(), t.y(), t.width(), t.height());
		}

		if (course.getResolution().isPresent()) {
			visitor.visitResolution(course.getResolution().get().getWidth(), course.getResolution().get().getHeight());
		}

		visitor.visitEnd();
	}

	public static Optional<Course> loadCourse(final File courseFile) {
		if (!courseFile.getName().endsWith("course")) {
			logger.error("Unknown course file type.");
			return Optional.empty();
		}

		return new XMLCourseReader(courseFile).load();
	}
}
