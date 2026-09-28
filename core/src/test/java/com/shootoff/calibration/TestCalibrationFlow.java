package com.shootoff.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.camera.perspective.PerspectiveManager;
import com.shootoff.config.CalibrationOption;
import com.shootoff.config.ConfigurationException;
import com.shootoff.config.Settings;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

class TestCalibrationFlow {
	// What the camera, the user interface and the exercise were asked to do, in order
	private final List<String> events = new ArrayList<>();
	private final List<Timer> timers = new ArrayList<>();
	private Settings settings;
	private FakeCamera camera;
	private FakeView view;
	private boolean projectorExerciseRunning;
	private Optional<Runnable> headlessTimeout = Optional.empty();

	private record Timer(Runnable task, long delayMillis, CompletableFuture<Void> future) {}

	private final class FakeCamera implements CalibrationCamera {
		Optional<Rect> projectionBounds = Optional.of(new Rect(1, 2, 3, 4));

		@Override
		public String getName() {
			return "Fake camera";
		}

		@Override
		public int getFeedWidth() {
			return 640;
		}

		@Override
		public int getFeedHeight() {
			return 480;
		}

		@Override
		public Optional<Rect> getProjectionBounds() {
			return projectionBounds;
		}

		@Override
		public void setProjectionBounds(Rect bounds) {
			projectionBounds = Optional.ofNullable(bounds);
			events.add("camera bounds " + bounds);
		}

		@Override
		public void setCalibrating(boolean isCalibrating) {
			events.add("camera calibrating " + isCalibrating);
		}

		@Override
		public void setDetecting(boolean isDetecting) {
			events.add("camera detecting " + isDetecting);
		}

		@Override
		public void enableAutoCalibration(boolean calculateFrameDelay) {
			events.add("camera looks for the pattern");
		}

		@Override
		public void disableAutoCalibration() {
			events.add("camera stops looking");
		}

		@Override
		public void setCropFeedToProjection(boolean cropFeed) {
			events.add("camera crops " + cropFeed);
		}

		@Override
		public void setLimitDetectProjection(boolean limitDetection) {
			events.add("camera limits detection " + limitDetection);
		}
	}

	private final class FakeView implements CalibrationFlow.View {
		boolean fullScreen = true;
		Optional<Rect> box = Optional.empty();
		CalibrationOption behavior = CalibrationOption.ONLY_IN_BOUNDS;

		@Override
		public boolean isArenaFullScreen() {
			return fullScreen;
		}

		@Override
		public void setArenaShotsVisible(boolean visible) {
			events.add("arena shots visible " + visible);
		}

		@Override
		public void setCalibrating(boolean calibrating) {
			events.add("calibrate button calibrating " + calibrating);
		}

		@Override
		public void calibrationStarted() {
			events.add("arena hides its targets");
		}

		@Override
		public void saveArenaBackground() {
			events.add("save background");
		}

		@Override
		public void restoreArenaBackground() {
			events.add("restore background");
		}

		@Override
		public void showPattern() {
			events.add("show pattern");
		}

		@Override
		public void showMessage(CalibrationFlow.Message message) {
			events.add("show " + message);
		}

		@Override
		public void hideMessage(CalibrationFlow.Message message) {
			events.add("hide " + message);
		}

		@Override
		public void showCalibratingFeed() {
			events.add("show calibrating feed");
		}

		@Override
		public void restoreSelectedView() {
			events.add("restore selected view");
		}

		@Override
		public void showManualBox() {
			box = Optional.of(new Rect(75, 75, 150, 150));
			events.add("show box");
		}

		@Override
		public Optional<Rect> manualBox() {
			return box;
		}

		@Override
		public void removeManualBox() {
			if (box.isPresent()) events.add("remove box");
			box = Optional.empty();
		}

		@Override
		public void projectionCalibrated(Rect canvasBounds) {
			events.add("projection on the canvas " + canvasBounds);
		}

