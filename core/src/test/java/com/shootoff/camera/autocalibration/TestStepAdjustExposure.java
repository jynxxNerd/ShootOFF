package com.shootoff.camera.autocalibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * The exposure step's baseline is the white screen's brightness, measured once the white has reached the
 * camera (spec §8 Revision 4, decision 2). On the owner's C270 and projector the white arrived after the step's
 * old 100 ms: "mean originally 91.7 … lowest 126.7".
 */
class TestStepAdjustExposure {
	// A camera whose exposure the step lowers or puts back
	private static final class ExposureCamera extends MockCamera {
		int decreases = 0;
		int resets = 0;

		@Override
		public boolean supportsExposureAdjustment() {
			return true;
		}

		@Override
		public boolean decreaseExposure() {
			decreases++;
			return true;
		}

		@Override
		public void resetExposure() {
			resets++;
		}
	}

	private final ExposureCamera camera = new ExposureCamera();
	private final List<String> backgrounds = new CopyOnWriteArrayList<>();
	private AutoCalibrationManager.AutoCalStep step;

	@BeforeAll
	static void loadOpenCv() {
		Loader.load(opencv_java.class);
	}

	@BeforeEach
	void setUp() {
		final AutoCalibrationManager manager = new AutoCalibrationManager(new CameraCalibrationListener() {
			@Override
			public void calibrate(Rect arenaBounds, Optional<Size> paper, boolean calibratedFromCanvas, long delay) {}

			@Override
			public void setArenaBackground(String resourceFilename) {
				backgrounds.add(String.valueOf(resourceFilename));
			}
		}, camera, false);
		step = manager.stepAdjustExposure;
		step.reset();
	}

	// A grey camera frame of the given brightness, as the step sees it (auto-calibration's frames are grey)
	private void frame(long timestamp, double brightness) {
		step.process(new Frame(new Mat(480, 640, CvType.CV_8UC1, new Scalar(brightness)), timestamp));
	}

	@Test
	void theBaselineIsTakenOnlyOnceTheWhiteScreenHasReachedTheCameraAndStoppedBrightening() {
		// The blank arena, as the paper step left it: the step asks for white
		frame(0, 40);
		assertEquals(List.of("white.png"), backgrounds);

		// Well past the old 100 ms, the white hasn't arrived; then it arrives over a few frames
		frame(33, 40);
		frame(133, 40);
		frame(166, 92);
		frame(200, 130);
		assertEquals(0, camera.decreases, "no baseline while the white is still brightening");

		// It stops brightening: that is the baseline, and the step starts lowering the exposure
		frame(233, 131);
		assertEquals(1, camera.decreases);

		frame(333, 118);
		frame(433, 106);
		frame(533, 95);
		frame(633, 85);
		frame(733, 76);

		assertTrue(step.completed());
		assertEquals(5, camera.decreases);
		assertEquals(0, camera.resets, "lowered, so the lowered exposure is kept");
	}

	@Test
	void aWhiteScreenThatNeverReachesTheCameraIsMeasuredAnywayAfterTwoSeconds() {
		frame(0, 40);
		for (long t = 33; t < AutoCalibrationManager.WHITE_SCREEN_TIMEOUT; t += 33)
			frame(t, 40);
		assertFalse(step.completed());

		frame(AutoCalibrationManager.WHITE_SCREEN_TIMEOUT, 40);

		// Too dark to lower: it gives up and puts the exposure back, as it always has
		assertTrue(step.completed());
		assertEquals(0, camera.decreases);
		assertEquals(1, camera.resets);
	}

	@Test
	void aRoomBrightEnoughThatTheWhiteBarelyShowsStillGetsItsBaselineOnceTheWhiteSettles() {
		frame(0, 110);
		frame(100, 112);
		frame(133, 139);
		frame(166, 140);

		assertEquals(1, camera.decreases);
	}
}
