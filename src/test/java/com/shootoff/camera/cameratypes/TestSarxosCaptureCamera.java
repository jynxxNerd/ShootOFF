package com.shootoff.camera.cameratypes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.videoio.VideoCapture;

public class TestSarxosCaptureCamera {
	@BeforeClass
	public static void loadOpenCV() {
		Loader.load(opencv_java.class);
	}

	@Test
	public void testFourccToStringDecodesMjpg() {
		assertEquals("MJPG", SarxosCaptureCamera.fourccToString(org.opencv.videoio.VideoWriter.fourcc('M', 'J', 'P', 'G')));
	}

	@Test
	public void testCaptureSettingsDoNotFailWhenCameraRejectsThem() {
		// An unopened capture rejects every property, like a camera that does not support MJPG
		final VideoCapture capture = new VideoCapture();

		SarxosCaptureCamera.applyCaptureSettings(capture);

		assertFalse(capture.isOpened());
	}
}