		@Override
		public CalibrationOption calibratedFeedBehavior() {
			return behavior;
		}

		@Override
		public Size arenaResolution() {
			return new Size(1280, 720);
		}

		@Override
		public void calibrated(Optional<PerspectiveManager> perspectiveManager) {
			events.add("calibrated" + (perspectiveManager.isPresent() ? " with perspective" : ""));
		}

		@Override
		public void runOnUiThread(Runnable action) {
			events.add("(UI thread)");
			action.run();
		}
	}

	private CalibrationFlow flow() {
		return new CalibrationFlow(camera, view, () -> {
			if (!projectorExerciseRunning) return Optional.empty();

			events.add("stop exercise");
			projectorExerciseRunning = false;
			return Optional.of(() -> {
				events.add("restart exercise");
				projectorExerciseRunning = true;
			});
		}, settings, (task, delayMillis) -> {
			final CompletableFuture<Void> future = new CompletableFuture<>();
			timers.add(new Timer(task, delayMillis, future));
			return future;
		}, headlessTimeout);
	}

	// Runs the timers that are due after delayMillis and haven't been cancelled
	private void runTimers(long delayMillis) {
		for (final Timer timer : List.copyOf(timers)) {
			if (timer.delayMillis() == delayMillis && !timer.future().isCancelled() && !timer.future().isDone()) {
				timer.future().complete(null);
				timer.task().run();
			}
		}
	}

	@BeforeEach
	void setUp() throws ConfigurationException {
		// Feed 640 x 480 shown at 1280 x 960: the canvas is twice the feed
		settings = new Settings(new String[0]);
		settings.setDisplayResolution(1280, 960);
		settings.setShowArenaShotMarkers(false);
		camera = new FakeCamera();
		view = new FakeView();
	}

	@Test
	void startingOnAFullScreenArenaStopsTheDrillBeforeSavingTheBackgroundAndShowingThePattern() {
		projectorExerciseRunning = true;
		final CalibrationFlow flow = flow();

		flow.start();

		assertTrue(flow.isCalibrating());
		assertEquals(List.of("stop exercise", "arena shots visible false", "calibrate button calibrating true",
				"camera calibrating true", "camera bounds null", "arena hides its targets", "save background",
				"show pattern", "camera looks for the pattern", "show AUTO_CALIBRATING"), events);
		assertEquals(List.of(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT),
				timers.stream().map(Timer::delayMillis).toList());
	}

	@Test
	void autoCalibrationSavesTheBoundsThenRestoresTheBackgroundBeforeRestartingTheDrill() {
		projectorExerciseRunning = true;
		final CalibrationFlow flow = flow();
		flow.start();
		events.clear();

		// The camera found the pattern, in feed coordinates
		flow.calibrated(new Rect(100, 50, 400, 300), Optional.empty(), false);

		assertFalse(flow.isCalibrating());
		assertEquals(List.of("projection on the canvas " + new Rect(200, 100, 800, 600), "camera crops false",
				"camera limits detection true", "camera bounds " + new Rect(100, 50, 400, 300), "camera stops looking",
				"calibrate button calibrating false", "hide AUTO_CALIBRATING", "restore selected view", "calibrated",
				"restore background", "camera calibrating false", "camera detecting false", "arena shots visible false",
				"restart exercise"), events);
		assertTrue(timers.get(0).future().isCancelled(), "the auto-calibration timeout");

		events.clear();
		runTimers(CalibrationFlow.DETECTION_RESTART_DELAY);
		assertEquals(List.of("camera detecting true"), events);
	}

