package com.shootoff.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

class TestSavedCalibrationSettings {
	private static final SavedCalibration SAVED = new SavedCalibration("UVC Camera (046d:0825) /dev/video0",
			new Size(640, 480), new Size(1280, 720), new Rect(101.5, 80, 400, 300), Optional.of(new Size(11, 8.5)), false);

	@BeforeAll
	static void setUpHome() {
		// Writing always stores the (empty) webcam list; reading that back enumerates cameras through OpenCV
		Loader.load(opencv_java.class);
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
	}

	private static Settings settings(File file) throws Exception {
		return new Settings(file.getPath(), new String[0]);
	}

	@Test
	void aNewFileRemembersNothing() throws Exception {
		final Settings settings = settings(ScratchConfig.emptyFile());

		assertFalse(settings.rememberCalibration());
		assertEquals(Optional.empty(), settings.getSavedCalibration());
	}

	@Test
	void aRememberedCalibrationReadsBackUnchanged() throws Exception {
		final File file = ScratchConfig.emptyFile();
		final Settings written = settings(file);
		written.setRememberCalibration(true);
		written.setSavedCalibration(SAVED);
		assertTrue(written.writeConfigurationFile());

		final Settings read = settings(file);

		assertTrue(read.rememberCalibration());
		assertEquals(Optional.of(SAVED), read.getSavedCalibration());
	}

	@Test
	void aManualBoxIsMarkedAsOneAndAnythingElseIsNot() throws Exception {
		final File file = ScratchConfig.emptyFile();
		final Settings written = settings(file);
		written.setRememberCalibration(true);
		written.setSavedCalibration(SAVED);
		written.writeConfigurationFile();
		assertFalse(Files.readString(file.toPath(), StandardCharsets.ISO_8859_1).contains("shootoff.arena.calibration.manual"));

		final SavedCalibration box = new SavedCalibration(SAVED.camera(), SAVED.feed(), SAVED.screen(), SAVED.bounds(),
				Optional.empty(), true);
		written.setSavedCalibration(box);
		written.writeConfigurationFile();

		assertTrue(Files.readAllLines(file.toPath(), StandardCharsets.ISO_8859_1).contains("shootoff.arena.calibration.manual=true"));
		assertEquals(Optional.of(box), settings(file).getSavedCalibration());
	}

	@Test
	void withRememberOffAndNothingSavedNoCalibrationKeyIsWritten() throws Exception {
		final File file = ScratchConfig.emptyFile();
		final Settings settings = settings(file);
		settings.setRememberCalibration(true);
		settings.setSavedCalibration(SAVED);
		settings.writeConfigurationFile();

		settings.setRememberCalibration(false);
		settings.setSavedCalibration(null);
		settings.writeConfigurationFile();

		assertFalse(Files.readString(file.toPath(), StandardCharsets.ISO_8859_1).contains("shootoff.arena.calibration."));
	}

	@Test
	void anUnreadableSavedCalibrationIsDroppedNotAnError() throws Exception {
		final File file = ScratchConfig.emptyFile();
		Files.write(file.toPath(), List.of("shootoff.arena.calibration.remember=true",
				"shootoff.arena.calibration.camera=C270", "shootoff.arena.calibration.feed=640x480",
				"shootoff.arena.calibration.screen=wide", "shootoff.arena.calibration.bounds=1,2,3,4"),
				StandardCharsets.ISO_8859_1);

		final Settings settings = settings(file);

		assertTrue(settings.rememberCalibration());
		assertEquals(Optional.empty(), settings.getSavedCalibration());
	}

	@Test
	void aKeyThisVersionDoesntKnowSurvivesASave() throws Exception {
		final File file = ScratchConfig.emptyFile();
		Files.write(file.toPath(), List.of("shootoff.markerradius=6", "shootoff.compose.future=kept as is"),
				StandardCharsets.ISO_8859_1);

		final Settings settings = settings(file);
		settings.setMarkerRadius(7);
		settings.writeConfigurationFile();

		final String saved = Files.readString(file.toPath(), StandardCharsets.ISO_8859_1);
		assertTrue(saved.contains("shootoff.compose.future=kept as is"));
		assertTrue(saved.contains("shootoff.markerradius=7"));
	}
}
