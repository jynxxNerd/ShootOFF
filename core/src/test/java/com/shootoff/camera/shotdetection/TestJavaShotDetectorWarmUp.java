package com.shootoff.camera.shotdetection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;

import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CameraView;
import com.shootoff.camera.Frame;
import com.shootoff.camera.MockCamera;
import com.shootoff.camera.RecordingCameraView;
import com.shootoff.camera.shot.ScaledShot;
import com.shootoff.config.ConfigurationException;
import com.shootoff.config.Settings;

/**
 * Starting afresh (setFrameSize) is what a change of the area searched does: the detector then waits out the same
 * warm-up as at startup before it looks for shots again, so that averages seeded from one frame can't make one.
 */
class TestJavaShotDetectorWarmUp {
	private static final Scalar WHITE = new Scalar(255, 255, 255);

	private static final class CountingCamera extends MockCamera {
		volatile int frames = 0;

		@Override
		public int getFrameCount() {
			return frames;
		}
	}

	private final CountingCamera camera = new CountingCamera();
	private final RecordingCameraView view = new RecordingCameraView();
	private JavaShotDetector detector;

	@BeforeAll
	static void loadOpenCv() {
		Loader.load(opencv_java.class);
	}

	@BeforeEach
	void setUp() throws ConfigurationException {
		new Settings(new String[0]);
		final CameraManager manager = new CameraManager(camera, null, view);
		final boolean[][] sectors = new boolean[JavaShotDetector.SECTOR_ROWS][JavaShotDetector.SECTOR_COLUMNS];
		for (final boolean[] row : sectors) java.util.Arrays.fill(row, true);
		manager.setSectorStatuses(sectors);
		detector = new JavaShotDetector(manager, (CameraView) view);
	}

	private void frame(boolean spot) {
		final Mat mat = new Mat(480, 640, CvType.CV_8UC3, new Scalar(0, 0, 0));
		if (spot) mat.submat(new Rect(300, 200, 10, 10)).setTo(WHITE);
		detector.processFrame(new Frame(mat, System.currentTimeMillis()), true);
		camera.frames++;
	}

	private void frames(int count, boolean spot) {
		for (int i = 0; i < count; i++) frame(spot);
	}

	private Optional<ScaledShot> shot() throws InterruptedException {
		return view.awaitShot(500, TimeUnit.MILLISECONDS);
	}

	@Test
	void aSpotAfterWarmingUpIsAShot() throws InterruptedException {
		frames(10, false);
		frame(true);

		assertEquals(true, shot().isPresent());
	}

	@Test
	void startingAfreshWaitsOutTheWarmUpAgainBeforeAnyShot() throws InterruptedException {
		frames(10, false);
		detector.setFrameSize(640, 480);

		// A spot while it warms up again is not a shot, however many frames it lasts
		frame(false);
		frames(4, true);
		assertEquals(Optional.empty(), shot());

		// Warmed up, a new spot is
		frames(10, false);
		frame(true);
		assertEquals(true, shot().isPresent());
	}
}
