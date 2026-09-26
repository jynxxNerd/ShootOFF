package com.shootoff.gui.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CameraView;
import com.shootoff.camera.CamerasSupervisor;
import com.shootoff.camera.MockCamera;
import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.RowStyle;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.ShotEntry;
import com.shootoff.gui.controller.ShootOFFController;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.plugins.TrainingExerciseView;
import com.shootoff.session.SessionRecorder;
import com.shootoff.session.ShotEvent;
import com.shootoff.targets.Target;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Group;
import javafx.scene.control.TableView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;

/**
 * Regression test for the shot-timer row race (spec 2.2: rows are appended on one thread, in order): a
 * v2 host's row (e.g. the par-miss row {@link JavaFxExerciseHost#addTimerRow} adds) must not change the
 * shared {@link ShotEntry} list at the same moment as a queued shot's own row
 * ({@link CanvasManager}'s pipeline).
 */
class TestShotTimerRowConcurrency {
	private static final String CAMERA = "test";

	private Configuration config;
	private SessionRecorder recorder;
	private final ObservableList<ShotEntry> shotEntries = FXCollections.observableArrayList();
	private CameraManager camera;
	private CanvasManager canvas;
	private final List<Double> heardShotX = new CopyOnWriteArrayList<>();

	@BeforeEach
	void setUp() throws Exception {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		org.bytedeco.javacpp.Loader.load(org.bytedeco.opencv.opencv_java.class);
		config = new Configuration(new String[0]);
		recorder = new SessionRecorder();
		config.setSessionRecorder(recorder);
		config.setExercise(new TrainingExercise() {
			@Override
			public void init() {}

			@Override
			public void targetUpdate(Target target, TargetChange change) {}

			@Override
			public ExerciseMetadata getInfo() {
				return new ExerciseMetadata("Recording drill", "1.0", "ShootOFF tests", "Records the shots it hears");
			}

			@Override
			public void shotListener(Shot shot, Optional<com.shootoff.targets.Hit> hit) {
				heardShotX.add(shot.getX());
			}

			@Override
			public void reset(List<Target> targets) {}

			@Override
			public void destroy() {}
		});
	}

	@AfterEach
	void tearDown() {
		config.setExercise(null);
		config.setSessionRecorder(null);
	}

	private void startCamera() {
		canvas = new CanvasManager(new Group(), new ShootOFFController(), CAMERA, shotEntries);
		final CamerasSupervisor cameras = new CamerasSupervisor(config);
		camera = cameras.addCameraManager(new MockCamera(), null, canvas).get();
		cameras.setDetectingAll(false);
		canvas.setCameraManager(camera);
	}

	private static <T> T onFx(Callable<T> action) throws Exception {
		final CompletableFuture<T> result = new CompletableFuture<>();
		Platform.runLater(() -> {
			try {
				result.complete(action.call());
			} catch (final Throwable t) {
				result.completeExceptionally(t);
			}
		});
		return result.get(5, TimeUnit.SECONDS);
	}

	// A v2 exercise's host, wired to the same shot timer rows the camera's CanvasManager appends to, as
	// ShootOFFController wires the real one
	private JavaFxExerciseHost buildHost(TableView<ShotEntry> table) {
		final Exercise noop = new Exercise() {
			@Override
			public ExerciseMetadata metadata() {
				return new ExerciseMetadata("v2 noop", "1.0", "ShootOFF tests", "Drives the host directly");
			}

			@Override
			public void start(ExerciseHost host) {}

			@Override
			public void onShot(Shot shot, Optional<com.shootoff.targets.model.Hit> hit) {}

			@Override
			public void onReset() {}

			@Override
			public void stop() {}
		};

		final TrainingExerciseView view = new TrainingExerciseView() {
			@Override
			public Pane getTrainingExerciseContainer() {
				return new Pane();
			}

			@Override
			public TableView<ShotEntry> getShotEntryTable() {
				return table;
			}

			@Override
			public VBox getButtonsPane() {
				return new VBox();
			}

			@Override
			public Optional<CameraView> getArenaView() {
				return Optional.empty();
			}

			@Override
			public List<Target> getTargets() {
				return canvas.getTargets();
			}
		};

		final SoundOutput silence = new SoundOutput() {
			@Override
			public void play(String name, InputStream sound, Runnable whenDone) {
				whenDone.run();
			}

			@Override
			public void say(String text) {}
		};

		return new JavaFxExerciseHost(noop, new ExerciseHostContext(config, new CamerasSupervisor(config), view,
				canvas, Optional.empty(), List.of(canvas), getClass().getClassLoader(), silence));
	}

