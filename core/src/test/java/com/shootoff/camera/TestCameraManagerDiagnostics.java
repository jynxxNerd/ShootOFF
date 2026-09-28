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
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

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

	// A low frame rate says what the camera's exposure was, so the log tells a dark scene from a slow start
	// (spec §8 Revision 4, decision 3)
	@Test
	void aFrameRateTooLowForShotDetectionLogsTheCamerasExposure() {
		final CameraManager manager = new CameraManager(new MockCamera() {
			@Override
			public String exposureState() {
				return "exposure 1002 (auto, mode 3), exposure_dynamic_framerate 0";
			}
		}, null, new RecordingCameraView());
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		final Logger logger = (Logger) LoggerFactory.getLogger(CameraManager.class);
		logger.addAppender(appender);
		try {
			manager.newFPS(4.9);
		} finally {
			logger.detachAppender(appender);
		}

		assertTrue(appender.list.stream().anyMatch(
				e -> e.getFormattedMessage().equals("[MockCamera] exposure 1002 (auto, mode 3), exposure_dynamic_framerate 0")));
	}

	@Test
	void motionWarningIsShownThenRemoved() throws InterruptedException {
		cameraManager.showMotionWarning();

		assertEquals(List.of("Warning: Excessive motion -- Try reducing the camera exposure setting"),
				view.diagnosticWarnings());
		assertTrue(view.awaitRemovedWarnings(1, 5, TimeUnit.SECONDS), "warning was never removed");
	}
}
