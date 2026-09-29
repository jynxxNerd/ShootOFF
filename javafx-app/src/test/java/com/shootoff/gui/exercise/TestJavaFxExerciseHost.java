package com.shootoff.gui.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.CameraView;
import com.shootoff.camera.CamerasSupervisor;
import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ArenaShot;
import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.exercise.ButtonHandle;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.RowStyle;
import com.shootoff.exercise.ShotStyle;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.exercise.TextHandle;
import com.shootoff.exercise.TextStyle;
import com.shootoff.geom.Point;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.LocatedImage;
import com.shootoff.gui.MockCanvasManager;
import com.shootoff.gui.ShotEntry;
import com.shootoff.gui.TimingControlsPane;
import com.shootoff.gui.pane.ProjectorArenaPane;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.plugins.TrainingExerciseView;
import com.shootoff.targets.Target;
import com.shootoff.targets.model.Hit;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Ellipse;
import javafx.scene.text.Font;

class TestJavaFxExerciseHost {
	private static final TextStyle STYLE = new TextStyle(40, "white", "transparent");

	@TempDir Path temp;
	private Configuration config;
	private CamerasSupervisor cameras;
	private MockCanvasManager canvas;
	private Pane container;
	private VBox buttons;
	private TableView<ShotEntry> table;
	private URLClassLoader resources;
	private final RecordingSounds sounds = new RecordingSounds();
	private final RecordingExercise exercise = new RecordingExercise();
	private List<Node> canvasBefore;
	private JavaFxExerciseHost host;

	private static final class RecordingSounds implements SoundOutput {
		final List<String> played = new CopyOnWriteArrayList<>();
		final List<String> spoken = new CopyOnWriteArrayList<>();

		@Override
		public void play(String name, InputStream sound, Runnable whenDone) {
			try (InputStream in = sound) {
				played.add(name);
			} catch (final IOException e) {
				throw new AssertionError(e);
			}
			whenDone.run();
		}

		@Override
		public void say(String text) {
			spoken.add(text);
		}
	}

	private static final class RecordingExercise implements Exercise {
		final List<String> events = new CopyOnWriteArrayList<>();
		final Map<String, String> threads = new ConcurrentHashMap<>();
		final List<Shot> shots = new CopyOnWriteArrayList<>();

		void record(String event) {
			threads.put(event, Thread.currentThread().getName() + (Platform.isFxApplicationThread() ? " (FX)" : ""));
			events.add(event);
		}

		void await(String event) throws InterruptedException {
			waitFor(() -> events.contains(event), "never saw " + event + " in " + events);
		}

		@Override
		public ExerciseMetadata metadata() {
			return new ExerciseMetadata("Recording exercise", "1.0", "ShootOFF tests", "Records its callbacks");
		}

		@Override
		public void start(ExerciseHost host) {
			record("start");
		}

		@Override
		public void onShot(Shot shot, Optional<Hit> hit) {
			shots.add(shot);
			record("shot");
		}

		@Override
		public void onTargetsChanged(List<TargetHandle> targets) {
			record("targets " + targets.size());
		}

		@Override
		public void onReset() {
			record("reset");
		}

		@Override
		public void stop() {
			record("stop");
		}
	}

	private final class View implements TrainingExerciseView {
		@Override
		public Pane getTrainingExerciseContainer() {
			return container;
		}

		@Override
		public TableView<ShotEntry> getShotEntryTable() {
			return table;
		}

		@Override
		public VBox getButtonsPane() {
			return buttons;
		}

		@Override
		public Optional<CameraView> getArenaView() {
			return Optional.empty();
		}

		@Override
		public List<Target> getTargets() {
			return canvas.getTargets();
		}
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

	// Waits for the scene changes queued so far
	private static void fxSync() throws Exception {
		onFx(() -> null);
	}

