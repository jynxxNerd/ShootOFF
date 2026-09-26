package com.shootoff.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.shot.ShotColor;
import com.shootoff.geom.Point;

class TestSettings {
	// A shootoff.properties as today's app writes it, minus the webcam keys (reading those
	// enumerates real cameras)
	private static final List<String> EXISTING_FILE = List.of(
			"shootoff.firstrun=false",
			"shootoff.errorreporting=false",
			"shootoff.markerradius=6",
			"shootoff.ignorelasercolor=red",
			"shootoff.redlasersound.use=false",
			"shootoff.redlasersound=sounds/walther_ppq.wav",
			"shootoff.greenlasersound.use=false",
			"shootoff.greenlasersound=sounds/walther_ppq.wav",
			"shootoff.virtualmagazine.use=true",
			"shootoff.virtualmagazine.capacity=15",
			"shootoff.malfunctions.use=true",
			"shootoff.malfunctions.probability=5.5",
			"shootoff.arena.x=1920.0",
			"shootoff.arena.y=0.0",
			"shootoff.diagnosticmessages.chime.muted=Warning: Excessive brightness",
			"shootoff.webcams.distances=Cam A|300",
			"shootoff.arena.calibrated.behavior=CROP",
			"shootoff.arena.show.markers=true",
			"shootoff.arena.calibrated.exposure=false",
			"shootoff.arena.notified.perspective=true",
			"shootoff.poiadjust.x=1.5",
			"shootoff.poiadjust.y=-2.25");

	@TempDir
	Path tempDir;

	@BeforeAll
	static void setUpHome() {
		// Writing always stores the (empty) webcam list; reading that back enumerates cameras
		// through OpenCV
		Loader.load(opencv_java.class);
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
	}

	private File existingFile() throws IOException {
		final Path file = tempDir.resolve("shootoff.properties");
		Files.write(file, EXISTING_FILE, StandardCharsets.ISO_8859_1);
		return file.toFile();
	}

	@Test
	void readsEveryKeyOfAnExistingPropertiesFile() throws Exception {
		final Settings settings = new Settings(existingFile().getPath(), new String[0]);

		assertFalse(settings.isFirstRun());
		assertFalse(settings.useErrorReporting());
		assertEquals(6, settings.getMarkerRadius());
		assertTrue(settings.ignoreLaserColor());
		assertEquals(Optional.of(ShotColor.RED), settings.getIgnoreLaserColor());
		assertFalse(settings.useRedLaserSound());
		assertTrue(settings.useVirtualMagazine());
		assertEquals(15, settings.getVirtualMagazineCapacity());
		assertTrue(settings.useMalfunctions());
		assertEquals(5.5f, settings.getMalfunctionsProbability(), 0.001f);
		assertEquals(Optional.of(new Point(1920.0, 0.0)), settings.getArenaPosition());
		assertTrue(settings.isChimeMuted("Warning: Excessive brightness"));
		assertEquals(Optional.of(300), settings.getCameraDistance("Cam A"));
		assertEquals(CalibrationOption.CROP, settings.getCalibratedFeedBehavior());
		assertTrue(settings.showArenaShotMarkers());
		assertFalse(settings.autoAdjustExposure());
		assertTrue(settings.showedPerspectiveMessage());
		assertTrue(settings.isAdjustingPOI());
		assertEquals(Optional.of(1.5), settings.getPOIAdjustmentX());
		assertEquals(Optional.of(-2.25), settings.getPOIAdjustmentY());
	}

	@Test
	void writtenSettingsReadBackUnchanged() throws Exception {
		final File file = existingFile();
		final Settings written = new Settings(file.getPath(), new String[0]);
		written.setMarkerRadius(9);
		written.setArenaPosition(12.5, 340.0);
		written.setCalibratedFeedBehavior(CalibrationOption.EVERYWHERE);
		written.muteMessageChime("Another message");
		assertTrue(written.writeConfigurationFile());

		final Settings read = new Settings(file.getPath(), new String[0]);

		// Changed values
		assertEquals(9, read.getMarkerRadius());
		assertEquals(Optional.of(new Point(12.5, 340.0)), read.getArenaPosition());
		assertEquals(CalibrationOption.EVERYWHERE, read.getCalibratedFeedBehavior());
		assertTrue(read.isChimeMuted("Another message"));
		// Values carried through from the original file
		assertFalse(read.useErrorReporting());
		assertEquals(Optional.of(ShotColor.RED), read.getIgnoreLaserColor());
		assertTrue(read.useVirtualMagazine());
		assertEquals(15, read.getVirtualMagazineCapacity());
		assertTrue(read.useMalfunctions());
		assertEquals(5.5f, read.getMalfunctionsProbability(), 0.001f);
		assertTrue(read.isChimeMuted("Warning: Excessive brightness"));
		assertEquals(Optional.of(300), read.getCameraDistance("Cam A"));
		assertTrue(read.showArenaShotMarkers());
		assertFalse(read.autoAdjustExposure());
		assertTrue(read.showedPerspectiveMessage());
		assertTrue(read.isAdjustingPOI());
		assertEquals(Optional.of(1.5), read.getPOIAdjustmentX());
		assertEquals(Optional.of(-2.25), read.getPOIAdjustmentY());
	}
}
