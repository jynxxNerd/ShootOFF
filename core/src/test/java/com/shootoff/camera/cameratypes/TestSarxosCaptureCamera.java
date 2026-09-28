package com.shootoff.camera.cameratypes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.awt.Dimension;
import java.util.Optional;
import java.util.OptionalInt;

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
	public void testTheNameIsKeptFromWhenTheCameraWasFound() {
		// Index 7 is past every webcam on the test machine, as a camera unplugged after it was listed is:
		// its name must not be looked up by index again
		final SarxosCaptureCamera camera = new SarxosCaptureCamera("Test Camera", 7);

		assertEquals("Test Camera", camera.getName());
	}

	@Test
	public void testDisablingDynamicFramerateOnMissingDeviceReportsNoChange() {
		assertFalse(SarxosCaptureCamera.disableDynamicFramerate("/nonexistent/video99"));
	}

	// 10000 (V4L2 exposure units per second) / 30 FPS = 333.3, floored to 333
	@Test
	public void testAFramePeriodAtThirtyFpsIsThreeThirtyThree() {
		assertEquals(333, SarxosCaptureCamera.framePeriodExposure(30), 0);
	}

	// An unknown frame rate (0, or NaN/negative) is taken as 30 FPS
	@Test
	public void testAnUnknownFrameRateIsTakenAsThirty() {
		assertEquals(333, SarxosCaptureCamera.framePeriodExposure(0), 0);
	}

	@Test
	public void testAnUnopenedCamerasExposureIsNeverHeld() {
		final SarxosCaptureCamera camera = new SarxosCaptureCamera("Test Camera", 7);

		assertFalse(camera.limitExposureToFramePeriod());
		camera.releaseExposureLimit();
		assertEquals("", camera.exposureState());
	}

	@Test
	public void testTheExposureStateSaysTheExposureItsModeAndTheDynamicFramerate() {
		assertEquals("exposure 1002 (auto, mode 3), exposure_dynamic_framerate 0",
				SarxosCaptureCamera.describeExposure(1002, 3, OptionalInt.of(0)));
		assertEquals("exposure 333 (manual), exposure_dynamic_framerate unknown",
				SarxosCaptureCamera.describeExposure(333, 1, OptionalInt.empty()));
	}

	// Plan 9's hardware check: the line said 30.0 FPS, the estimate's starting value, while the covered C270 gave a
	// frame every 2 s. It counts the frames since the camera opened instead (spec §8 Revision 5, decision 4).
	@Test
	public void testTheFrameRateIsMeasuredFromTheFramesSinceTheCameraOpened() {
		assertEquals("30.0 FPS measured (90 frames in 3000 ms)", SarxosCaptureCamera.describeFrameRate(90, 3000));
		assertEquals("0.7 FPS measured (2 frames in 3000 ms)", SarxosCaptureCamera.describeFrameRate(2, 3000));
	}

	@Test
	public void testNoTimeSinceOpeningIsNoFrameRate() {
		assertEquals("0.0 FPS measured (0 frames in 0 ms)", SarxosCaptureCamera.describeFrameRate(0, 0));
	}
}
