package com.shootoff.camera.cameratypes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestV4l2Controls {
	@TempDir Path tempDir;

	@Test
	void missingDeviceHasNoControlValue() {
		final String device = tempDir.resolve("video99").toString();

		assertEquals(OptionalInt.empty(), V4l2Controls.getControl(device, V4l2Controls.EXPOSURE_DYNAMIC_FRAMERATE));
	}

	@Test
	void missingDeviceRejectsSet() {
		final String device = tempDir.resolve("video99").toString();

		assertFalse(V4l2Controls.setControl(device, V4l2Controls.EXPOSURE_DYNAMIC_FRAMERATE, 0));
	}

	@Test
	void nonVideoFileHasNoControlValue() throws IOException {
		final Path notADevice = Files.createFile(tempDir.resolve("not-a-camera"));

		assertEquals(OptionalInt.empty(),
				V4l2Controls.getControl(notADevice.toString(), V4l2Controls.EXPOSURE_DYNAMIC_FRAMERATE));
	}

	@Test
	void nonVideoFileRejectsSet() throws IOException {
		final Path notADevice = Files.createFile(tempDir.resolve("not-a-camera"));

		assertFalse(V4l2Controls.setControl(notADevice.toString(), V4l2Controls.EXPOSURE_DYNAMIC_FRAMERATE, 0));
	}

	@Test
	void controlIdMatchesTheKernelHeader() {
		// V4L2_CID_EXPOSURE_AUTO_PRIORITY = V4L2_CID_CAMERA_CLASS_BASE (0x009a0900) + 3,
		// the id v4l2-ctl reports for exposure_dynamic_framerate
		assertEquals(0x009a0903, V4l2Controls.EXPOSURE_DYNAMIC_FRAMERATE);
	}
}