	@Test
	void aWindowedArenaIsAskedToGoFullScreenAndThenAutoCalibrates() {
		view.fullScreen = false;
		final CalibrationFlow flow = flow();

		flow.start();
		assertEquals(List.of("arena shots visible false", "calibrate button calibrating true", "camera calibrating true",
				"camera bounds null", "show FULL_SCREEN_REQUEST"), events);

		events.clear();
		view.fullScreen = true;
		flow.setFullScreen(true);
		assertEquals(List.of("hide FULL_SCREEN_REQUEST"), events);

		events.clear();
		runTimers(CalibrationFlow.FULL_SCREEN_SETTLE_DELAY);
		assertEquals(List.of("(UI thread)", "arena hides its targets", "save background", "show pattern",
				"camera looks for the pattern", "show AUTO_CALIBRATING"), events);
	}

	@Test
	void leavingFullScreenPausesAutoCalibrationAndReturningKeepsTheSavedBackground() {
		final CalibrationFlow flow = flow();
		flow.setFullScreen(true);
		events.clear();

		flow.setFullScreen(false);
		assertEquals(List.of("camera stops looking", "hide AUTO_CALIBRATING", "show FULL_SCREEN_REQUEST"), events);

		events.clear();
		flow.setFullScreen(true);
		runTimers(CalibrationFlow.FULL_SCREEN_SETTLE_DELAY);
		// The pattern is still the background: saving now would save the pattern
		assertFalse(events.contains("save background"));
		assertTrue(events.contains("show pattern"));
	}

	@Test
	void aTimeoutOpensTheManualBoxAndStoppingCalibratesToIt() {
		final CalibrationFlow flow = flow();
		flow.setFullScreen(true);
		events.clear();

		runTimers(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT);
		assertEquals(List.of("(UI thread)", "camera stops looking", "hide AUTO_CALIBRATING", "show calibrating feed",
				"show MANUAL_REQUEST", "show box"), events);
		assertTrue(flow.isCalibrating());

		events.clear();
		flow.stop();

		// The box is on the canvas; the camera gets it in feed coordinates
		assertEquals(List.of("remove box", "projection on the canvas " + new Rect(75, 75, 150, 150),
				"camera crops false", "camera limits detection true", "camera bounds " + new Rect(37.5, 37.5, 75, 75),
				"camera stops looking", "calibrate button calibrating false", "hide MANUAL_REQUEST",
				"restore selected view", "calibrated", "restore background", "camera calibrating false",
				"camera detecting false", "arena shots visible false"), events);
	}

	@Test
	void aHeadlessTimeoutReportsItAndEndsCalibration() {
		headlessTimeout = Optional.of(() -> events.add("headless timeout"));
		final CalibrationFlow flow = flow();
		flow.setFullScreen(true);
		events.clear();

		runTimers(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT);
		assertEquals(List.of(), events);

		runTimers(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_HEADLESS);
		assertEquals(List.of("(UI thread)", "headless timeout"), events.subList(0, 2));
		assertFalse(flow.isCalibrating());
		assertFalse(events.contains("show box"));
	}

	@Test
	void anUnattendedCalibrationThatTimesOutIsCancelledWithoutTheManualBox() {
		projectorExerciseRunning = true;
		final CalibrationFlow flow = flow();
		flow.startUnattended(() -> events.add("not found"));
		assertTrue(flow.isCalibrating());
		assertTrue(events.contains("show pattern"));
		events.clear();

		// Attended calibration's 12 seconds go by: a camera just plugged in may still be settling
		runTimers(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT);
		assertTrue(flow.isCalibrating());
		assertEquals(List.of(), events);

		runTimers(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED);

		assertFalse(flow.isCalibrating());
		// Cancelled, as Cancel does it, and only then told; never the box, never the calibrating feed
		assertEquals(List.of("(UI thread)", "camera stops looking", "hide AUTO_CALIBRATING",
				"calibrate button calibrating false", "restore selected view", "camera calibrating false",
				"camera detecting false", "not found"), events);
		assertFalse(projectorExerciseRunning, "the drill was stopped, not restarted");
	}

