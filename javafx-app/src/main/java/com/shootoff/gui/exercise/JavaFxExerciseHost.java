/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.gui.exercise;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ArenaShot;
import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.ButtonHandle;
import com.shootoff.exercise.Cancellable;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseExecutor;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.ExercisePaths;
import com.shootoff.exercise.RowStyle;
import com.shootoff.exercise.ShotMarkerHandle;
import com.shootoff.exercise.ShotStyle;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.exercise.TextHandle;
import com.shootoff.exercise.TextStyle;
import com.shootoff.geom.Point;
import com.shootoff.geom.Size;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.LocatedImage;
import com.shootoff.gui.ParListener;
import com.shootoff.gui.ShotEntry;
import com.shootoff.gui.TimingControlsPane;
import com.shootoff.gui.pane.ProjectorArenaPane;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.targets.Target;
import com.shootoff.targets.io.TargetIO;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.TargetDefinition;
import com.shootoff.targets.model.TargetId;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Ellipse;
import javafx.scene.text.Font;

/**
 * Runs one v2 {@link Exercise} in the JavaFX app, on the projector arena or on a camera feed.
 * <ul>
 * <li>Every exercise callback (start, shots, target changes, resets, stop, scheduled tasks, buttons,
 * settings and listeners) runs on the exercise's own thread, an {@link ExerciseExecutor}.</li>
 * <li>Host methods may be called from any thread. Changes to the scene are queued onto the JavaFX
 * thread in call order. No host method waits for the JavaFX thread, so {@link #stop()} may wait for
 * the exercise thread while running on the JavaFX thread.</li>
 * <li>{@link #stop()} stops the exercise, cancels its scheduled tasks and removes everything it added.
 * Calls the exercise makes afterwards change nothing.</li>
 * </ul>
 */
public final class JavaFxExerciseHost implements ExerciseHost {
	private static final Logger logger = LoggerFactory.getLogger(JavaFxExerciseHost.class);

	private static final Duration STOP_TIMEOUT = Duration.ofSeconds(2);
	// What the shared timing controls show until an exercise sets its own values, as for v1 exercises
	private static final double DEFAULT_PAR_TIME = 2.0;
	private static final DelayRange DEFAULT_DELAYED_START = new DelayRange(4, 8);
	private static final int COLUMN_WIDTH = 60;

	private final Exercise exercise;
	private final ExerciseHostContext context;
	private final ExerciseExecutor executor;

	// Guarded by this
	private final List<TargetView> addedTargets = new ArrayList<>();
	private boolean stopped = false;
	private boolean detectionPaused = false;

	private volatile double parTime = DEFAULT_PAR_TIME;
	private volatile DelayRange delayedStart = DEFAULT_DELAYED_START;
	private final List<DoubleConsumer> parListeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<DelayRange>> delayListeners = new CopyOnWriteArrayList<>();

	// JavaFX thread only
	private final List<Node> canvasNodes = new ArrayList<>();
	private final List<Node> markers = new ArrayList<>();
	private final Map<CanvasManager, Label> feedLabels = new LinkedHashMap<>();
	private final List<Node> panes = new ArrayList<>();
	private final List<Button> buttons = new ArrayList<>();
	private final List<TableColumn<ShotEntry, String>> columns = new ArrayList<>();
	private TimingControlsPane timingControls = null;
	private boolean changedBackground = false;
	private Optional<LocatedImage> previousBackground = Optional.empty();
	private boolean tornDown = false;

	// The shared timing controls' listener; it runs on the JavaFX thread
	private final ParListener timingListener = new ParListener() {
		@Override
		public void updatedDelayedStartInterval(int min, int max) {
			if (min > max) return;

			final DelayRange range = new DelayRange(min, max);
			if (range.equals(delayedStart)) return;

			delayedStart = range;
			for (final Consumer<DelayRange> listener : delayListeners) {
				executor.execute(() -> listener.accept(range));
			}
		}

		@Override
		public void updatedParInterval(double seconds) {
			if (Double.compare(seconds, parTime) == 0) return;

			parTime = seconds;
			for (final DoubleConsumer listener : parListeners) {
				executor.execute(() -> listener.accept(seconds));
			}
		}
	};

	public JavaFxExerciseHost(Exercise exercise, ExerciseHostContext context) {
		this.exercise = exercise;
		this.context = context;
		executor = new ExerciseExecutor(exercise.metadata().getName());
	}

	// ---- Lifecycle, driven by HostedExercise

	public void start() {
		executor.execute(() -> exercise.start(this));
	}

