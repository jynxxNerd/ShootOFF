package com.shootoff.courses.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Before;
import org.junit.Test;

import com.shootoff.courses.Course;
import com.shootoff.courses.CourseBackground;
import com.shootoff.courses.CourseTarget;
import com.shootoff.geom.Size;

public class TestCourseIO {
	private static final String BACKGROUND_URL = "/arena/backgrounds/indoor_range.gif";

	private Course course;

	@Before
	public void setUp() {
		course = new Course(Optional.of(new CourseBackground(BACKGROUND_URL, true)),
				List.of(new CourseTarget(new File("targets/Reset.target"), 10, 100, 10, 1)),
				Optional.of(new Size(640, 360)));
	}

	@Test
	public void testXMLSerialization() {
		final File tempXMLCourse = new File("temp_course.course");
		CourseIO.saveCourse(course, tempXMLCourse);

		final Optional<Course> loaded = CourseIO.loadCourse(tempXMLCourse);

		assertTrue(loaded.isPresent());
		assertEquals(course.getBackground(), loaded.get().getBackground());
		assertEquals(course.getTargets(), loaded.get().getTargets());
		assertEquals(course.getResolution(), loaded.get().getResolution());

		if (!tempXMLCourse.delete()) System.err.println("Failed to delete " + tempXMLCourse.getPath());
	}

	@Test
	public void testCourseDoesntExist() {
		assertEquals(Optional.empty(), CourseIO.loadCourse(new File("does_not_exist.course")));
	}

	@Test
	public void testUnknownCourseExtension() {
		assertEquals(Optional.empty(), CourseIO.loadCourse(new File("does_not_exist.watisthis")));
	}

	@Test
	public void testEveryBundledCourseParses() throws IOException {
		final List<Path> files;
		try (Stream<Path> paths = Files.walk(Paths.get("courses"))) {
			files = paths.filter(p -> p.toString().endsWith(".course")).collect(Collectors.toList());
		}

		assertEquals(8, files.size());
		for (final Path file : files) {
			final Optional<Course> loaded = CourseIO.loadCourse(file.toFile());

			assertTrue(file.toString(), loaded.isPresent());
			assertFalse(file.toString(), loaded.get().getTargets().isEmpty());
			assertTrue(file.toString(), loaded.get().getResolution().isPresent());
			for (final CourseTarget target : loaded.get().getTargets()) {
				assertTrue(target.file() + " exists", target.file().isFile());
			}
		}
	}
}
