package com.shootoff.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import com.shootoff.camera.shot.ScaledShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.camera.shotdetection.FrameProcessingShotDetector;
import com.shootoff.camera.shotdetection.ShotDetector;
import com.shootoff.config.ConfigurationException;
import com.shootoff.config.Settings;
import com.shootoff.geom.Rect;

/**
 * Where the camera looks for shots once the arena is calibrated (spec §9, Revision 1): with "Only detect shots in
 * projector bounds", the projection while the camera's view has no targets, the whole frame while it has some.
 */
class TestCameraManagerDetectionArea {
	private static final Rect PROJECTION = new Rect(100, 40, 200, 200);

	// A detector that writes down the size of each frame it is given and each time it starts afresh, and can
	// find a shot at a spot in the frame, running something first (a change landing mid-frame)
	private static final class AreaDetector extends FrameProcessingShotDetector {
		final List<String> frames = new CopyOnWriteArrayList<>();
		final AtomicInteger restarts = new AtomicInteger();
		volatile double[] shotAt = null;
		volatile Runnable beforeShot = () -> {};

		AreaDetector(CameraManager cameraManager, CameraView cameraView) {
			super(cameraManager, cameraView);
		}

		@Override
		public void processFrame(Frame frame, boolean isDetecting) {
			frames.add(frame.getOriginalMat().cols() + "x" + frame.getOriginalMat().rows());
			final double[] at = shotAt;
			if (at != null) {
				beforeShot.run();
				addShot(ShotColor.RED, at[0], at[1], frame.getTimestamp(), true);
			}
		}

		@Override
		public void setFrameSize(int width, int height) {
			restarts.incrementAndGet();
		}

		@Override
		protected boolean handlesBounds() {
			return true;
		}
	}

	private static final class AreaCamera extends MockCamera {
		AreaDetector detector;

		@Override
		public ShotDetector getPreferredShotDetector(CameraManager cameraManager, CameraView cameraView) {
			detector = new AreaDetector(cameraManager, cameraView);
			return detector;
		}
	}

	private final AreaCamera camera = new AreaCamera();
	private final RecordingCameraView view = new RecordingCameraView();
	private CameraManager manager;

	@BeforeAll
	static void loadOpenCv() {
		Loader.load(opencv_java.class);
	}

	@BeforeEach
	void setUp() throws ConfigurationException {
		new Settings(new String[0]);
		manager = new CameraManager(camera, null, view);
		manager.setProjectionBounds(PROJECTION);
	}

	private void frame() {
		manager.newFrame(new Frame(new Mat(480, 640, CvType.CV_8UC3), System.currentTimeMillis()), false);
	}

	private ScaledShot nextShot() throws InterruptedException {
		return view.awaitShot(5, TimeUnit.SECONDS).orElseThrow(() -> new AssertionError("no shot reached the view"));
	}

	@Test
	void onlyInBoundsLooksAtTheProjectionWhileTheViewHasNoTargetsAndTheWholeFrameWhileItHasSome() {
		manager.setLimitDetectProjection(true);

		frame();
		view.setHasTargets(true);
		frame();
		view.setHasTargets(false);
		frame();

		assertEquals(List.of("200x200", "640x480", "200x200"), camera.detector.frames);
		assertEquals(Optional.of(PROJECTION), manager.getDetectionArea());
		view.setHasTargets(true);
		assertEquals(Optional.empty(), manager.getDetectionArea());
	}

	@Test
	void everywhereLooksAtTheWholeFrameAndCropAtTheProjectionWhateverTheTargets() {
		view.setHasTargets(true);
		frame();
		view.setHasTargets(false);
		frame();

		manager.setCropFeedToProjection(true);
		frame();
		view.setHasTargets(true);
		frame();

		assertEquals(List.of("640x480", "640x480", "200x200", "200x200"), camera.detector.frames);
	}

	@Test
	void theDetectorStartsAfreshOnlyWhenTheAreaChanges() {
		manager.setLimitDetectProjection(true);
		final int before = camera.detector.restarts.get();

		frame();
		frame();
		assertEquals(before + 1, camera.detector.restarts.get());

		view.setHasTargets(true);
		frame();
		frame();
		assertEquals(before + 2, camera.detector.restarts.get());
	}

	@Test
	void aShotIsOffsetByTheAreaItWasFoundInEvenIfTheTargetsChangeMeanwhile() throws InterruptedException {
		manager.setLimitDetectProjection(true);

		// Found in the projection's sub-image: offset onto the feed, though a target arrives before it is added
		camera.detector.shotAt = new double[] { 10, 10 };
		camera.detector.beforeShot = () -> view.setHasTargets(true);
		frame();
		final ScaledShot inProjection = nextShot();
		assertEquals(110, inProjection.getX(), 0.001);
		assertEquals(50, inProjection.getY(), 0.001);

		// Found in the whole frame: where it is, though the last target goes before it is added
		camera.detector.shotAt = new double[] { 500, 400 };
		camera.detector.beforeShot = () -> view.setHasTargets(false);
		frame();
		final ScaledShot inFrame = nextShot();
		assertEquals(500, inFrame.getX(), 0.001);
		assertEquals(400, inFrame.getY(), 0.001);
	}
}
