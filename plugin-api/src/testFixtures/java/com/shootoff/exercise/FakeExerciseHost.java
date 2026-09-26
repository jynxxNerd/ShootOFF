package com.shootoff.exercise;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.geom.Point;
import com.shootoff.geom.Size;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.HitTester;
import com.shootoff.targets.model.PlacedTarget;
import com.shootoff.targets.model.Placement;
import com.shootoff.targets.model.ResourceResolver;
import com.shootoff.targets.model.TargetDefinition;
import com.shootoff.targets.model.TargetDefinitions;
import com.shootoff.targets.model.TargetFormatException;
import com.shootoff.targets.model.TargetId;
import com.shootoff.targets.model.TargetSet;

/**
 * An in-memory {@link ExerciseHost} for exercise tests. It records what the exercise shows, and the
 * test plays the user and the clock:
 * <ul>
 * <li>{@link #start}, {@link #reset} and {@link #stop} drive the exercise's lifecycle;</li>
 * <li>{@link #shoot(ShotColor, double, double)} fires a shot, hit-tested against the exercise's
 * visible targets;</li>
 * <li>{@link #advance} moves the manual clock and runs the tasks that fall due;</li>
 * <li>{@link #click}, {@link #changeSetting}, {@link #changeParTime} and {@link #changeDelayedStart}
 * use the controls.</li>
 * </ul>
 * Everything runs on the calling thread, which plays the exercise thread.
 */
public class FakeExerciseHost implements ExerciseHost {
	public static final Size DEFAULT_SURFACE = new Size(1280, 720);
	/** The clock's time, in milliseconds, when the host is created */
	public static final long START_TIME = 1_000_000;
	/** What ShootOFF's shared controls show until an exercise sets its own values */
	public static final double DEFAULT_PAR_TIME = 2.0;
	public static final DelayRange DEFAULT_DELAYED_START = new DelayRange(4, 8);

	public record ShownText(String text, double x, double y, TextStyle style) {}

	public record ShownMarker(double x, double y, ShotStyle style) {}

	/**
	 * A shot timer row: the shot (none for an {@link #addTimerRow} row), its time, the exercise's
	 * column values, and its highlight
	 */
	public record Row(Optional<Shot> shot, long timeMillis, Map<String, String> values, Optional<RowStyle> style) {}

	private final Size surfaceSize;
	private final boolean projector;
	private final Path dataDirectory;
	private ClassLoader resources = Thread.currentThread().getContextClassLoader();

	private final TargetSet targetSet = new TargetSet();
	private final List<FakeText> texts = new ArrayList<>();
	private final List<FakeButton> buttons = new ArrayList<>();
	private final Map<String, FakeSetting> settings = new LinkedHashMap<>();
	private final List<String> columns = new ArrayList<>();
	private final List<FakeRow> rows = new ArrayList<>();
	private final List<FakeMarker> markers = new ArrayList<>();
	private final List<String> sounds = new ArrayList<>();
	private final List<String> spoken = new ArrayList<>();
	private final List<String> messages = new ArrayList<>();
	private final List<DoubleConsumer> parListeners = new ArrayList<>();
	private final List<Consumer<DelayRange>> delayListeners = new ArrayList<>();
	private final PriorityQueue<Task> tasks = new PriorityQueue<>(
			Comparator.comparingLong((Task task) -> task.due).thenComparingLong(task -> task.sequence));

	private Optional<Exercise> exercise = Optional.empty();
	private Optional<String> background = Optional.empty();
	private boolean detectionPaused = false;
	private boolean stopped = false;
	private double parTime = DEFAULT_PAR_TIME;
	private DelayRange delayedStart = DEFAULT_DELAYED_START;
	private long now = START_TIME;
	private long nextSequence = 0;

	/**
	 * A projector host with the default surface and a new temporary data directory.
	 */
	public FakeExerciseHost() {
		this(DEFAULT_SURFACE, true, newTemporaryDirectory());
	}

