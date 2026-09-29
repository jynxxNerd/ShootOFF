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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.CameraView;
import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ArenaShot;
import com.shootoff.camera.shot.DisplayShot;
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
import com.shootoff.exercise.host.ExerciseHostSupport;
import com.shootoff.exercise.host.ExerciseHostSupport.FileTarget;
import com.shootoff.exercise.host.ExerciseHostSupport.JarTarget;
import com.shootoff.exercise.host.ExerciseHostSupport.MissingTarget;
import com.shootoff.exercise.host.ExerciseHostSupport.Stopped;
import com.shootoff.exercise.host.SavedBackground;
import com.shootoff.geom.Point;
import com.shootoff.geom.Size;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.LocatedImage;
import com.shootoff.gui.MirroredCanvasManager;
import com.shootoff.gui.ParListener;
import com.shootoff.gui.ShotEntry;
import com.shootoff.gui.TimingControlsPane;
import com.shootoff.gui.pane.ProjectorArenaPane;
import com.shootoff.gui.targets.MirroredTarget;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.targets.Target;
import com.shootoff.targets.io.TargetIO;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.TargetDefinition;
import com.shootoff.targets.model.TargetId;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
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
 * Runs one v2 {@link Exercise} in the JavaFX app, on the projector arena or on a camera feed. What
 * every user interface's host shares (the exercise's thread, stopping, names, timer rows, the shared
 * par time and start delay) is plugin-api's {@link ExerciseHostSupport}; this draws the exercise on the
 * JavaFX scene.
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

	private static final int COLUMN_WIDTH = 60;

	private final ExerciseHostContext context;
	private final ExerciseHostSupport<TargetView> support;

	// JavaFX thread only
	private final List<Node> canvasNodes = new ArrayList<>();
	private final List<Node> markers = new ArrayList<>();
	private final Map<CanvasManager, Label> feedLabels = new LinkedHashMap<>();
	private final List<Node> panes = new ArrayList<>();
	private final List<Button> buttons = new ArrayList<>();
	private final List<TableColumn<ShotEntry, String>> columns = new ArrayList<>();
	private TimingControlsPane timingControls = null;
	private final SavedBackground<LocatedImage> savedBackground = new SavedBackground<>();
	private boolean tornDown = false;

	// The shared timing controls' listener; it runs on the JavaFX thread
	private final ParListener timingListener = new ParListener() {
		@Override
		public void updatedDelayedStartInterval(int min, int max) {
			support.userChangedDelayedStart(min, max);
		}

		@Override
		public void updatedParInterval(double seconds) {
			support.userChangedParTime(seconds);
		}
	};

	public JavaFxExerciseHost(Exercise exercise, ExerciseHostContext context) {
		this.context = context;
		support = new ExerciseHostSupport<>(exercise, context.resources());
	}

	// ---- Lifecycle, driven by HostedExercise

	public void start() {
		support.start(this);
	}

	/**
	 * Hands a shot to the exercise: arena shots to a projector exercise, camera shots to a camera
	 * exercise.
	 */
	public void deliverShot(Shot shot, Optional<Hit> hit) {
		if (isProjector() != (shot instanceof ArenaShot)) return;

		support.deliverShot(shot, hit);
	}

	public void targetsChanged() {
		support.targetsChanged(this::targets);
	}

	public void reset() {
		support.reset();
	}

	/**
	 * Stops the exercise and removes everything it added. Waits up to two seconds for the exercise
	 * thread, which never waits for the JavaFX thread, so this may run on the JavaFX thread.
	 */
	public void stop() {
		final Optional<Stopped<TargetView>> stopped = support.stop();
		if (stopped.isEmpty()) return;

		for (final TargetView target : stopped.get().addedTargets()) {
			context.canvas().removeTarget(target);
		}

		if (stopped.get().restartDetection()) context.cameras().setDetectingAll(true);

		// Queued after every scene change the exercise made, so all of them are undone. When
		// already on the FX thread, run it now instead: a caller that stops an exercise and then
		// touches the scene itself in the same FX call (for example enabling calibration) must see
		// the teardown, including the background restore, as already done.
		if (Platform.isFxApplicationThread()) {
			tearDown();
		} else {
			Platform.runLater(this::tearDown);
		}
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

		savedBackground.restore(previous -> context.arena().get().setArenaBackground(previous.orElse(null)));
	}

	// Queues a scene change. Changes queued after the tear down are dropped.
	private void fx(Runnable change) {
		Platform.runLater(() -> {
			if (!tornDown) change.run();
		});
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
			logger.warn("{} set a background, but only the projector arena has one", support.exerciseName());
			return;
		}

		final String name = ExercisePaths.resourceName(imageResource);
		final LocatedImage background;
		try {
			final Optional<InputStream> image = support.openImage(name);
			if (image.isEmpty()) {
				logger.error("Can't find background {}", imageResource);
				return;
			}
			try (InputStream in = image.get()) {
				background = new LocatedImage(in, "/" + name);
			}
		} catch (final IOException e) {
			logger.error("Can't read background {}", imageResource, e);
			return;
		}

		fx(() -> {
			final ProjectorArenaPane arena = context.arena().get();
			savedBackground.beforeChange(arena::getArenaBackground);
			arena.setArenaBackground(background);
		});
	}

	// ---- Targets

	@Override
	public Optional<TargetHandle> addTarget(String targetFile, double x, double y) {
		if (support.isStopped()) return Optional.empty();

		final Optional<Target> added = loadTarget(targetFile);
		if (added.isEmpty()) return Optional.empty();

		final TargetView target = (TargetView) added.get();
		target.setPosition(x, y);

		final Optional<ProjectorArenaPane> arena = context.arena();
		if (arena.isPresent() && arena.get().getPerspectiveManager().isPresent()
				&& arena.get().getPerspectiveManager().get().isInitialized()) {
			arena.get().resizeTargetToDefaultPerspective(target);
		}

		if (support.track(target)) return Optional.of(new FxTargetHandle(target));

		// Stopped while loading
		context.canvas().removeTarget(target);
		return Optional.empty();
	}

	private Optional<Target> loadTarget(String targetFile) {
		return switch (support.findTarget(targetFile)) {
		case JarTarget jar -> loadJarTarget(jar);
		case FileTarget file -> context.canvas().addTarget(file.file(), false);
		case MissingTarget missing -> {
			logger.error("Can't find target {}", missing.requested());
			yield Optional.empty();
		}
		};
	}

	private Optional<Target> loadJarTarget(JarTarget jar) {
		// The projector arena's canvas mirrors each target on the arena tab's. Add it as v1's
		// ProjectorTrainingExerciseBase does, by file: that returns this canvas's copy, the one the
		// projector shows and removeTarget expects. Adding the components here instead returns the
		// tab's copy, which the exercise could neither hide on the projector nor remove.
		if (context.canvas() instanceof MirroredCanvasManager) {
			return context.canvas().addTarget(new File("@" + jar.name()), false);
		}

		try (InputStream in = ExerciseHostSupport.open(jar.url())) {
			return TargetIO.loadTarget(in, false, context.resources()).map(
					components -> context.canvas().addTarget(components.withTargetFile(new File("@" + jar.name())), true));
		} catch (final IOException e) {
			logger.error("Can't read target {} from the exercise", jar.name(), e);
			return Optional.empty();
		}
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
			// MirroredTarget mirrors placement but not visibility: hide the arena tab's copy too
			if (view instanceof MirroredTarget mirrored && mirrored.getMirroredTarget() != null) {
				mirrored.getMirroredTarget().setVisible(visible);
			}
		}

		@Override
		public void remove() {
			support.untrack(view);
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
		if (support.isStopped()) return;

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
		button.setOnAction(event -> support.run(onClick));

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
				if (newValue != null) support.run(() -> onChange.accept(newValue));
			});

			addSettingPane(label, spinner);
		});
	}

	@Override
	public void addYesNoSetting(String label, boolean initial, Consumer<Boolean> onChange) {
		fx(() -> {
			final CheckBox checkBox = new CheckBox();
			checkBox.setSelected(initial);
			checkBox.selectedProperty().addListener((observable, oldValue, newValue) -> {
				support.run(() -> onChange.accept(newValue));
			});

			addSettingPane(label, checkBox);
		});
	}

	@Override
	public void addChoiceSetting(String label, List<String> choices, String initial, Consumer<String> onChange) {
		if (!choices.contains(initial)) {
			throw new IllegalArgumentException(label + ": " + initial + " isn't one of " + choices);
		}
		final List<String> items = List.copyOf(choices);

		fx(() -> {
			final ComboBox<String> comboBox = new ComboBox<>(FXCollections.observableArrayList(items));
			comboBox.setValue(initial);
			comboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
				if (newValue != null) support.run(() -> onChange.accept(newValue));
			});

			addSettingPane(label, comboBox);
		});
	}

	// A setting's label and control, in the exercise pane
	private void addSettingPane(String label, Node control) {
		final HBox pane = new HBox(10, new Label(label), control);
		pane.setAlignment(Pos.CENTER_LEFT);
		context.view().getTrainingExerciseContainer().getChildren().add(pane);
		panes.add(pane);
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
			// Guarded together with the pipeline's own row append (CanvasManager.appendShotEntry),
			// which may run on the shot queue's thread at the same moment (spec 2.2)
			synchronized (table.getItems()) {
				if (table.getItems().isEmpty()) {
					logger.warn("{} set {} on an empty shot timer", support.exerciseName(), name);
					return;
				}

				table.getItems().get(table.getItems().size() - 1).setExerciseValue(name, value);
			}
			table.refresh();
		});
	}

	@Override
	public void styleLastRow(RowStyle style) {
		final Color color = Color.web(style.highlightColor());

		fx(() -> {
			final ObservableList<ShotEntry> rows = context.view().getShotEntryTable().getItems();
			// See setColumnValue
			synchronized (rows) {
				if (rows.isEmpty()) return;

				final int last = rows.size() - 1;
				rows.set(last, rows.get(last).withRowColor(Optional.of(color)));
			}
		});
	}

	// The row v1's drill got from a fake red shot at (-10, -10): the shot timer computes Time, Split and
	// Laser from the shot as usual, but nothing draws, hit-tests or records it
	@Override
	public void addTimerRow(long timeMillis, RowStyle style) {
		final Color color = Color.web(style.highlightColor());
		final DisplayShot noShot = new DisplayShot(ExerciseHostSupport.noShot(timeMillis),
				context.config().getMarkerRadius());

		fx(() -> {
			final ObservableList<ShotEntry> rows = context.view().getShotEntryTable().getItems();
			// See setColumnValue
			synchronized (rows) {
				final Optional<Shot> previous = rows.isEmpty() ? Optional.empty()
						: Optional.of(rows.get(rows.size() - 1).getShot());
				rows.add(new ShotEntry(noShot, previous, Optional.of(color), false, false));
			}
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
		if (support.isStopped()) return;

		context.cameras().clearShots();
		fx(() -> {
			canvasChildren().removeAll(markers);
			canvasNodes.removeAll(markers);
			markers.clear();
		});
	}

	@Override
	public void resetTargets() {
		if (support.isStopped()) return;

		fx(() -> {
			// Each camera's canvas stands its targets and the arena's up and clears their shots, as Reset does
			// without restarting the cameras' shot timers
			final Set<CanvasManager> canvases = new LinkedHashSet<>();
			canvases.add(context.canvas());
			for (final CameraView view : context.cameras().getCameraViews()) {
				if (view instanceof CanvasManager canvas) canvases.add(canvas);
			}
			canvases.forEach(CanvasManager::reset);

			canvasChildren().removeAll(markers);
			canvasNodes.removeAll(markers);
			markers.clear();
		});
	}

	@Override
	public void reloadVirtualMagazine() {
		if (!support.isStopped()) ExerciseHostSupport.reloadVirtualMagazine(context.config());
	}

	@Override
	public void pauseShotDetection(boolean paused) {
		support.pauseShotDetection(paused, context.cameras()::setDetectingAll);
	}

	// ---- Sound

	@Override
	public void playSound(String resourceOrFile) {
		support.playSound(resourceOrFile, context.sounds()::play);
	}

	@Override
	public void playSounds(List<String> resourcesOrFiles) {
		support.playSounds(resourcesOrFiles, context.sounds()::play);
	}

	@Override
	public boolean hasSound(String resourceOrFile) {
		return support.hasSound(resourceOrFile);
	}

	@Override
	public void say(String text) {
		if (!support.isStopped()) context.sounds().say(text);
	}

	// ---- Time

	@Override
	public long currentTimeMillis() {
		return System.currentTimeMillis();
	}

	@Override
	public Cancellable schedule(Runnable task, Duration delay) {
		return support.schedule(task, delay);
	}

	@Override
	public Cancellable scheduleRepeating(Runnable task, Duration initialDelay, Duration period) {
		return support.scheduleRepeating(task, initialDelay, period);
	}

	// ---- Resources

	@Override
	public Optional<InputStream> resource(String path) {
		return support.resource(path);
	}

	@Override
	public Path dataDirectory() {
		return support.dataDirectory();
	}

	// ---- Shared settings

	@Override
	public double parTime() {
		return support.parTime();
	}

	@Override
	public void setParTime(double seconds) {
		support.setParTime(seconds);
		fx(() -> {
			if (timingControls != null) timingControls.setParTime(seconds);
		});
	}

	@Override
	public void onParTimeChanged(DoubleConsumer listener) {
		support.onParTimeChanged(listener);
		fx(() -> showTimingControls(true));
	}

	@Override
	public DelayRange delayedStart() {
		return support.delayedStart();
	}

	@Override
	public void setDelayedStart(DelayRange range) {
		support.setDelayedStart(range);
		fx(() -> {
			if (timingControls != null) timingControls.setDelayRange(range.minSeconds(), range.maxSeconds());
		});
	}

	@Override
	public void onDelayedStartChanged(Consumer<DelayRange> listener) {
		support.onDelayedStartChanged(listener);
		fx(() -> showTimingControls(false));
	}

	// The fields show the current values; setting them notifies timingListener, which ignores values it
	// already has
	private void showTimingControls(boolean withParTime) {
		if (timingControls == null) {
			timingControls = new TimingControlsPane(timingListener);
			timingControls.setDelayRange(support.delayedStart().minSeconds(), support.delayedStart().maxSeconds());
			context.view().getTrainingExerciseContainer().getChildren().add(timingControls);
			panes.add(timingControls);
		}

		if (withParTime && !timingControls.hasParTime()) {
			timingControls.addParTime(timingListener);
			timingControls.setParTime(support.parTime());
		}
	}
}
