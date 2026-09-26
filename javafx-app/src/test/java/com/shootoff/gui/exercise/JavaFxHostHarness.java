package com.shootoff.gui.exercise;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.shootoff.camera.CameraView;
import com.shootoff.camera.CamerasSupervisor;
import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ArenaShot;
import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.ExerciseHostContract;
import com.shootoff.exercise.ExerciseHostContract.TimerRowView;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.MirroredCanvasManager;
import com.shootoff.gui.MockCanvasManager;
import com.shootoff.gui.ShotEntry;
import com.shootoff.gui.TimingControlsPane;
import com.shootoff.gui.pane.ProjectorArenaPane;
import com.shootoff.gui.targets.MirroredTarget;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.plugins.TrainingExerciseBase;
import com.shootoff.plugins.TrainingExerciseView;
import com.shootoff.plugins.engine.ExerciseLoaders;
import com.shootoff.plugins.engine.Plugin;
import com.shootoff.plugins.engine.PluginJars;
import com.shootoff.targets.Target;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * A {@link JavaFxExerciseHost} on a camera feed's canvas, or on the projector arena's canvas mirrored by
 * the arena tab's (wired as ProjectorSlide wires them), for {@link ExerciseHostContract}.
 */
final class JavaFxHostHarness implements ExerciseHostContract.Harness {
	// The plugin a projector exercise's jar needs to be: the arena's canvases load "@" targets from the
	// current plugin's jar
	private static final String DRILL_CLASS = "com.example.v2.ContractDrill";
	private static final String DRILL_SOURCE = String.join("\n",
			"package com.example.v2;",
			"import java.util.Optional;",
			"import com.shootoff.camera.Shot;",
			"import com.shootoff.exercise.Exercise;",
			"import com.shootoff.exercise.ExerciseHost;",
			"import com.shootoff.plugins.ExerciseMetadata;",
			"import com.shootoff.targets.model.Hit;",
			"public class ContractDrill implements Exercise {",
			"  @Override public ExerciseMetadata metadata() {",
			"    return new ExerciseMetadata(\"Contract drill\", \"1.0\", \"ShootOFF tests\", \"Its jar\", true);",
			"  }",
			"  @Override public void start(ExerciseHost host) {}",
			"  @Override public void onShot(Shot shot, Optional<Hit> hit) {}",
			"  @Override public void onReset() {}",
			"  @Override public void stop() {}",
			"}");

	private final Configuration config;
	private final JavaFxExerciseHost host;
	private final CanvasManager canvas;
	private final Optional<MirroredCanvasManager> mirror;
	private final List<CanvasManager> feeds;
	private final Pane container;
	private final VBox buttons;
	private final TableView<ShotEntry> table;
	private final URLClassLoader resources;
	private final List<String> played = new CopyOnWriteArrayList<>();

	private JavaFxHostHarness(Configuration config, Exercise exercise, CanvasManager canvas,
			Optional<MirroredCanvasManager> mirror, Optional<ProjectorArenaPane> arena, Pane container,
			URLClassLoader resources) throws Exception {
		this.config = config;
		this.canvas = canvas;
		this.mirror = mirror;
		feeds = List.of(mirror.isPresent() ? mirror.get() : canvas);
		this.container = container;
		this.resources = resources;
		buttons = onFx(() -> new VBox(new Button("Reset")));
		table = onFx(() -> new TableView<>(FXCollections.observableArrayList()));

		host = new JavaFxExerciseHost(exercise, new ExerciseHostContext(config, new CamerasSupervisor(config), new View(),
				canvas, arena, feeds, resources, new SoundOutput() {
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
					public void say(String text) {}
				}));
	}

	/**
	 * A host on a camera feed's canvas, with the exercise's jar as a folder.
	 */
	static JavaFxHostHarness onCameraFeed(Exercise exercise, Map<String, byte[]> jarEntries, Path temp)
			throws Exception {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		final Configuration config = new Configuration(new String[0]);

		final Path jar = Files.createDirectories(temp.resolve("jar"));
		write(jar, jarEntries);
		final URLClassLoader resources = new URLClassLoader(new URL[] { jar.toUri().toURL() }, null);

		final MockCanvasManager canvas = onFx(() -> new MockCanvasManager(config));
		return new JavaFxHostHarness(config, exercise, canvas, Optional.empty(), Optional.empty(),
				onFx(VBox::new), resources);
	}