	/**
	 * Hands a shot to the exercise: arena shots to a projector exercise, camera shots to a camera
	 * exercise.
	 */
	public void deliverShot(Shot shot, Optional<Hit> hit) {
		if (isProjector() != (shot instanceof ArenaShot)) return;

		executor.execute(() -> exercise.onShot(shot, hit));
	}

	public void targetsChanged() {
		executor.execute(() -> exercise.onTargetsChanged(targets()));
	}

	public void reset() {
		executor.execute(exercise::onReset);
	}

	/**
	 * Stops the exercise and removes everything it added. Waits up to two seconds for the exercise
	 * thread, which never waits for the JavaFX thread, so this may run on the JavaFX thread.
	 */
	public void stop() {
		synchronized (this) {
			if (stopped) return;
			stopped = true;
		}

		executor.shutdown(exercise::stop, STOP_TIMEOUT);

		final List<TargetView> targets;
		final boolean restartDetection;
		synchronized (this) {
			targets = List.copyOf(addedTargets);
			addedTargets.clear();
			restartDetection = detectionPaused;
		}

		for (final TargetView target : targets) {
			context.canvas().removeTarget(target);
		}

		if (restartDetection) context.cameras().setDetectingAll(true);

		// Queued after every scene change the exercise made, so all of them are undone
		Platform.runLater(this::tearDown);
	}

	private void tearDown() {
		tornDown = true;

		context.canvas().getCanvasGroup().getChildren().removeAll(canvasNodes);
		canvasNodes.clear();
		markers.clear();

		for (final Map.Entry<CanvasManager, Label> feed : feedLabels.entrySet()) {
			feed.getKey().removeChild(feed.getValue());
		}
		feedLabels.clear();

		context.view().getTrainingExerciseContainer().getChildren().removeAll(panes);
		panes.clear();
		timingControls = null;

		context.view().getButtonsPane().getChildren().removeAll(buttons);
		buttons.clear();

		context.view().getShotEntryTable().getColumns().removeAll(columns);
		columns.clear();

		if (changedBackground) context.arena().get().setArenaBackground(previousBackground.orElse(null));
	}

	// Queues a scene change. Changes queued after the tear down are dropped.
	private void fx(Runnable change) {
		Platform.runLater(() -> {
			if (!tornDown) change.run();
		});
	}

	private synchronized boolean isStopped() {
		return stopped;
	}

	private ObservableList<Node> canvasChildren() {
		return context.canvas().getCanvasGroup().getChildren();
	}

	// ---- Surface

	@Override
	public Size surfaceSize() {
		if (context.arena().isPresent()) {
			final ProjectorArenaPane arena = context.arena().get();
			return new Size(arena.getWidth(), arena.getHeight());
		}

		return new Size(context.config().getDisplayWidth(), context.config().getDisplayHeight());
	}

	@Override
	public boolean isProjector() {
		return context.arena().isPresent();
	}

	@Override
	public void setBackground(String imageResource) {
		if (context.arena().isEmpty()) {
			logger.warn("{} set a background, but only the projector arena has one", exercise.metadata().getName());
			return;
		}

		final String name = ExercisePaths.resourceName(imageResource);
		final LocatedImage background;
		try (InputStream image = openImage(name)) {
			if (image == null) {
				logger.error("Can't find background {}", imageResource);
				return;
			}
			background = new LocatedImage(image, "/" + name);
		} catch (final IOException e) {
			logger.error("Can't read background {}", imageResource, e);
			return;
		}

		fx(() -> {
			final ProjectorArenaPane arena = context.arena().get();
			if (!changedBackground) {
				previousBackground = arena.getArenaBackground();
				changedBackground = true;
			}
			arena.setArenaBackground(background);
		});
	}

	// The exercise's jar first, then ShootOFF's own resources (for example arena/backgrounds/...)
	private InputStream openImage(String name) throws IOException {
		final Optional<URL> resource = findResource(name);
		if (resource.isPresent()) return open(resource.get());

		return JavaFxExerciseHost.class.getResourceAsStream("/" + name);
	}

	// ---- Targets

