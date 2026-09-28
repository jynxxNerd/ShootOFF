package com.shootoff.camera.autocalibration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

import com.shootoff.camera.CameraCalibrationListener;
import com.shootoff.camera.Frame;
import com.shootoff.camera.MockCamera;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

/**
 * While auto-calibration looks for the pattern it keeps the camera's frame rate up (spec §8 Revision 4,
 * decision 3): at each look it asks the camera to hold its exposure to a frame period. The C270 dropped to
 * 3.8–4.9 FPS in a dark scene with exposure_dynamic_framerate already 0.
 */
class TestPatternSearchExposure {
	@BeforeAll
	static void loadOpenCv() {
		Loader.load(opencv_java.class);
	}

	@Test
	void eachLookForThePatternHoldsTheExposureToAFramePeriod() {
		final AtomicInteger limits = new AtomicInteger();
		final MockCamera camera = new MockCamera() {
			@Override
			public boolean limitExposureToFramePeriod() {
				limits.incrementAndGet();
				return true;
			}
		};
		final AutoCalibrationManager manager = new AutoCalibrationManager(new CameraCalibrationListener() {
			@Override
			public void calibrate(Rect arenaBounds, Optional<Size> paper, boolean calibratedFromCanvas, long delay) {}

			@Override
			public void setArenaBackground(String resourceFilename) {}
		}, camera, false);
		manager.reset();
		final Mat dark = new Mat(480, 640, CvType.CV_8UC3, new Scalar(10, 10, 10));

		// It looks every 250 ms of the frames' time; the frames between looks are skipped
		manager.processFrame(new Frame(dark, 1000));
		manager.processFrame(new Frame(dark, 1100));
		manager.processFrame(new Frame(dark, 1300));

		assertEquals(2, limits.get());
	}
}
