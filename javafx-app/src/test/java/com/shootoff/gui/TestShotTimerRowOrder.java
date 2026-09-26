package com.shootoff.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CamerasSupervisor;
import com.shootoff.camera.MockCamera;
import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.gui.controller.ShootOFFController;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.targets.Hit;
import com.shootoff.targets.Target;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Group;

/**
 * Detected shots through the real path: CameraManager.injectShot, ShotDetector, then CanvasManager on
 * the shot thread.
 */
class TestShotTimerRowOrder {
	private Configuration config;
	private final ObservableList<ShotEntry> shotEntries = FXCollections.observableArrayList();
	private CameraManager camera;

	@BeforeEach
	void setUp() throws Exception {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		org.bytedeco.javacpp.Loader.load(org.bytedeco.opencv.opencv_java.class);
		config = new Configuration(new String[0]);
	}

	@AfterEach
	void tearDown() {
		config.setExercise(null);
	}

	private void startCamera() {
		final CanvasManager canvas = new CanvasManager(new Group(), new ShootOFFController(), "test", shotEntries);
		final CamerasSupervisor cameras = new CamerasSupervisor(config);
		camera = cameras.addCameraManager(new MockCamera(), null, canvas).get();
		cameras.setDetectingAll(false);
		canvas.setCameraManager(camera);
	}

	private static void waitFor(BooleanSupplier condition, String failure) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) fail(failure);
			Thread.sleep(10);
		}
	}

	// Two lasers seen in the same camera frame: the camera's thread submits both shots at once
	@Test
	void twoShotsInOneFrameMakeTwoRowsInOrderOnOneThread() throws Exception {
		final AtomicInteger changing = new AtomicInteger();
		final AtomicBoolean overlapped = new AtomicBoolean();
		final List<Thread> threads = new CopyOnWriteArrayList<>();
		final CountDownLatch secondArrived = new CountDownLatch(1);
		shotEntries.addListener((javafx.collections.ListChangeListener<ShotEntry>) change -> {
			threads.add(Thread.currentThread());
			if (changing.incrementAndGet() > 1) {
				overlapped.set(true);
				secondArrived.countDown();
			} else {
				// Give another thread time to change the list while this change is still being made
				try {
					secondArrived.await(500, TimeUnit.MILLISECONDS);
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			changing.decrementAndGet();
		});
		startCamera();

		camera.injectShot(ShotColor.RED, 100, 100, false);
		camera.injectShot(ShotColor.GREEN, 400, 300, false);

		waitFor(() -> shotEntries.size() >= 2, "rows: " + shotEntries.size());

		assertFalse(overlapped.get(), "two threads changed the shot timer at once");
		assertEquals(1, Set.copyOf(threads).size());
		assertEquals(List.of(100.0, 400.0), shotEntries.stream().map(row -> row.getShot().getX()).toList());
		assertEquals(List.of("red", "green"), shotEntries.stream().map(ShotEntry::getColor).toList());
		assertEquals(config.getMarkerRadius(), (int) shotEntries.get(0).getShot().getMarker().getRadiusX());
	}

	// Shoot Don't Shoot speaks "Bad shoot!" inside shotListener: the next shot must not wait for it
	@Test
	void aBlockingV1ShotListenerDoesNotHoldUpTheNextShot() throws Exception {
		final CountDownLatch release = new CountDownLatch(1);
		final List<Double> heard = new CopyOnWriteArrayList<>();
		final List<Integer> rowsWhenHeard = new CopyOnWriteArrayList<>();
		config.setExercise(new TrainingExercise() {
			@Override
			public void init() {}

			@Override
			public void targetUpdate(Target target, TargetChange change) {}

			@Override
			public ExerciseMetadata getInfo() {
				return new ExerciseMetadata("Blocking drill", "1.0", "ShootOFF tests", "Blocks on its first shot");
			}

			@Override
			public void shotListener(Shot shot, Optional<Hit> hit) {
				heard.add(shot.getX());
				rowsWhenHeard.add(shotEntries.size());
				if (heard.size() == 1) {
					try {
						release.await(20, TimeUnit.SECONDS);
					} catch (final InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			}

			@Override
			public void reset(List<Target> targets) {}

			@Override
			public void destroy() {}
		});
		startCamera();

		try {
			camera.injectShot(ShotColor.RED, 100, 100, false);
			waitFor(() -> heard.size() == 1, "the exercise never heard the first shot");
			camera.injectShot(ShotColor.GREEN, 400, 300, false);

			// While the first shot's listener still blocks, the second shot gets its row and is heard
			waitFor(() -> shotEntries.size() == 2 && heard.size() == 2,
					"rows: " + shotEntries.size() + ", heard: " + heard);
			assertEquals(1, release.getCount());
			assertEquals(List.of(100.0, 400.0), heard);
			// Each shot's row was already in the shot timer when the exercise heard the shot
			assertEquals(List.of(1, 2), rowsWhenHeard);
		} finally {
			release.countDown();
		}
	}
}