	@Override
	public Optional<TargetHandle> addTarget(String targetFile, double x, double y) {
		if (isStopped()) return Optional.empty();

		final Optional<Target> added = loadTarget(targetFile);
		if (added.isEmpty()) return Optional.empty();

		final TargetView target = (TargetView) added.get();
		target.setPosition(x, y);

		final Optional<ProjectorArenaPane> arena = context.arena();
		if (arena.isPresent() && arena.get().getPerspectiveManager().isPresent()
				&& arena.get().getPerspectiveManager().get().isInitialized()) {
			arena.get().resizeTargetToDefaultPerspective(target);
		}

		synchronized (this) {
			if (!stopped) {
				addedTargets.add(target);
				return Optional.of(new FxTargetHandle(target));
			}
		}

		// Stopped while loading
		context.canvas().removeTarget(target);
		return Optional.empty();
	}

	private Optional<Target> loadTarget(String targetFile) {
		final String name = ExercisePaths.resourceName(targetFile);
		final Optional<URL> resource = findResource(name);

		if (resource.isPresent()) {
			try (InputStream in = open(resource.get())) {
				return TargetIO.loadTarget(in, false, context.resources()).map(
						components -> context.canvas().addTarget(components.withTargetFile(new File("@" + name)), true));
			} catch (final IOException e) {
				logger.error("Can't read target {} from the exercise", name, e);
				return Optional.empty();
			}
		}

		final Optional<File> file = ExercisePaths.shootoffFile(targetFile, "targets");
		if (file.isEmpty()) {
			logger.error("Can't find target {}", targetFile);
			return Optional.empty();
		}

		return context.canvas().addTarget(file.get(), false);
	}

	@Override
	public List<TargetHandle> targets() {
		final List<TargetHandle> handles = new ArrayList<>();
		for (final Target target : new ArrayList<>(context.canvas().getTargets())) {
			handles.add(new FxTargetHandle((TargetView) target));
		}
		return handles;
	}

	private final class FxTargetHandle implements TargetHandle {
		private final TargetView view;

		FxTargetHandle(TargetView view) {
			this.view = view;
		}

		@Override
		public TargetId id() {
			return view.getPlacedTarget().getId();
		}

		@Override
		public void move(double x, double y) {
			view.setPosition(x, y);
		}

		@Override
		public void resize(double width, double height) {
			view.setDimensions(width, height);
		}

		@Override
		public void setVisible(boolean visible) {
			view.setVisible(visible);
		}

		@Override
		public void remove() {
			synchronized (JavaFxExerciseHost.this) {
				addedTargets.remove(view);
			}
			context.canvas().removeTarget(view);
		}

		@Override
		public Point position() {
			return view.getPlacedTarget().getPosition();
		}

		@Override
		public Size size() {
			return view.getPlacedTarget().getSize();
		}

		@Override
		public TargetDefinition definition() {
			return view.getComponents().getDefinition();
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof FxTargetHandle other && other.view == view;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(view);
		}
	}

	// ---- Text

	@Override
	public TextHandle showText(String text, double x, double y, TextStyle style) {
		final Label label = new Label(text);
		label.setFont(Font.font(style.fontSize()));
		label.setTextFill(Color.web(style.textColor()));
		label.setBackground(
				new Background(new BackgroundFill(Color.web(style.backgroundColor()), CornerRadii.EMPTY, Insets.EMPTY)));

		fx(() -> {
			label.setLayoutX(x);
			label.setLayoutY(y);
			canvasChildren().add(label);
			canvasNodes.add(label);
		});

		return new TextHandle() {
			@Override
			public void setText(String newText) {
				fx(() -> label.setText(newText));
			}

			@Override
			public void move(double newX, double newY) {
				fx(() -> {
					label.setLayoutX(newX);
					label.setLayoutY(newY);
				});
			}

			@Override
			public void remove() {
				fx(() -> {
					canvasChildren().remove(label);
					canvasNodes.remove(label);
				});
			}
		};
	}

	@Override
	public void showMessage(String message) {
		if (isStopped()) return;

		if (context.config().inDebugMode()) System.out.println(message);
		context.config().getSessionRecorder().ifPresent(recorder -> recorder.recordExerciseFeedMessage(message));

		fx(() -> {
			for (final CanvasManager feed : context.feeds()) {
				feedLabels.computeIfAbsent(feed, canvas -> {
					final Label label = new Label();
					label.setTextFill(Color.WHITE);
					canvas.addChild(label);
					return label;
				}).setText(message);
			}
		});
	}

	// ---- Controls

