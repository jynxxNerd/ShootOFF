package com.shootoff.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.config.Settings;
import com.shootoff.config.ConfigurationException;

class TestCameraManagerDiagnostics {
	private RecordingCameraView view;
	private CameraManager cameraManager;

	@BeforeAll
	static void loadOpenCv() {
		Loader.load(opencv_java.class);
	}

	@BeforeEach
	void setUp() throws ConfigurationException {
		new Settings(new String[0]);
		view = new RecordingCameraView();
		cameraManager = new CameraManager(new MockCamera(), null, view);
	}

	@Test
	void brightnessWarningIsShownThenRemoved() throws InterruptedException {
		cameraManager.showBrightnessWarning();

		assertEquals(List.of("Warning: Excessive brightness"), view.diagnosticWarnings());
		assertTrue(view.awaitRemovedWarnings(1, 5, TimeUnit.SECONDS), "warning was never removed");
	}

	@Test
	void motionWarningIsShownThenRemoved() throws InterruptedException {
		cameraManager.showMotionWarning();

		assertEquals(List.of("Warning: Excessive motion -- Try reducing the camera exposure setting"),
				view.diagnosticWarnings());
		assertTrue(view.awaitRemovedWarnings(1, 5, TimeUnit.SECONDS), "warning was never removed");
	}
}
