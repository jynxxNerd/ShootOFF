package com.shootoff.camera.cameratypes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.awt.Dimension;
import java.util.Optional;

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
		// An unopened capture rejects every property, like a camera that does not support the
		// requested format
		final VideoCapture capture = new VideoCapture();

		SarxosCaptureCamera.applyCaptureSettings(capture, Optional.empty());

		assertFalse(capture.isOpened());
	}

	@Test
	public void testPreferredFourccWithNoRequestedSizeIsYuyv() {
		assertEquals(org.opencv.videoio.VideoWriter.fourcc('Y', 'U', 'Y', 'V'),
				SarxosCaptureCamera.preferredFourcc(Optional.empty()));
	}

	@Test
	public void testPreferredFourccAt640x480IsYuyv() {
		assertEquals(org.opencv.videoio.VideoWriter.fourcc('Y', 'U', 'Y', 'V'),
				SarxosCaptureCamera.preferredFourcc(Optional.of(new Dimension(640, 480))));
	}

	@Test
	public void testPreferredFourccAt320x240IsYuyv() {
		assertEquals(org.opencv.videoio.VideoWriter.fourcc('Y', 'U', 'Y', 'V'),
				SarxosCaptureCamera.preferredFourcc(Optional.of(new Dimension(320, 240))));
	}

	@Test
	public void testPreferredFourccAt1280x720IsMjpg() {
		assertEquals(org.opencv.videoio.VideoWriter.fourcc('M', 'J', 'P', 'G'),
				SarxosCaptureCamera.preferredFourcc(Optional.of(new Dimension(1280, 720))));
	}

	@Test
	public void testPreferredFourccAt1280x960IsMjpg() {
		assertEquals(org.opencv.videoio.VideoWriter.fourcc('M', 'J', 'P', 'G'),
				SarxosCaptureCamera.preferredFourcc(Optional.of(new Dimension(1280, 960))));
	}

	@Test
	public void testPreferredFourccAt800x600IsMjpg() {
		assertEquals(org.opencv.videoio.VideoWriter.fourcc('M', 'J', 'P', 'G'),
				SarxosCaptureCamera.preferredFourcc(Optional.of(new Dimension(800, 600))));
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

	@Test
	public void testDisablingDynamicFramerateOnMissingDeviceReportsNoChange() {
		assertFalse(SarxosCaptureCamera.disableDynamicFramerate("/nonexistent/video99"));
	}
}