	@Override
	public ButtonHandle addButton(String label, Runnable onClick) {
		final Button button = new Button(label);
		button.setOnAction(event -> executor.execute(onClick));

		fx(() -> {
			final List<Node> pane = context.view().getButtonsPane().getChildren();
			// Sized like ShootOFF's Reset button, the pane's first
			if (!pane.isEmpty() && pane.get(0) instanceof Button reset) {
				button.setPrefSize(reset.getPrefWidth(), reset.getPrefHeight());
			}
			pane.add(button);
			buttons.add(button);
		});

		return new ButtonHandle() {
			@Override
			public void setLabel(String newLabel) {
				fx(() -> button.setText(newLabel));
			}

			@Override
			public void remove() {
				fx(() -> {
					context.view().getButtonsPane().getChildren().remove(button);
					buttons.remove(button);
				});
			}
		};
	}

	@Override
	public void addNumberSetting(String label, double initial, double min, double max, double step,
			DoubleConsumer onChange) {
		fx(() -> {
			final Spinner<Double> spinner = new Spinner<>(min, max, initial, step);
			spinner.setEditable(true);
			spinner.valueProperty().addListener((observable, oldValue, newValue) -> {
				if (newValue != null) executor.execute(() -> onChange.accept(newValue));
			});

			final HBox pane = new HBox(10, new Label(label), spinner);
			pane.setAlignment(Pos.CENTER_LEFT);
			context.view().getTrainingExerciseContainer().getChildren().add(pane);
			panes.add(pane);
		});
	}

	// ---- Shot timer

