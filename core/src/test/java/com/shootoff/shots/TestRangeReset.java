package com.shootoff.shots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CameraView;
import com.shootoff.camera.CamerasSupervisor;
import com.shootoff.camera.DiagnosticMessage;
import com.shootoff.camera.MockCamera;
import com.shootoff.camera.shot.ScaledShot;
import com.shootoff.config.Settings;
import com.shootoff.geom.Rect;

class TestRangeReset {
	private final List<String> events = new ArrayList<>();
	private final List<Runnable> scheduled = new ArrayList<>();
	private final List<Long> delays = new ArrayList<>();
	private final AtomicBoolean calibrating = new AtomicBoolean(false);
	private CamerasSupervisor cameras;
	private CameraManager first;
	private CameraManager second;
	private RangeReset reset;

	@BeforeEach
	void setUp() throws Exception {
		final Settings settings = new Settings(new String[0]);
		cameras = new CamerasSupervisor(settings);
		first = new CameraManager(new MockCamera(), null, new RecordingView("first"));
		second = new CameraManager(new MockCamera(), null, new RecordingView("second"));
		cameras.getCameraManagers().add(first);
		cameras.getCameraManagers().add(second);
		cameras.setDetectingAll(true);
		reset = new RangeReset(cameras, calibrating::get, (task, delayMillis) -> {
			scheduled.add(task);
			delays.add(delayMillis);
		});
	}

	@Test
	void resetClearsTheCamerasThenResetsTheExerciseThenPausesDetectionForASecond() {
		reset.reset(() -> events.add("exercise"));

		assertEquals(List.of("first reset", "second reset", "exercise"), events);
		assertFalse(first.isDetecting());
		assertFalse(second.isDetecting());
		assertEquals(List.of(RangeReset.DETECTION_PAUSE_MILLIS), delays);

		scheduled.get(0).run();

		assertTrue(first.isDetecting());
		assertTrue(second.isDetecting());
	}

	@Test
	void aCameraThatWasAlreadyOffStaysOff() {
		second.setDetecting(false);

		reset.disableShotDetection(250);
		scheduled.get(0).run();

		assertTrue(first.isDetecting());
		assertFalse(second.isDetecting());
		assertEquals(List.of(250L), delays);
	}

	@Test
	void detectionStaysOffWhileCalibrating() {
		reset.disableShotDetection(1000);
		calibrating.set(true);
		scheduled.get(0).run();

		assertFalse(first.isDetecting());
		assertFalse(second.isDetecting());
	}

	@Test
	void nothingHappensWhileAnExerciseHasDetectionPaused() {
		cameras.setDetectingAll(false);

		reset.disableShotDetection(1000);

		assertTrue(scheduled.isEmpty());
		assertFalse(first.isDetecting());
	}

	// A camera view that writes down resets
	private final class RecordingView implements CameraView {
		private final String name;

		RecordingView(String name) {
			this.name = name;
		}

		@Override
		public void reset() {
			events.add(name + " reset");
		}

		@Override
		public void addShot(ScaledShot shot) {}

		@Override
		public DiagnosticMessage addDiagnosticWarning(String message) {
			return () -> {};
		}

		@Override
		public void clearShots() {}

		@Override
		public void close() {}

		@Override
		public void setCameraManager(CameraManager cameraManager) {}

		@Override
		public void updateBackground(BufferedImage frame, Optional<Rect> projectionBounds) {}

		@Override
		public boolean hasTargets() {
			return false;
		}
	}
}