	private static void waitFor(BooleanSupplier condition, String failure) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) fail(failure);
			Thread.sleep(5);
		}
	}

	private ExerciseHostContext context(MockCanvasManager surface, Optional<ProjectorArenaPane> arena) {
		return new ExerciseHostContext(config, cameras, new View(), surface, arena, List.of(canvas), resources,
				sounds);
	}

	@BeforeEach
	void setUp() throws Exception {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		config = new Configuration(new String[0]);
		cameras = new CamerasSupervisor(config);

		// "The exercise's jar": a sound, a target and a background
		final Path jar = Files.createDirectories(temp.resolve("jar"));
		Files.createDirectories(jar.resolve("sounds"));
		Files.write(jar.resolve("sounds/cue.wav"), new byte[] { 1, 2, 3 });
		Files.createDirectories(jar.resolve("targets"));
		Files.writeString(jar.resolve("targets/box.target"),
				"<target><rectangle x=\"0\" y=\"0\" width=\"10\" height=\"20\" fill=\"red\" /></target>");
		Files.createDirectories(jar.resolve("backgrounds"));
		ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", jar.resolve("backgrounds/black.png").toFile());
		resources = new URLClassLoader(new URL[] { jar.toUri().toURL() }, null);

		onFx(() -> {
			canvas = new MockCanvasManager(config);
			container = new VBox();
			buttons = new VBox(new Button("Reset"));
			table = new TableView<>(FXCollections.observableArrayList());
			canvasBefore = List.copyOf(canvas.getCanvasGroup().getChildren());
			return null;
		});

		host = new JavaFxExerciseHost(exercise, context(canvas, Optional.empty()));
	}

	@AfterEach
	void tearDown() throws IOException {
		host.stop();
		resources.close();
	}

	private Label labelWithText(String text) {
		for (final Node node : canvas.getCanvasGroup().getChildren()) {
			if (node instanceof Label label && text.equals(label.getText())) return label;
		}
		throw new AssertionError("No label " + text + " on the canvas");
	}

	private Button buttonLabeled(String text) {
		for (final Node node : buttons.getChildren()) {
			if (node instanceof Button button && text.equals(button.getText())) return button;
		}
		throw new AssertionError("No button " + text);
	}

	@SuppressWarnings("unchecked")
	private Spinner<Double> spinner() {
		for (final Node pane : container.getChildren()) {
			if (!(pane instanceof HBox box)) continue;
			for (final Node node : box.getChildren()) {
				if (node instanceof Spinner<?> spinner) return (Spinner<Double>) spinner;
			}
		}
		throw new AssertionError("No spinner in the exercise pane");
	}

	private List<TimingControlsPane> timingPanes() {
		return container.getChildren().stream().filter(TimingControlsPane.class::isInstance)
				.map(TimingControlsPane.class::cast).toList();
	}

	@Test
	void targetsReachTheCanvasAndItsTargetSet() throws Exception {
		final TargetHandle ipsc = host.addTarget("targets/IPSC.target", 10, 20).get();

		assertEquals(1, canvas.getTargets().size());
		assertEquals(canvas.getTargetSet().getTargets().get(0).getId(), ipsc.id());
		assertEquals(new Point(10, 20), ipsc.position());
		assertEquals(List.of(ipsc), host.targets());

		ipsc.move(30, 40);
		ipsc.setVisible(false);
		assertEquals(new Point(30, 40), ipsc.position());
		fxSync();
		assertEquals(30, onFx(() -> ((TargetView) canvas.getTargets().get(0)).getTargetGroup().getLayoutX()), 0);
		assertFalse(canvas.getTargetSet().getTargets().get(0).isVisible());

		// From the exercise's jar
		final TargetHandle box = host.addTarget("@targets/box.target", 0, 0).get();
		assertEquals("@targets/box.target", canvas.getTargets().get(1).getTargetFile().getPath());
		assertEquals(10, box.size().getWidth(), 0);

		ipsc.remove();
		box.remove();
		fxSync();
		assertEquals(List.of(), canvas.getTargets());
		assertEquals(Optional.empty(), host.addTarget("targets/no_such.target", 0, 0));
	}

	@Test
	void textsAndMessagesReachTheCanvases() throws Exception {
		final TextHandle round = host.showText("Round: 1/10", 100, 10, STYLE);
		fxSync();
		final Label label = onFx(() -> labelWithText("Round: 1/10"));
		assertEquals(100, label.getLayoutX(), 0);
		assertEquals(Font.font(40).getSize(), label.getFont().getSize(), 0);
		assertEquals(Color.WHITE, label.getTextFill());

		round.setText("Round: 2/10");
		round.move(120, 12);
		fxSync();
		assertEquals("Round: 2/10", label.getText());
		assertEquals(120, label.getLayoutX(), 0);

		round.remove();
		host.showMessage("Score: 3");
		fxSync();
		assertFalse(canvas.getCanvasGroup().getChildren().contains(label));
		assertNotNull(onFx(() -> labelWithText("Score: 3")));
	}

	@Test
	void buttonsReachTheButtonsPane() throws Exception {
		host.start();
		final ButtonHandle pause = host.addButton("Pause", () -> exercise.record("pause"));
		fxSync();

		onFx(() -> {
			buttonLabeled("Pause").fire();
			return null;
		});
		exercise.await("pause");

		pause.setLabel("Resume");
		fxSync();
		assertNotNull(onFx(() -> buttonLabeled("Resume")));

		pause.remove();
		fxSync();
		assertEquals(1, buttons.getChildren().size());
	}

	@Test
	void numberSettingReachesTheExercisePane() throws Exception {
		host.start();
		host.addNumberSetting("Shots per round", 10, 1, 100, 1, value -> exercise.record("rounds " + value));
		fxSync();

		final Spinner<Double> spinner = onFx(this::spinner);
		assertEquals(10, spinner.getValue(), 0);

		onFx(() -> {
			spinner.getValueFactory().setValue(7.0);
			return null;
		});
		exercise.await("rounds 7.0");
	}

	@Test
	void columnsAndRowStylesReachTheShotTimer() throws Exception {
		host.addColumn("Score");
		fxSync();
		assertEquals(List.of("Score"), onFx(() -> table.getColumns().stream().map(TableColumn::getText).toList()));

		onFx(() -> table.getItems().add(new ShotEntry(new DisplayShot(new Shot(ShotColor.RED, 1, 2, 1000), 2),
				Optional.empty(), Optional.empty(), false, false)));
		host.setColumnValue("Score", "10");
		host.styleLastRow(new RowStyle("coral"));
		fxSync();

		final ShotEntry last = onFx(() -> table.getItems().get(table.getItems().size() - 1));
		assertEquals("10", last.getExerciseValue("Score"));
		assertEquals(Optional.of(Color.CORAL), last.getRowColor());
	}

	@Test
	void timerRowsReachTheShotTimerLikeAShotsRow() throws Exception {
		host.addColumn("Score");
		onFx(() -> table.getItems().add(new ShotEntry(new DisplayShot(new Shot(ShotColor.RED, 1, 2, 1000), 2),
				Optional.empty(), Optional.empty(), false, false)));

		host.addTimerRow(2500, new RowStyle("coral"));
		host.setColumnValue("Score", "0");
		fxSync();

		final List<ShotEntry> rows = onFx(() -> List.copyOf(table.getItems()));
		assertEquals(2, rows.size());
		final ShotEntry row = rows.get(1);
		// What v1's fake par-miss shot showed: its time, the split since the previous row, red, coral
		assertEquals(String.format("%.2f", 2.5f), row.getTimestamp());
		assertEquals(String.format("%.2f", 1.5f), row.getSplit().getSplit());
		assertEquals("red", row.getColor());
		assertEquals(Optional.of(Color.CORAL), row.getRowColor());
		assertEquals("0", row.getExerciseValue("Score"));
		// Not a shot: nothing drawn on the canvas
		assertEquals(canvasBefore, onFx(() -> List.copyOf(canvas.getCanvasGroup().getChildren())));
	}

	@Test
	void soundsReachTheSoundOutput() {
		host.playSound("sounds/beep.wav");
		host.playSound("/sounds/cue.wav");
		host.playSounds(List.of("sounds/beep.wav", "chime.wav"));
		host.playSound("sounds/no_such.wav");
		host.say("Make ready");

		// ShootOFF's sounds folder, the exercise's jar, and a bare name in the sounds folder
		assertEquals(List.of("sounds/beep.wav", "/sounds/cue.wav", "sounds/beep.wav", "chime.wav"), sounds.played);
		assertEquals(List.of("Make ready"), sounds.spoken);
	}

	@Test
	void callbacksArriveOnOneExerciseThread() throws Exception {
		host.start();
		host.addButton("Go", () -> exercise.record("click"));
		host.addNumberSetting("Rounds", 1, 1, 10, 1, value -> exercise.record("setting"));
		host.onParTimeChanged(value -> exercise.record("par"));
		host.schedule(() -> exercise.record("scheduled"), Duration.ofMillis(10));
		host.deliverShot(new DisplayShot(ShotColor.RED, 1, 2, 3, 2), Optional.empty());
		host.targetsChanged();
		host.reset();
		fxSync();
		onFx(() -> {
			buttonLabeled("Go").fire();
			spinner().getValueFactory().setValue(2.0);
			timingPanes().get(0).setParTime(3.0);
			return null;
		});

		for (final String event : List.of("start", "shot", "targets 0", "reset", "click", "setting", "par", "scheduled")) {
			exercise.await(event);
		}
		host.stop();

		assertTrue(exercise.events.contains("stop"));
		assertEquals(Set.of("Exercise: Recording exercise"), Set.copyOf(exercise.threads.values()));
	}

	@Test
	void stopRemovesEverythingAndCancelsScheduledTasks() throws Exception {
		host.start();
		host.addTarget("targets/IPSC.target", 5, 5);
		host.showText("Score: 0", 10, 10, STYLE);
		host.showMessage("Make ready");
		host.addButton("Pause", () -> {});
		host.addNumberSetting("Rounds", 10, 1, 100, 1, value -> {});
		host.addColumn("Score");
		host.showShotMarker(20, 20, new ShotStyle(ShotColor.RED));
		host.onParTimeChanged(value -> {});
		final AtomicBoolean ran = new AtomicBoolean();
		host.schedule(() -> ran.set(true), Duration.ofMillis(300));
		fxSync();
		assertEquals(2, container.getChildren().size());
		assertTrue(onFx(() -> canvas.getCanvasGroup().getChildren().stream().anyMatch(Ellipse.class::isInstance)));

		host.stop();
		fxSync();

		assertEquals(List.of(), canvas.getTargets());
		assertEquals(canvasBefore, onFx(() -> List.copyOf(canvas.getCanvasGroup().getChildren())));
		assertEquals(1, buttons.getChildren().size());
		assertEquals(List.of(), container.getChildren());
		assertEquals(List.of(), table.getColumns());
		Thread.sleep(500);
		assertFalse(ran.get());
		assertEquals(List.of("start", "stop"), exercise.events);

		// Calls after stop change nothing
		host.showText("late", 0, 0, STYLE);
		host.addButton("late", () -> {});
		assertEquals(Optional.empty(), host.addTarget("targets/IPSC.target", 0, 0));
		fxSync();
		assertEquals(canvasBefore, onFx(() -> List.copyOf(canvas.getCanvasGroup().getChildren())));
		assertEquals(1, buttons.getChildren().size());
	}

	@Test
	void timingControlsShowOnlyWhileAnExerciseListens() throws Exception {
		host.start();
		host.setParTime(4.0);
		host.setDelayedStart(new DelayRange(5, 8));
		fxSync();
		assertEquals(List.of(), timingPanes());

		final List<Double> heard = new CopyOnWriteArrayList<>();
		host.onParTimeChanged(heard::add);
		fxSync();
		final TimingControlsPane controls = onFx(() -> timingPanes().get(0));

		// The shared controls show the exercise's values, and the exercise hears only the user's
		assertEquals(Optional.of("4.0"), onFx(controls::getParTimeText));
		assertEquals("5-8", onFx(() -> controls.getMinText() + "-" + controls.getMaxText()));
		assertEquals(List.of(), heard);

		onFx(() -> {
			controls.setParTime(3.5);
			return null;
		});
		waitFor(() -> heard.contains(3.5), "the exercise never heard 3.5 s");
		assertEquals(3.5, host.parTime(), 0);

		host.stop();
		fxSync();
		assertEquals(List.of(), timingPanes());
	}

	@Test
	void projectorHostSetsAndRestoresTheBackgroundAndTakesOnlyArenaShots() throws Exception {
		final MockCanvasManager arenaCanvas = onFx(() -> new MockCanvasManager(config));
		final ProjectorArenaPane arena = onFx(() -> new ProjectorArenaPane(config, arenaCanvas));
		final JavaFxExerciseHost projectorHost = new JavaFxExerciseHost(exercise, context(arenaCanvas, Optional.of(arena)));
		assertTrue(projectorHost.isProjector());
		assertFalse(host.isProjector());

		projectorHost.start();
		projectorHost.setBackground("backgrounds/black.png");
		fxSync();
		assertEquals("/backgrounds/black.png", arena.getArenaBackground().get().getURL());

		final ArenaShot arenaShot = new ArenaShot(new DisplayShot(ShotColor.RED, 4, 5, 6, 2));
		projectorHost.deliverShot(new DisplayShot(ShotColor.RED, 1, 2, 3, 2), Optional.empty());
		projectorHost.deliverShot(arenaShot, Optional.empty());
		exercise.await("shot");

		projectorHost.stop();
		fxSync();
		assertEquals(List.of(arenaShot), exercise.shots);
		assertEquals(Optional.empty(), arena.getArenaBackground());
	}

	// Exercise port spec §5: a camera exercise that runs everywhere sees every canvas's targets and takes
	// arena shots too; one that doesn't takes camera shots only
	@Test
	void cameraHostThatRunsEverywhereSeesEveryCanvasAndTakesArenaShots() throws Exception {
		final MockCanvasManager arenaCanvas = onFx(() -> new MockCanvasManager(config));
		onFx(() -> {
			canvas.addTarget(new File("targets/IPSC.target"), false);
			arenaCanvas.addTarget(new File("targets/SimpleBullseye_score.target"), false);
			return null;
		});
		final List<CanvasManager> seen = List.of(canvas, arenaCanvas);
		final JavaFxExerciseHost everywhereHost = new JavaFxExerciseHost(exercise,
				new ExerciseHostContext(config, cameras, new View(), canvas, Optional.empty(), List.of(canvas), resources,
						sounds, Optional.of(() -> seen)));

		assertEquals(List.of(canvas.getTargetSet().getTargets().get(0).getId(),
				arenaCanvas.getTargetSet().getTargets().get(0).getId()),
				everywhereHost.targets().stream().map(TargetHandle::id).toList());
		assertEquals(1, host.targets().size());

		everywhereHost.start();
		final DisplayShot cameraShot = new DisplayShot(ShotColor.RED, 1, 2, 3, 2);
		final ArenaShot arenaShot = new ArenaShot(new DisplayShot(ShotColor.RED, 4, 5, 6, 2));
		everywhereHost.deliverShot(cameraShot, Optional.empty());
		everywhereHost.deliverShot(arenaShot, Optional.empty());
		waitFor(() -> exercise.shots.size() == 2, "shots " + exercise.shots);
		everywhereHost.stop();
		assertEquals(List.of(cameraShot, arenaShot), exercise.shots);

		exercise.shots.clear();
		host.start();
		host.deliverShot(arenaShot, Optional.empty());
		host.deliverShot(cameraShot, Optional.empty());
		waitFor(() -> exercise.shots.size() == 1, "shots " + exercise.shots);
		host.stop();
		assertEquals(List.of(cameraShot), exercise.shots);
	}

	private static LocatedImage image(String url) throws IOException {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", bytes);
		return new LocatedImage(new ByteArrayInputStream(bytes.toByteArray()), url);
	}

	// Reproduces CalibrationManager.enableCalibration() when the arena is already fullscreen:
	// exerciseListener.setExercise(null) (which stops the running exercise) is immediately
	// followed, on the same FX thread call, by enableAutoCalibration() saving the current
	// background and showing the pattern.
	@Test
	void stopFinishesRestoringTheBackgroundBeforeReturningOnTheFxThread() throws Exception {
		final MockCanvasManager arenaCanvas = onFx(() -> new MockCanvasManager(config));
		final ProjectorArenaPane arena = onFx(() -> new ProjectorArenaPane(config, arenaCanvas));
		final Optional<LocatedImage> originalBackground = onFx(arena::getArenaBackground);

		final RecordingExercise drill = new RecordingExercise();
		final JavaFxExerciseHost drillHost = new JavaFxExerciseHost(drill, context(arenaCanvas, Optional.of(arena)));
		drillHost.start();
		drillHost.setBackground("backgrounds/black.png");
		fxSync();
		assertEquals("/backgrounds/black.png", onFx(arena::getArenaBackground).get().getURL());

		final LocatedImage pattern = image("/pattern.png");

		onFx(() -> {
			drillHost.stop();
			// CalibrationManager.enableAutoCalibration(): save whatever the background is right
			// now, then show the pattern -- both still on the FX thread, right after stop().
			arena.saveCurrentBackground();
			arena.setArenaBackground(pattern);
			return null;
		});

		// Any FX work stop() queued must already be finished: the pattern must still be showing,
		// not overwritten by a teardown that runs after this point.
		fxSync();
		assertEquals(pattern.getURL(), onFx(arena::getArenaBackground).get().getURL());
		drill.await("stop");

		// Calibration ends: CalibrationManager.stopCalibration() restores what it saved above.
		onFx(() -> {
			arena.restoreCurrentBackground();
			return null;
		});
		fxSync();
		assertEquals(originalBackground, onFx(arena::getArenaBackground));

		// The drill restarts on a fresh host and is stopped again (off the FX thread, as
		// destroy() usually runs): the arena must show the original pre-exercise background,
		// not the black the drill leaves behind if its "previous" background was corrupted.
		final RecordingExercise restartedDrill = new RecordingExercise();
		final JavaFxExerciseHost restarted = new JavaFxExerciseHost(restartedDrill,
				context(arenaCanvas, Optional.of(arena)));
		restarted.start();
		restarted.setBackground("backgrounds/black.png");
		fxSync();
		restarted.stop();
		fxSync();

		assertEquals(originalBackground, onFx(arena::getArenaBackground));
	}

	@Test
	void dataDirectoryIsPerExerciseInTheShootoffHome() throws IOException {
		final String previousHome = System.getProperty("shootoff.home");
		System.setProperty("shootoff.home", temp.toString());
		try {
			assertEquals(temp.resolve("exercise-data").resolve(RecordingExercise.class.getName()), host.dataDirectory());
			assertTrue(Files.isDirectory(host.dataDirectory()));
		} finally {
			System.setProperty("shootoff.home", previousHome);
		}
	}
}