	@Override
	public void addColumn(String name) {
		fx(() -> {
			final TableColumn<ShotEntry, String> column = new TableColumn<>(name);
			column.setPrefWidth(COLUMN_WIDTH);
			column.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getExerciseValue(name)));
			context.view().getShotEntryTable().getColumns().add(column);
			columns.add(column);
		});
	}

	@Override
	public void setColumnValue(String name, String value) {
		fx(() -> {
			final TableView<ShotEntry> table = context.view().getShotEntryTable();
			if (table.getItems().isEmpty()) {
				logger.warn("{} set {} on an empty shot timer", exercise.metadata().getName(), name);
				return;
			}

			table.getItems().get(table.getItems().size() - 1).setExerciseValue(name, value);
			table.refresh();
		});
	}

	@Override
	public void styleLastRow(RowStyle style) {
		final Color color = Color.web(style.highlightColor());

		fx(() -> {
			final ObservableList<ShotEntry> rows = context.view().getShotEntryTable().getItems();
			if (rows.isEmpty()) return;

			final int last = rows.size() - 1;
			rows.set(last, rows.get(last).withRowColor(Optional.of(color)));
		});
	}

	// The row v1's drill got from a fake red shot at (-10, -10): the shot timer computes Time, Split and
	// Laser from the shot as usual, but nothing draws, hit-tests or records it
	@Override
	public void addTimerRow(long timeMillis, RowStyle style) {
		final Color color = Color.web(style.highlightColor());
		final DisplayShot noShot = new DisplayShot(new Shot(ShotColor.RED, -10, -10, timeMillis),
				context.config().getMarkerRadius());

		fx(() -> {
			final ObservableList<ShotEntry> rows = context.view().getShotEntryTable().getItems();
			final Optional<Shot> previous = rows.isEmpty() ? Optional.empty()
					: Optional.of(rows.get(rows.size() - 1).getShot());
			rows.add(new ShotEntry(noShot, previous, Optional.of(color), false, false));
		});
	}

	// ---- Shots

	@Override
	public ShotMarkerHandle showShotMarker(double x, double y, ShotStyle style) {
		final int radius = context.config().getMarkerRadius();
		final Ellipse marker = new Ellipse(x, y, radius, radius);
		marker.setFill(DisplayShot.toPaint(style.color()));

		fx(() -> {
			canvasChildren().add(marker);
			canvasNodes.add(marker);
			markers.add(marker);
		});

		return () -> fx(() -> {
			canvasChildren().remove(marker);
			canvasNodes.remove(marker);
			markers.remove(marker);
		});
	}

	@Override
	public void clearShots() {
		if (isStopped()) return;

		context.cameras().clearShots();
		fx(() -> {
			canvasChildren().removeAll(markers);
			canvasNodes.removeAll(markers);
			markers.clear();
		});
	}

	@Override
	public synchronized void pauseShotDetection(boolean paused) {
		if (stopped) return;

		detectionPaused = paused;
		context.cameras().setDetectingAll(!paused);
	}

	// ---- Sound

	@Override
	public void playSound(String resourceOrFile) {
		openSound(resourceOrFile).ifPresent(sound -> context.sounds().play(resourceOrFile, sound, () -> {}));
	}

	@Override
	public void playSounds(List<String> resourcesOrFiles) {
		playInOrder(List.copyOf(resourcesOrFiles), 0);
	}

	private void playInOrder(List<String> sounds, int index) {
		if (index >= sounds.size() || isStopped()) return;

		final Optional<InputStream> sound = openSound(sounds.get(index));
		if (sound.isPresent()) {
			context.sounds().play(sounds.get(index), sound.get(), () -> playInOrder(sounds, index + 1));
		} else {
			playInOrder(sounds, index + 1);
		}
	}

	// The exercise's jar first, then ShootOFF's folder and its sounds/ folder
	private Optional<InputStream> openSound(String resourceOrFile) {
		if (isStopped()) return Optional.empty();

		try {
			final Optional<URL> resource = findResource(ExercisePaths.resourceName(resourceOrFile));
			if (resource.isPresent()) return Optional.of(open(resource.get()));

			final Optional<File> file = ExercisePaths.shootoffFile(resourceOrFile, "sounds");
			if (file.isPresent()) return Optional.of(new BufferedInputStream(Files.newInputStream(file.get().toPath())));
		} catch (final IOException e) {
			logger.error("Can't open sound {}", resourceOrFile, e);
			return Optional.empty();
		}

		logger.error("Can't find sound {}", resourceOrFile);
		return Optional.empty();
	}

	@Override
	public void say(String text) {
		if (!isStopped()) context.sounds().say(text);
	}

	// ---- Time

	@Override
	public long currentTimeMillis() {
		return System.currentTimeMillis();
	}

	@Override
	public Cancellable schedule(Runnable task, Duration delay) {
		return executor.schedule(task, delay);
	}

	@Override
	public Cancellable scheduleRepeating(Runnable task, Duration initialDelay, Duration period) {
		return executor.scheduleRepeating(task, initialDelay, period);
	}

	// ---- Resources

	@Override
	public Optional<InputStream> resource(String path) {
		final Optional<URL> resource = findResource(ExercisePaths.resourceName(path));
		if (resource.isEmpty()) return Optional.empty();

		try {
			return Optional.of(open(resource.get()));
		} catch (final IOException e) {
			logger.error("Can't open resource {}", path, e);
			return Optional.empty();
		}
	}

	// A plugin's own jar only: its class loader's getResource would search ShootOFF's classpath first
	private Optional<URL> findResource(String name) {
		final ClassLoader loader = context.resources();
		if (loader == null || name.isEmpty()) return Optional.empty();

		return Optional.ofNullable(loader instanceof URLClassLoader jar ? jar.findResource(name) : loader.getResource(name));
	}

	// Uncached, so the exercise's jar isn't held open and can be replaced while ShootOFF runs
	private static InputStream open(URL url) throws IOException {
		final URLConnection connection = url.openConnection();
		connection.setUseCaches(false);
		return new BufferedInputStream(connection.getInputStream());
	}

	@Override
	public Path dataDirectory() {
		final Path directory = Paths.get(System.getProperty("shootoff.home", System.getProperty("user.dir")),
				"exercise-data", exercise.getClass().getName());

		try {
			return Files.createDirectories(directory);
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// ---- Shared settings

	@Override
	public double parTime() {
		return parTime;
	}

	@Override
	public void setParTime(double seconds) {
		parTime = seconds;
		fx(() -> {
			if (timingControls != null) timingControls.setParTime(seconds);
		});
	}

	@Override
	public void onParTimeChanged(DoubleConsumer listener) {
		parListeners.add(listener);
		fx(() -> showTimingControls(true));
	}

	@Override
	public DelayRange delayedStart() {
		return delayedStart;
	}

	@Override
	public void setDelayedStart(DelayRange range) {
		delayedStart = range;
		fx(() -> {
			if (timingControls != null) timingControls.setDelayRange(range.minSeconds(), range.maxSeconds());
		});
	}

	@Override
	public void onDelayedStartChanged(Consumer<DelayRange> listener) {
		delayListeners.add(listener);
		fx(() -> showTimingControls(false));
	}

	// The fields show the current values; setting them notifies timingListener, which ignores values it
	// already has
	private void showTimingControls(boolean withParTime) {
		if (timingControls == null) {
			timingControls = new TimingControlsPane(timingListener);
			timingControls.setDelayRange(delayedStart.minSeconds(), delayedStart.maxSeconds());
			context.view().getTrainingExerciseContainer().getChildren().add(timingControls);
			panes.add(timingControls);
		}

		if (withParTime && !timingControls.hasParTime()) {
			timingControls.addParTime(timingListener);
			timingControls.setParTime(parTime);
		}
	}
}