	private static void waitFor(BooleanSupplier condition, String failure) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) fail(failure);
			Thread.sleep(10);
		}
	}

	// A v2 drill's par-miss rows (JavaFxExerciseHost.addTimerRow, on the FX thread) and a stream of
	// queued shots' own rows (the pipeline, on the shot queue's thread) interleave throughout: neither
	// side must change the shared list at the same moment as the other. Without the shared lock, the
	// unsynchronized ObservableList (backed by a plain ArrayList) either throws ("Called endChange
	// before beginChange") or silently loses a row, so the final row count comes up short.
	@Test
	void aHostRowDoesNotRaceAQueuedShotsRow() throws Exception {
		final int shots = 300;
		final int hostRows = 300;
		final List<Throwable> uncaught = new CopyOnWriteArrayList<>();

		final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		final ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		rootLogger.addAppender(logs);

		final Thread fxThread = onFx(Thread::currentThread);
		final Thread.UncaughtExceptionHandler previousHandler = fxThread.getUncaughtExceptionHandler();
		fxThread.setUncaughtExceptionHandler((t, e) -> uncaught.add(e));

		try {
			startCamera();
			final TableView<ShotEntry> table = onFx(() -> new TableView<>(shotEntries));
			final JavaFxExerciseHost host = buildHost(table);

			// Interleaved, so the shot queue's thread and the FX thread are both mutating the shared row
			// list throughout, not just at one lucky (or unlucky) moment. Alternating far-apart positions
			// (as TestShotTimerRowOrder does) keeps the deduplication processor from treating consecutive
			// injected shots as one shot's noise.
			for (int i = 0; i < Math.max(shots, hostRows); i++) {
				if (i < shots) {
					if (i % 2 == 0) {
						camera.injectShot(ShotColor.RED, 100, 100, false);
					} else {
						camera.injectShot(ShotColor.RED, 400, 300, false);
					}
				}
				if (i < hostRows) host.addTimerRow(1000 + i, new RowStyle("coral"));
			}

			waitFor(() -> heardShotX.size() >= shots, "shots heard: " + heardShotX.size() + " of " + shots);
			// Let any trailing FX work settle before asserting
			onFx(() -> null);
			waitFor(() -> shotEntries.size() >= shots + hostRows,
					"rows: " + shotEntries.size() + " (expected " + (shots + hostRows) + ")");

			assertEquals(shots + hostRows, shotEntries.size(), "rows: " + shotEntries.size());
			assertEquals(shots, heardShotX.size(), "not every shot reached the exercise");
			assertEquals(shots, recorded(), "not every shot was recorded");
			assertTrue(uncaught.isEmpty(), "uncaught exceptions on the FX thread: " + uncaught);

			final List<String> problems = logs.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
					.map(ILoggingEvent::getFormattedMessage).toList();
			assertTrue(problems.isEmpty(), "unexpected warnings/errors: " + problems);
		} finally {
			fxThread.setUncaughtExceptionHandler(previousHandler);
			rootLogger.detachAppender(logs);
		}
	}

	private long recorded() {
		return recorder.getCameraEvents(CAMERA).stream().filter(e -> e instanceof ShotEvent).count();
	}
}