	/**
	 * A host on the projector arena: the arena window's canvas, mirrored by the arena tab's, with the
	 * exercise's jar installed as the current plugin.
	 */
	static JavaFxHostHarness onArena(Exercise exercise, Map<String, byte[]> jarEntries, Path temp) throws Exception {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		final Configuration config = new Configuration(new String[0]);

		// PluginJars jars everything in its classes folder
		write(Files.createDirectories(temp.resolve("drill.jar.build").resolve("classes")), jarEntries);
		final Path jar = PluginJars.build(temp, "drill.jar", Optional.of(PluginJars.descriptor(2, DRILL_CLASS)),
				Map.of(DRILL_CLASS, DRILL_SOURCE), classpath());
		final Plugin plugin = new Plugin(jar, ExerciseLoaders.all());
		config.setPlugin(plugin);

		final Pane container = onFx(VBox::new);
		final ProjectorArenaPane[] panes = onFx(() -> {
			final ObservableList<ShotEntry> shotTimer = FXCollections.observableArrayList();
			final Stage arenaStage = new Stage();
			final ProjectorArenaPane projectorPane = new ProjectorArenaPane(arenaStage, null, container, null,
					shotTimer);
			final ProjectorArenaPane tabPane = new ProjectorArenaPane(arenaStage, null, container, null, shotTimer);
			projectorPane.setArenaPaneMirror(tabPane);
			final MirroredCanvasManager projector = (MirroredCanvasManager) projectorPane.getCanvasManager();
			final MirroredCanvasManager tab = (MirroredCanvasManager) tabPane.getCanvasManager();
			projector.setMirroredManager(tab);
			tab.setMirroredManager(projector);
			tab.setRecordsSessionEvents(false);
			return new ProjectorArenaPane[] { projectorPane, tabPane };
		});

		return new JavaFxHostHarness(config, exercise, panes[0].getCanvasManager(),
				Optional.of((MirroredCanvasManager) panes[1].getCanvasManager()), Optional.of(panes[0]), container,
				plugin.getLoader());
	}

	private static void write(Path folder, Map<String, byte[]> entries) throws IOException {
		for (final Map.Entry<String, byte[]> entry : entries.entrySet()) {
			final Path file = folder.resolve(entry.getKey());
			Files.createDirectories(file.getParent());
			Files.write(file, entry.getValue());
		}
	}

	// ShootOFF's classes, as a plugin author compiles against them
	private static String classpath() {
		return String.join(File.pathSeparator, System.getProperty("java.class.path"), location(TrainingExerciseBase.class),
				location(Exercise.class), location(Shot.class));
	}

	private static String location(Class<?> type) {
		return new File(type.getProtectionDomain().getCodeSource().getLocation().getPath()).getPath();
	}

