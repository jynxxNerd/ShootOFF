package com.shootoff.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

/** The JavaFX app rewrites shootoff.properties; what the Compose app keeps there must survive it. */
class TestConfigurationKeepsComposeKeys {
	private static final List<String> COMPOSE_KEYS = List.of("shootoff.arena.calibration.remember=true",
			"shootoff.arena.calibration.camera=C270", "shootoff.arena.calibration.feed=640.0x480.0",
			"shootoff.arena.calibration.screen=1280.0x720.0",
			"shootoff.arena.calibration.bounds=100.0,80.0,400.0,300.0",
			"shootoff.compose.unknown=a key no version of the JavaFX app knows");

	@BeforeAll
	static void setUpHome() {
		Loader.load(opencv_java.class);
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
	}

	@AfterEach
	void leaveAConfigurationCurrent() throws ConfigurationException {
		// Later tests in this JVM expect Configuration.getConfig() to be non-null
		new Configuration(new String[0]);
	}

	@Test
	void aJavaFxSaveKeepsTheComposeAppsKeys() throws Exception {
		final File file = ScratchConfig.emptyFile();
		Files.write(file.toPath(), COMPOSE_KEYS, StandardCharsets.ISO_8859_1);

		// What the JavaFX preferences window does when the user presses Save
		final Configuration config = new Configuration(file.getPath(), new String[0]);
		config.setMarkerRadius(8);
		assertTrue(config.writeConfigurationFile());

		final List<String> saved = Files.readAllLines(file.toPath(), StandardCharsets.ISO_8859_1);
		assertTrue(saved.containsAll(COMPOSE_KEYS), () -> "saved: " + saved);
		assertTrue(saved.contains("shootoff.markerradius=8"));
		assertEquals(Optional.of(new Rect(100, 80, 400, 300)),
				new Configuration(file.getPath(), new String[0]).getSavedCalibration().map(c -> c.bounds()));
		assertEquals(new Size(1280, 720), config.getSavedCalibration().get().screen());
	}
}
