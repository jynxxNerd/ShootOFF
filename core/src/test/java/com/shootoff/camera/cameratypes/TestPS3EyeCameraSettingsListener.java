package com.shootoff.camera.cameratypes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class TestPS3EyeCameraSettingsListener {
	// Without the eyeCam native library (every non-Windows machine) the camera only logs that
	// the driver is missing, and close() has no device to release

	@Test
	void closingTheCameraClosesTheSettingsWindowOnce() {
		final PS3EyeCamera camera = new PS3EyeCamera();
		final AtomicInteger closings = new AtomicInteger();
		camera.setSettingsListener(new PS3EyeCamera.SettingsListener() {
			@Override
			public void fpsUpdated(double fps) {}

			@Override
			public void cameraClosing() {
				closings.incrementAndGet();
			}
		});

		camera.close();
		camera.close();

		assertEquals(1, closings.get());
	}

	@Test
	void closingWithoutASettingsWindowIsFine() {
		assertDoesNotThrow(() -> new PS3EyeCamera().close());
	}
}