	static <T> T onFx(Callable<T> action) throws Exception {
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

	@Override
	public ExerciseHost host() {
		return host;
	}

	@Override
	public void start() {
		host.start();
	}

	@Override
	public void shoot(double x, double y) {
		final DisplayShot shot = new DisplayShot(ShotColor.RED, x, y, System.currentTimeMillis(), 2);
		// A projector exercise takes shots in arena coordinates
		host.deliverShot(mirror.isPresent() ? new ArenaShot(shot) : shot, Optional.empty());
	}

	@Override
	public void reset() {
		host.reset();
	}

	@Override
	public void stop() {
		host.stop();
	}

	@Override
	public void awaitUi() throws Exception {
		onFx(() -> null);
	}

	@Override
	public void click(String label) throws Exception {
		onFx(() -> {
			for (final Node node : buttons.getChildren()) {
				if (node instanceof Button button && label.equals(button.getText())) {
					button.fire();
					return null;
				}
			}
			throw new AssertionError("No button " + label);
		});
	}

	@Override
	@SuppressWarnings("unchecked")
	public void changeSetting(String label, double value) throws Exception {
		onFx(() -> {
			for (final Node pane : container.getChildren()) {
				if (pane instanceof HBox box && box.getChildren().get(0) instanceof Label name
						&& label.equals(name.getText())) {
					((Spinner<Double>) box.getChildren().get(1)).getValueFactory().setValue(value);
					return null;
				}
			}
			throw new AssertionError("No setting " + label);
		});
	}

	private TimingControlsPane timingControls() {
		return container.getChildren().stream().filter(TimingControlsPane.class::isInstance)
				.map(TimingControlsPane.class::cast).findFirst()
				.orElseThrow(() -> new AssertionError("No timing controls"));
	}

	@Override
	public void userSetsParTime(double seconds) throws Exception {
		onFx(() -> {
			timingControls().setParTime(seconds);
			return null;
		});
	}

	@Override
	public void userSetsDelayedStart(int minSeconds, int maxSeconds) throws Exception {
		onFx(() -> {
			timingControls().setDelayRange(minSeconds, maxSeconds);
			return null;
		});
	}

	@Override
	public Object shownState() throws Exception {
		return onFx(() -> {
			final List<Object> state = new ArrayList<>();
			state.add(List.copyOf(canvas.getCanvasGroup().getChildren()));
			state.add(List.copyOf(canvas.getTargets()));
			if (mirror.isPresent()) {
				state.add(List.copyOf(mirror.get().getCanvasGroup().getChildren()));
				state.add(List.copyOf(mirror.get().getTargets()));
			}
			for (final CanvasManager feed : feeds) {
				state.add(List.copyOf(feed.getCanvasGroup().getChildren()));
			}
			state.add(List.copyOf(buttons.getChildren()));
			state.add(List.copyOf(container.getChildren()));
			state.add(List.copyOf(table.getColumns()));
			return state;
		});
	}

	@Override
	public void addShotRow(long timeMillis) throws Exception {
		onFx(() -> {
			final List<ShotEntry> rows = table.getItems();
			final Optional<Shot> previous = rows.isEmpty() ? Optional.empty()
					: Optional.of(rows.get(rows.size() - 1).getShot());
			rows.add(new ShotEntry(new DisplayShot(new Shot(ShotColor.RED, 1, 2, timeMillis), 2), previous,
					Optional.empty(), false, false));
			return null;
		});
	}

	@Override
	public List<TimerRowView> timerRows(String column) throws Exception {
		return onFx(() -> table.getItems().stream()
				.map(row -> new TimerRowView(row.getTimestamp(), row.getSplit().getSplit(), row.getColor(),
						row.getExerciseValue(column), row.getRowColor().isPresent()))
				.toList());
	}

	@Override
	public List<Boolean> visibilityOnEachView(TargetHandle target) throws Exception {
		final Optional<TargetView> view = find(canvas, target);

		if (view.isEmpty()) {
			// A copy left on the arena tab without its projector copy still shows
			if (mirror.isPresent() && mirror.get().getTargets().size() > canvas.getTargets().size()) {
				return List.of(true);
			}
			return List.of();
		}

		final List<Boolean> visibility = new ArrayList<>();
		visibility.add(shown(view.get()));
		if (mirror.isPresent()) {
			final TargetView tabCopy = ((MirroredTarget) view.get()).getMirroredTarget();
			if (!mirror.get().getTargets().contains(tabCopy)) throw new AssertionError("The arena tab has no copy");
			visibility.add(shown(tabCopy));
		}
		return visibility;
	}

	private static Optional<TargetView> find(CanvasManager canvas, TargetHandle target) {
		for (final Target t : new ArrayList<>(canvas.getTargets())) {
			final TargetView view = (TargetView) t;
			if (view.getPlacedTarget().getId().equals(target.id())) return Optional.of(view);
		}
		return Optional.empty();
	}

	// The model and the scene must agree
	private static boolean shown(TargetView view) throws Exception {
		final boolean model = view.isVisible();
		final boolean node = onFx(() -> view.getTargetGroup().isVisible());
		if (model != node) throw new AssertionError("The model says visible=" + model + ", the scene " + node);
		return model;
	}

	@Override
	public int viewCount() {
		return mirror.isPresent() ? 2 : 1;
	}

	@Override
	public List<String> playedSounds() {
		return List.copyOf(played);
	}

	@Override
	public void close() throws Exception {
		host.stop();
		config.setPlugin(null);
		resources.close();
	}
}
