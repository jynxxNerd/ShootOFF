package com.shootoff.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.config.ScratchConfig;
import com.shootoff.config.Settings;

class TestCamerasSupervisor {
	private CamerasSupervisor cameras;

	@BeforeEach
	void setUp() throws Exception {
		cameras = new CamerasSupervisor(new Settings(ScratchConfig.emptyFile().getPath(), new String[0]));
	}

	// The Compose app starts a camera off the UI thread while the drill and reset walk the managers
	@Test
	void theManagersCanChangeWhileTheyAreWalked() {
		cameras.getCameraManagers().add(new CameraManager());

		for (final CameraManager manager : cameras.getCameraManagers()) {
			cameras.addStartedCameraManager(new CameraManager());
		}

		assertEquals(2, cameras.getCameraManagers().size());
	}

	@Test
	void aStartedManagerIsAddedAndDetectionIsOn() {
		cameras.setDetectingAll(false);
		final CameraManager manager = new CameraManager();

		cameras.addStartedCameraManager(manager);

		assertSame(manager, cameras.getCameraManagers().get(0));
		assertEquals(List.of(manager), cameras.getCameraManagers());
		assertTrue(cameras.areDetecting());
	}
}
