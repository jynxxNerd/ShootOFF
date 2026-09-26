package com.shootoff.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestExercisePaths {
	@TempDir Path home;
	private String previousHome;

	@BeforeEach
	void setUp() throws IOException {
		previousHome = System.getProperty("shootoff.home");
		System.setProperty("shootoff.home", home.toString());
		Files.createDirectories(home.resolve("targets"));
		Files.writeString(home.resolve("targets/A.target"), "<target/>");
	}

	@AfterEach
	void tearDown() {
		if (previousHome == null) System.clearProperty("shootoff.home");
		else System.setProperty("shootoff.home", previousHome);
	}

	@Test
	void resourceNameDropsTheAtAndLeadingSlashes() {
		assertEquals("targets/ISSF.target", ExercisePaths.resourceName("@targets\\ISSF.target"));
		assertEquals("sounds/buzzer.wav", ExercisePaths.resourceName("/sounds/buzzer.wav"));
		assertEquals("backgrounds/black.png", ExercisePaths.resourceName("backgrounds/black.png"));
	}

	@Test
	void shootoffFileLooksInTheHomeThenInTheFolder() {
		final File a = home.resolve("targets/A.target").toFile();

		assertEquals(Optional.of(a), ExercisePaths.shootoffFile("targets/A.target", "targets"));
		assertEquals(Optional.of(a), ExercisePaths.shootoffFile("A.target", "targets"));
		assertEquals(Optional.empty(), ExercisePaths.shootoffFile("B.target", "targets"));
		assertEquals(Optional.empty(), ExercisePaths.shootoffFile("@targets/A.target", "targets"));
	}

	@Test
	void absolutePathIsUsedAsIs() {
		final File a = home.resolve("targets/A.target").toFile();

		assertEquals(Optional.of(a), ExercisePaths.shootoffFile(a.getAbsolutePath(), "sounds"));
		assertEquals(Optional.empty(), ExercisePaths.shootoffFile(home.resolve("missing.wav").toString(), "sounds"));
	}
}
