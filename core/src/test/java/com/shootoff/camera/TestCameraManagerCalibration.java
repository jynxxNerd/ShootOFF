package com.shootoff.camera;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.imageio.ImageIO;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

import com.shootoff.calibration.CalibrationCamera;
import com.shootoff.camera.autocalibration.AutoCalibrationManager;
import com.shootoff.config.ConfigurationException;
import com.shootoff.config.Settings;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

/**
 * What calibrating changes on a camera, and Cancel puts back (spec §8 Revision 4, decision 1): the projection,
 * the perspective warp auto-calibration found, and the exposure.
 */
class TestCameraManagerCalibration {
	// A camera that remembers what was done to its exposure
	private static final class ExposureCamera extends MockCamera {
		OptionalDouble manual = OptionalDouble.empty();
		final List<String> exposure = new CopyOnWriteArrayList<>();

		@Override
		public OptionalDouble manualExposure() {
			return manual;
		}

		@Override
		public void restoreManualExposure(double value) {
			exposure.add("manual " + value);
			manual = OptionalDouble.of(value);
		}

		@Override
		public void resetExposure() {
			exposure.add("auto");
			manual = OptionalDouble.empty();
		}
	}

	private final ExposureCamera camera = new ExposureCamera();
	private final List<Rect> found = new CopyOnWriteArrayList<>();
	private CameraManager manager;

	@BeforeAll
	static void loadOpenCv() {
		Loader.load(opencv_java.class);
	}

	@BeforeEach
	void setUp() throws ConfigurationException {
		new Settings(new String[0]);
		camera.setViewSize(new Dimension(640, 480));
		manager = new CameraManager(camera, null, new RecordingCameraView());
		manager.setCalibrationManager(new CameraCalibrationListener() {
			@Override
			public void calibrate(Rect arenaBounds, Optional<Size> paper, boolean calibratedFromCanvas, long delay) {
				found.add(arenaBounds);
			}

			@Override
			public void setArenaBackground(String resourceFilename) {}
		});
	}