	public FakeExerciseHost(Size surfaceSize, boolean projector, Path dataDirectory) {
		this.surfaceSize = surfaceSize;
		this.projector = projector;
		this.dataDirectory = dataDirectory;

		try {
			Files.createDirectories(dataDirectory);
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Path newTemporaryDirectory() {
		try {
			return Files.createTempDirectory("exercise-data");
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Sets where resources of "the exercise's jar" come from.
	 */
	public FakeExerciseHost withResources(ClassLoader resources) {
		this.resources = resources;
		return this;
	}

	// ---- The test's side: lifecycle, shots, the clock and the user

	public void start(Exercise exercise) {
		this.exercise = Optional.of(exercise);
		exercise.start(this);
	}

	/**
	 * Presses Reset: clears the shot timer, then calls the exercise's <tt>onReset</tt>.
	 */
	public void reset() {
		if (stopped) return;
		rows.clear();
		exercise.ifPresent(Exercise::onReset);
	}

	/**
	 * Stops the exercise, then removes everything it added and cancels its tasks, as ShootOFF does.
	 */
	public void stop() {
		if (stopped) return;
		exercise.ifPresent(Exercise::stop);
		stopped = true;

		for (final PlacedTarget target : targetSet.getTargets()) {
			targetSet.remove(target.getId());
		}
		texts.clear();
		buttons.clear();
		settings.clear();
		columns.clear();
		markers.clear();
		parListeners.clear();
		delayListeners.clear();
		tasks.clear();
		background = Optional.empty();
		detectionPaused = false;
	}

	/**
	 * Fires a shot at (<tt>x</tt>, <tt>y</tt>), hit-tested against the exercise's visible targets.
	 *
	 * @return <tt>false</tt> if the shot wasn't delivered: detection is paused, or the exercise isn't
	 *         running
	 */
	public boolean shoot(ShotColor color, double x, double y) {
		return shoot(new Shot(color, x, y, now), HitTester.hit(targetSet, x, y));
	}

	/**
	 * Delivers a shot with a hit of the test's choosing.
	 */
	public boolean shoot(Shot shot, Optional<Hit> hit) {
		if (detectionPaused || stopped || exercise.isEmpty()) return false;

		rows.add(new FakeRow(Optional.of(shot), shot.getTimestamp()));
		exercise.get().onShot(shot, hit);
		return true;
	}

	/**
	 * Moves the clock forward, running every task that falls due on the way, in time order (tasks
	 * due at the same time run in the order they were scheduled).
	 */
	public void advance(Duration duration) {
		final long end = now + duration.toMillis();

		while (!tasks.isEmpty() && tasks.peek().due <= end) {
			final Task task = tasks.poll();
			now = task.due;

			if (task.period > 0) {
				// Rescheduled before it runs, so it can cancel itself
				task.due += task.period;
				task.sequence = nextSequence++;
				tasks.add(task);
			}

			task.action.run();
		}

		now = end;
	}

	public int pendingTasks() {
		return tasks.size();
	}

	public void click(String label) {
		final FakeButton button = buttons.stream().filter(b -> b.label.equals(label)).findFirst()
				.orElseThrow(() -> new IllegalStateException("No button labeled " + label + "; the buttons are "
						+ buttonLabels()));
		button.onClick.run();
	}

	public void changeSetting(String label, double value) {
		final FakeSetting setting = settings.get(label);
		if (setting == null) throw new IllegalStateException("No setting labeled " + label);
		if (value < setting.min || value > setting.max) {
			throw new IllegalArgumentException(String.format("%s must be between %s and %s, not %s", label,
					setting.min, setting.max, value));
		}

		setting.value = value;
		setting.onChange.accept(value);
	}

	/**
	 * The user sets the shared par time: the exercise's listeners hear it.
	 */
	public void changeParTime(double seconds) {
		parTime = seconds;
		for (final DoubleConsumer listener : List.copyOf(parListeners)) {
			listener.accept(seconds);
		}
	}

	/**
	 * The user sets the shared delay: the exercise's listeners hear it.
	 */
	public void changeDelayedStart(DelayRange range) {
		delayedStart = range;
		for (final Consumer<DelayRange> listener : List.copyOf(delayListeners)) {
			listener.accept(range);
		}
	}

	// ---- The test's side: what the exercise shows

	public List<ShownText> shownTexts() {
		return texts.stream().map(t -> new ShownText(t.text, t.x, t.y, t.style)).toList();
	}

	/**
	 * @return the latest text shown with its top-left corner at (<tt>x</tt>, <tt>y</tt>)
	 */
	public Optional<String> textAt(double x, double y) {
		Optional<String> found = Optional.empty();
		for (final FakeText text : texts) {
			if (text.x == x && text.y == y) found = Optional.of(text.text);
		}
		return found;
	}

	public List<String> messages() {
		return List.copyOf(messages);
	}

	public List<String> buttonLabels() {
		return buttons.stream().map(b -> b.label).toList();
	}

	public List<String> settingLabels() {
		return List.copyOf(settings.keySet());
	}

	public double settingValue(String label) {
		final FakeSetting setting = settings.get(label);
		if (setting == null) throw new IllegalStateException("No setting labeled " + label);
		return setting.value;
	}

	public List<String> columns() {
		return List.copyOf(columns);
	}

	public List<Row> rows() {
		return rows.stream().map(r -> new Row(r.shot, r.timeMillis, Map.copyOf(r.values), r.style)).toList();
	}

	public List<ShownMarker> shotMarkers() {
		return markers.stream().map(m -> new ShownMarker(m.x, m.y, m.style)).toList();
	}

	/**
	 * @return every sound played, in order, as the exercise named it
	 */
	public List<String> sounds() {
		return List.copyOf(sounds);
	}

	public List<String> spoken() {
		return List.copyOf(spoken);
	}

	public Optional<String> background() {
		return background;
	}

	public boolean isShotDetectionPaused() {
		return detectionPaused;
	}

	public boolean isStopped() {
		return stopped;
	}

	public boolean isVisible(TargetHandle target) {
		return targetSet.get(target.id()).map(PlacedTarget::isVisible).orElse(false);
	}

	public boolean isListeningForParTime() {
		return !parListeners.isEmpty();
	}

	public boolean isListeningForDelayedStart() {
		return !delayListeners.isEmpty();
	}

	// ---- ExerciseHost

	@Override
	public Size surfaceSize() {
		return surfaceSize;
	}

	@Override
	public boolean isProjector() {
		return projector;
	}

	@Override
	public void setBackground(String imageResource) {
		if (!stopped && projector) background = Optional.of(imageResource);
	}

	@Override
	public Optional<TargetHandle> addTarget(String targetFile, double x, double y) {
		if (stopped) return Optional.empty();

		final Optional<TargetDefinition> definition = loadDefinition(targetFile);
		if (definition.isEmpty()) return Optional.empty();

		final PlacedTarget placed = targetSet.add(definition.get(), Placement.ORIGIN.withPosition(x, y));
		return Optional.of(new FakeTarget(placed.getId(), placed.getDefinition()));
	}

	private Optional<TargetDefinition> loadDefinition(String targetFile) {
		final String name = ExercisePaths.resourceName(targetFile);

		try {
			if (!name.isEmpty() && resources.getResource(name) != null) {
				try (InputStream in = resources.getResourceAsStream(name)) {
					return Optional.of(TargetDefinitions.load(in, ResourceResolver.classLoader(resources)));
				}
			}

			final Optional<File> file = ExercisePaths.shootoffFile(targetFile, "targets");
			if (file.isEmpty()) return Optional.empty();
			return Optional.of(TargetDefinitions.load(file.get().toPath()));
		} catch (final IOException | TargetFormatException e) {
			return Optional.empty();
		}
	}

	@Override
	public List<TargetHandle> targets() {
		final List<TargetHandle> handles = new ArrayList<>();
		for (final PlacedTarget target : targetSet.getTargets()) {
			handles.add(new FakeTarget(target.getId(), target.getDefinition()));
		}
		return handles;
	}

	@Override
	public TextHandle showText(String text, double x, double y, TextStyle style) {
		final FakeText shown = new FakeText(text, x, y, style);
		if (!stopped) texts.add(shown);

		return new TextHandle() {
			@Override
			public void setText(String newText) {
				shown.text = newText;
			}

			@Override
			public void move(double newX, double newY) {
				shown.x = newX;
				shown.y = newY;
			}

			@Override
			public void remove() {
				texts.remove(shown);
			}
		};
	}

	@Override
	public void showMessage(String message) {
		if (!stopped) messages.add(message);
	}

	@Override
	public ButtonHandle addButton(String label, Runnable onClick) {
		final FakeButton button = new FakeButton(label, onClick);
		if (!stopped) buttons.add(button);

		return new ButtonHandle() {
			@Override
			public void setLabel(String newLabel) {
				button.label = newLabel;
			}

			@Override
			public void remove() {
				buttons.remove(button);
			}
		};
	}

	@Override
	public void addNumberSetting(String label, double initial, double min, double max, double step,
			DoubleConsumer onChange) {
		if (!stopped) settings.put(label, new FakeSetting(initial, min, max, onChange));
	}

	@Override
	public void addColumn(String name) {
		if (!stopped && !columns.contains(name)) columns.add(name);
	}

	@Override
	public void setColumnValue(String name, String value) {
		if (!stopped && !rows.isEmpty()) rows.get(rows.size() - 1).values.put(name, value);
	}

	@Override
	public void styleLastRow(RowStyle style) {
		if (!stopped && !rows.isEmpty()) rows.get(rows.size() - 1).style = Optional.of(style);
	}

	@Override
	public void addTimerRow(long timeMillis, RowStyle style) {
		if (stopped) return;

		final FakeRow row = new FakeRow(Optional.empty(), timeMillis);
		row.style = Optional.of(style);
		rows.add(row);
	}

	@Override
	public ShotMarkerHandle showShotMarker(double x, double y, ShotStyle style) {
		final FakeMarker marker = new FakeMarker(x, y, style);
		if (!stopped) markers.add(marker);
		return () -> markers.remove(marker);
	}

	@Override
	public void clearShots() {
		if (stopped) return;
		rows.clear();
		markers.clear();
	}

	@Override
	public void pauseShotDetection(boolean paused) {
		if (!stopped) detectionPaused = paused;
	}

	@Override
	public void playSound(String resourceOrFile) {
		if (!stopped) sounds.add(resourceOrFile);
	}

	@Override
	public void playSounds(List<String> resourcesOrFiles) {
		if (!stopped) sounds.addAll(resourcesOrFiles);
	}

	@Override
	public void say(String text) {
		if (!stopped) spoken.add(text);
	}

	@Override
	public long currentTimeMillis() {
		return now;
	}

	@Override
	public Cancellable schedule(Runnable task, Duration delay) {
		return schedule(task, delay.toMillis(), 0);
	}

	@Override
	public Cancellable scheduleRepeating(Runnable task, Duration initialDelay, Duration period) {
		return schedule(task, initialDelay.toMillis(), Math.max(1, period.toMillis()));
	}

	private Cancellable schedule(Runnable action, long delayMillis, long periodMillis) {
		if (stopped) return () -> {};

		final Task task = new Task(now + delayMillis, periodMillis, nextSequence++, action);
		tasks.add(task);
		return () -> tasks.remove(task);
	}

	@Override
	public Optional<InputStream> resource(String path) {
		return Optional.ofNullable(resources.getResourceAsStream(ExercisePaths.resourceName(path)));
	}

	@Override
	public Path dataDirectory() {
		return dataDirectory;
	}

	@Override
	public double parTime() {
		return parTime;
	}

	@Override
	public void setParTime(double seconds) {
		if (!stopped) parTime = seconds;
	}

	@Override
	public void onParTimeChanged(DoubleConsumer listener) {
		if (!stopped) parListeners.add(listener);
	}

	@Override
	public DelayRange delayedStart() {
		return delayedStart;
	}

	@Override
	public void setDelayedStart(DelayRange range) {
		if (!stopped) delayedStart = range;
	}

	@Override
	public void onDelayedStartChanged(Consumer<DelayRange> listener) {
		if (!stopped) delayListeners.add(listener);
	}

	// ---- What the host keeps

	private final class FakeTarget implements TargetHandle {
		private final TargetId id;
		private final TargetDefinition definition;

		FakeTarget(TargetId id, TargetDefinition definition) {
			this.id = id;
			this.definition = definition;
		}

		private PlacedTarget placed() {
			return targetSet.get(id).orElseThrow(() -> new IllegalStateException("The target was removed"));
		}

		@Override
		public TargetId id() {
			return id;
		}

		@Override
		public void move(double x, double y) {
			targetSet.move(id, x, y);
		}

		@Override
		public void resize(double width, double height) {
			targetSet.resize(id, width, height);
		}

		@Override
		public void setVisible(boolean visible) {
			targetSet.setVisible(id, visible);
		}

		@Override
		public void remove() {
			targetSet.remove(id);
		}

		@Override
		public Point position() {
			return placed().getPosition();
		}

		@Override
		public Size size() {
			return placed().getSize();
		}

		@Override
		public TargetDefinition definition() {
			return definition;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof FakeTarget other && other.id.equals(id);
		}

		@Override
		public int hashCode() {
			return id.hashCode();
		}
	}

	private static final class FakeText {
		String text;
		double x;
		double y;
		final TextStyle style;

		FakeText(String text, double x, double y, TextStyle style) {
			this.text = text;
			this.x = x;
			this.y = y;
			this.style = style;
		}
	}

	private static final class FakeButton {
		String label;
		final Runnable onClick;

		FakeButton(String label, Runnable onClick) {
			this.label = label;
			this.onClick = onClick;
		}
	}

	private static final class FakeSetting {
		double value;
		final double min;
		final double max;
		final DoubleConsumer onChange;

		FakeSetting(double value, double min, double max, DoubleConsumer onChange) {
			this.value = value;
			this.min = min;
			this.max = max;
			this.onChange = onChange;
		}
	}

	private static final class FakeRow {
		final Optional<Shot> shot;
		final long timeMillis;
		final Map<String, String> values = new LinkedHashMap<>();
		Optional<RowStyle> style = Optional.empty();

		FakeRow(Optional<Shot> shot, long timeMillis) {
			this.shot = shot;
			this.timeMillis = timeMillis;
		}
	}

	private record FakeMarker(double x, double y, ShotStyle style) {}

	private static final class Task {
		long due;
		final long period;
		long sequence;
		final Runnable action;

		Task(long due, long period, long sequence, Runnable action) {
			this.due = due;
			this.period = period;
			this.sequence = sequence;
			this.action = action;
		}
	}
}
