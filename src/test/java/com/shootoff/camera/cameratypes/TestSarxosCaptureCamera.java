package com.shootoff.camera.cameratypes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.awt.Dimension;

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

	@Test
	public void testSetViewSizeOnUnopenedCameraDoesNotThrowAndGetViewSizeStaysSafe() {
		// SarxosCaptureCamera("name", index) creates a real, unopened VideoCapture without
		// requiring a webcam to be attached, unlike the no-arg "for testing" constructor.
		final SarxosCaptureCamera camera = new SarxosCaptureCamera("Test Camera", 0);

		// CameraManager/CheckableImageListCell call setViewSize before open(); this must not
		// throw even though the underlying VideoCapture rejects every property when unopened.
		camera.setViewSize(new Dimension(640, 480));

		final Dimension viewSize = camera.getViewSize();

		assertEquals(0.0, viewSize.getWidth(), 0.0);
		assertEquals(0.0, viewSize.getHeight(), 0.0);
	}
}