	// A 640x480 camera frame of a dim wall with the projected pattern at <tt>where</tt>
	private static BufferedImage frame(Rect where) throws IOException {
		final BufferedImage frame = new BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR);
		final Graphics2D g = frame.createGraphics();
		g.setColor(new Color(40, 40, 40));
		g.fillRect(0, 0, 640, 480);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(ImageIO.read(TestCameraManagerCalibration.class.getResource("/pattern.png")), (int) where.getMinX(),
				(int) where.getMinY(), (int) where.getWidth(), (int) where.getHeight(), null);
		g.dispose();
		return frame;
	}

	// Auto-calibrates as the camera's thread does, frame by frame, until the pattern at <tt>where</tt> is found;
	// then the projection is set, as CalibrationFlow.calibrated does
	private void autoCalibrate(Rect where) throws IOException {
		manager.enableAutoCalibration(false);
		final BufferedImage image = frame(where);
		for (long timestamp = 1000; !manager.cameraAutoCalibrated && timestamp < 10_000; timestamp += 300)
			manager.processFrame(new Frame(image, timestamp), true);
		assertTrue(manager.cameraAutoCalibrated, "the pattern wasn't found");
		manager.setProjectionBounds(found.get(found.size() - 1));
	}

	// What CalibrationFlow.start() does to the camera, then its cancel()
	private void recalibrateAndCancel() {
		manager.setCalibrating(true);
		manager.setProjectionBounds(null);
		manager.enableAutoCalibration(false);
		manager.disableAutoCalibration();
		manager.setCalibrating(false);
	}

	@Test
	void restoringPutsBackTheProjectionAndThePerspectiveWarpThatStartingCalibrationThrewAway() throws IOException {
		autoCalibrate(new Rect(100, 80, 420, 296));
		final Optional<Rect> bounds = manager.getProjectionBounds();
		final Mat warp = manager.acm.getPerspMat();
		assertNotNull(warp);
		final CalibrationCamera.Saved saved = manager.saveCalibration();

		recalibrateAndCancel();
		assertFalse(manager.cameraAutoCalibrated);
		assertNull(manager.acm.getPerspMat());
		assertEquals(Optional.empty(), manager.getProjectionBounds());

		manager.restoreCalibration(saved);

		assertTrue(manager.cameraAutoCalibrated);
		assertSame(warp, manager.acm.getPerspMat());
		assertEquals(bounds, manager.getProjectionBounds());
	}

	// Calibration's time limit gives a pattern found just before it time to finish (spec §8 Revision 4, decision 2)
	@Test
	void thePatternIsFoundFromTheFrameThatFindsItUntilAutoCalibrationStops() throws IOException {
		manager.enableAutoCalibration(false);
		assertFalse(manager.isPatternFound());

		// One frame finds the pattern; the paper step, which takes the next frames, hasn't run yet
		manager.processFrame(new Frame(frame(new Rect(100, 80, 420, 296)), 1000), true);
		assertTrue(manager.isPatternFound());
		assertFalse(manager.cameraAutoCalibrated);

		manager.disableAutoCalibration();
		assertFalse(manager.isPatternFound());
	}

	@Test
	void restoringPutsBackTheExposureTheExposureStepHadSet() {
		camera.manual = OptionalDouble.of(120);
		final CalibrationCamera.Saved saved = manager.saveCalibration();
		// The next calibration starts from auto exposure (CalculatedFPSCamera resets it for CALIBRATING)
		camera.resetExposure();

		manager.restoreCalibration(saved);

		assertEquals(List.of("auto", "manual 120.0"), camera.exposure);
	}

	@Test
	void restoringACameraThatExposedAutomaticallyPutsAutoExposureBack() {
		final CalibrationCamera.Saved saved = manager.saveCalibration();
		// The exposure step of the cancelled calibration had lowered it by hand
		camera.manual = OptionalDouble.of(90);

		manager.restoreCalibration(saved);

		assertEquals(List.of("auto"), camera.exposure);
		assertEquals(OptionalDouble.empty(), camera.manual);
	}

	@Test
	void restoringACameraThatWasNeverCalibratedLeavesItUncalibrated() {
		final CalibrationCamera.Saved saved = manager.saveCalibration();

		recalibrateAndCancel();
		manager.restoreCalibration(saved);

		assertFalse(manager.cameraAutoCalibrated);
		assertNull(manager.acm);
		assertEquals(Optional.empty(), manager.getProjectionBounds());
	}

	// Task 1's review: a Cancel on a camera that was never calibrated restores acm to null (restoreCalibration
	// puts back the null saveCalibration saw) without clearing isAutoCalibrating; a frame arriving in between,
	// as the camera's thread can, must not throw when processFrame reads isAutoCalibrating then acm
	@Test
	void aFrameArrivingAfterCancelRestoresANeverCalibratedAcmToNullDoesNotThrow() throws IOException {
		final CalibrationCamera.Saved saved = manager.saveCalibration();
		manager.enableAutoCalibration(false);
		manager.restoreCalibration(saved);

		assertDoesNotThrow(() -> manager.processFrame(new Frame(frame(new Rect(100, 80, 420, 296)), 1000), true));
	}

	// Cancel pressed while the camera's thread is still looking at a frame (a look takes 400-500 ms): whatever that
	// look finds belongs to the cancelled calibration and can't change the warp put back
	@Test
	void aLookStillUnderWayAtCancelCantChangeTheWarpPutBack() throws IOException {
		autoCalibrate(new Rect(100, 80, 420, 296));
		final Mat warp = manager.acm.getPerspMat();
		final CalibrationCamera.Saved saved = manager.saveCalibration();
		manager.setCalibrating(true);
		manager.setProjectionBounds(null);
		manager.enableAutoCalibration(false);
		// The camera's thread took this one for its frame just before Cancel
		final AutoCalibrationManager looking = manager.acm;

		manager.disableAutoCalibration();
		manager.restoreCalibration(saved);
		// Its look ends after the restore, finding the pattern somewhere else
		looking.processFrame(new Frame(frame(new Rect(60, 40, 420, 296)), 5000));

		assertTrue(manager.cameraAutoCalibrated);
		assertSame(warp, manager.acm.getPerspMat());
	}
}