	@Test
	void anUnattendedCalibrationThatFindsThePatternEndsAsAnyOtherAndTheNextStartIsAttended() {
		final CalibrationFlow flow = flow();
		flow.startUnattended(() -> events.add("not found"));

		flow.calibrated(new Rect(100, 50, 400, 300), Optional.empty(), false);
		assertFalse(flow.isCalibrating());
		assertTrue(events.contains("calibrated"));

		// Calibrate, pressed afterwards: its timeout opens the box as ever
		flow.start();
		events.clear();
		runTimers(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT);
		assertTrue(flow.isCalibrating());
		assertTrue(events.contains("show box"));
		assertFalse(events.contains("not found"));
	}

	@Test
	void cancelEndsCalibrationWithNoBackgroundRestoreOrRestartAndReEnablesDetection() {
		projectorExerciseRunning = true;
		final CalibrationFlow flow = flow();
		flow.start();
		events.clear();

		flow.cancel();

		assertFalse(flow.isCalibrating());
		assertEquals(List.of("camera stops looking", "hide AUTO_CALIBRATING", "calibrate button calibrating false",
				"restore selected view", "camera calibrating false", "camera detecting false"), events);
		assertFalse(events.contains("restore background"));
		assertFalse(events.contains("restart exercise"));
		assertFalse(projectorExerciseRunning, "the drill was stopped, not restarted");

		// The auto-calibration timeout is cancelled: firing it does nothing, so it never opens the box
		assertTrue(timers.get(0).future().isCancelled());
		events.clear();
		runTimers(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT);
		assertEquals(List.of(), events);

		// Shot detection comes back after the usual delay
		runTimers(CalibrationFlow.DETECTION_RESTART_DELAY);
		assertEquals(List.of("camera detecting true"), events);
	}

	@Test
	void aStartAfterCancelWorksNormallyWithMessagesShownAgain() {
		projectorExerciseRunning = true;
		final CalibrationFlow flow = flow();
		flow.start();
		flow.cancel();
		events.clear();

		projectorExerciseRunning = true;
		flow.start();

		assertTrue(flow.isCalibrating());
		assertEquals(List.of("stop exercise", "arena shots visible false", "calibrate button calibrating true",
				"camera calibrating true", "camera bounds null", "arena hides its targets", "save background",
				"show pattern", "camera looks for the pattern", "show AUTO_CALIBRATING"), events);
	}

	@Test
	void theCalibratedFeedBehaviorDecidesCroppingAndLimiting() {
		final CalibrationFlow flow = flow();

		view.behavior = CalibrationOption.CROP;
		flow.calibrated(new Rect(0, 0, 10, 10), Optional.empty(), true);
		assertTrue(events.containsAll(List.of("camera crops true", "camera limits detection false")));

		events.clear();
		flow.configureArenaCamera(CalibrationOption.EVERYWHERE);
		assertEquals(List.of("camera crops false", "camera limits detection false"), events);

		events.clear();
		flow.arenaClosing();
		assertEquals(List.of("camera bounds null"), events);
	}

	@Test
	void aSavedCalibrationIsAppliedWithoutCalibrating() {
		final CalibrationFlow flow = flow();
		projectorExerciseRunning = true;

		// The feed's 640 x 480 is shown at 1280 x 960
		flow.applySaved(new Rect(100, 80, 400, 300), Optional.empty());

		assertEquals(List.of("projection on the canvas Rect[minX=200.0, minY=160.0, width=800.0, height=600.0]",
				"camera crops false", "camera limits detection true",
				"camera bounds Rect[minX=100.0, minY=80.0, width=400.0, height=300.0]", "calibrated"), events);
		assertFalse(flow.isCalibrating());
		assertTrue(projectorExerciseRunning);
	}

	@Test
	void aSavedCalibrationCantBeAppliedWhileCalibrating() {
		final CalibrationFlow flow = flow();
		flow.start();

		assertThrows(IllegalStateException.class, () -> flow.applySaved(new Rect(100, 80, 400, 300), Optional.empty()));
		assertTrue(flow.isCalibrating());
	}
}
