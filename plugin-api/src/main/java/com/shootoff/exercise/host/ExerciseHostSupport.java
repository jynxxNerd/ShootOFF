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

package com.shootoff.exercise.host;

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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.Shot;
import com.shootoff.camera.processors.ShotProcessor;
import com.shootoff.camera.processors.VirtualMagazineProcessor;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Settings;
import com.shootoff.exercise.Cancellable;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseExecutor;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.ExercisePaths;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.shots.TimerRow;
import com.shootoff.targets.model.Hit;

/**
 * The part of an {@link ExerciseHost} that is the same in every user interface:
 * <ul>
 * <li>the exercise's thread, and every callback on it</li>
 * <li>stopping: the exercise's <tt>stop()</tt> exactly once, then handing back the targets it added and
 * whether it left shot detection paused</li>
 * <li>names: a target, sound, image or resource is looked up in the exercise's own jar first, then in
 * ShootOFF's folder</li>
 * <li>the shot timer row an exercise adds without a shot</li>
 * <li>the shared par time and start delay, and the exercise's listeners for the user's changes</li>
 * </ul>
 * The user interface keeps its own drawing and controls, and removes them when {@link #stop()} says so.
 *
 * @param <T>
 *            the user interface's target type
 */
public final class ExerciseHostSupport<T> {
	private static final Logger logger = LoggerFactory.getLogger(ExerciseHostSupport.class);

	public static final Duration STOP_TIMEOUT = Duration.ofSeconds(2);
	// What the shared timing controls show until an exercise sets its own values, as for v1 exercises
	public static final double DEFAULT_PAR_TIME = 2.0;
	public static final DelayRange DEFAULT_DELAYED_START = new DelayRange(4, 8);

	/**
	 * Where a target an exercise names comes from.
	 */
	public sealed interface TargetSource permits JarTarget, FileTarget, MissingTarget {}

	/**
	 * A target in the exercise's jar.
	 *
	 * @param name
	 *            its resource name in the jar, without a leading <tt>@</tt> or <tt>/</tt>
	 */
	public record JarTarget(String name, URL url) implements TargetSource {}

	/**
	 * A target file in ShootOFF's folder.
	 */
	public record FileTarget(File file) implements TargetSource {}

	/**
	 * A target that is in neither place.
	 */
	public record MissingTarget(String requested) implements TargetSource {}

	/**
	 * What {@link #stop()} hands back for the user interface to undo.
	 *
	 * @param addedTargets
	 *            the targets the exercise added and hasn't removed, in the order it added them
	 * @param restartDetection
	 *            <tt>true</tt> if the exercise left shot detection paused
	 */
	public record Stopped<T>(List<T> addedTargets, boolean restartDetection) {}

	/**
	 * Plays a sound and closes it, then runs <tt>whenDone</tt>.
	 */
	@FunctionalInterface
	public interface SoundSink {
		void play(String name, InputStream sound, Runnable whenDone);
	}

	private final Exercise exercise;
	private final ClassLoader resources;
	private final ExerciseExecutor executor;

	// Guarded by this
	private final List<T> addedTargets = new ArrayList<>();
	private boolean stopped = false;
	private boolean detectionPaused = false;

	private volatile double parTime = DEFAULT_PAR_TIME;
	private volatile DelayRange delayedStart = DEFAULT_DELAYED_START;
	private final List<DoubleConsumer> parListeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<DelayRange>> delayListeners = new CopyOnWriteArrayList<>();

	/**
	 * @param resources
	 *            the exercise's class loader; for a plugin, its jar's
	 */
	public ExerciseHostSupport(Exercise exercise, ClassLoader resources) {
		this.exercise = exercise;
		this.resources = resources;
		executor = new ExerciseExecutor(exercise.metadata().getName());
	}

	public String exerciseName() {
		return exercise.metadata().getName();
	}

	// ---- The exercise's thread

	public void start(ExerciseHost host) {
		executor.execute(() -> exercise.start(host));
	}

	public void deliverShot(Shot shot, Optional<Hit> hit) {
		executor.execute(() -> exercise.onShot(shot, hit));
	}

	/**
	 * @param targets
	 *            the surface's targets, read on the exercise's thread
	 */
	public void targetsChanged(Supplier<List<TargetHandle>> targets) {
		executor.execute(() -> exercise.onTargetsChanged(targets.get()));
	}

	public void reset() {
		executor.execute(exercise::onReset);
	}

	/**
	 * Runs a callback of the exercise's (a button, a setting) on its thread.
	 */
	public void run(Runnable callback) {
		executor.execute(callback);
	}

	public Cancellable schedule(Runnable task, Duration delay) {
		return executor.schedule(task, delay);
	}

	public Cancellable scheduleRepeating(Runnable task, Duration initialDelay, Duration period) {
		return executor.scheduleRepeating(task, initialDelay, period);
	}

	// ---- Stopping

	/**
	 * Stops the exercise: the callbacks already queued run, then its <tt>stop()</tt>, and every
	 * scheduled task is cancelled. Waits up to two seconds for the exercise's thread, which never waits
	 * for a user interface's thread, so a user interface may call this on its own thread. Only the first
	 * call does anything.
	 *
	 * @return what the user interface must undo; empty if the exercise had already stopped
	 */
	public Optional<Stopped<T>> stop() {
		synchronized (this) {
			if (stopped) return Optional.empty();
			stopped = true;
		}

		executor.shutdown(exercise::stop, STOP_TIMEOUT);

		synchronized (this) {
			final Stopped<T> undo = new Stopped<>(List.copyOf(addedTargets), detectionPaused);
			addedTargets.clear();
			return Optional.of(undo);
		}
	}

	public synchronized boolean isStopped() {
		return stopped;
	}

	// ---- Targets

	/**
	 * Remembers a target the exercise added, to remove it when the exercise stops.
	 *
	 * @return <tt>false</tt> if the exercise has stopped meanwhile: the user interface removes the
	 *         target again
	 */
	public synchronized boolean track(T target) {
		if (stopped) return false;

		addedTargets.add(target);
		return true;
	}

	/**
	 * Forgets a target the exercise removed.
	 */
	public synchronized void untrack(T target) {
		addedTargets.remove(target);
	}

	// ---- Shot detection

	/**
	 * Pauses or resumes shot detection for the exercise, unless it has stopped.
	 *
	 * @param detecting
	 *            turns every camera's detection on or off
	 */
	public synchronized void pauseShotDetection(boolean paused, Consumer<Boolean> detecting) {
		if (stopped) return;

		detectionPaused = paused;
		detecting.accept(!paused);
	}

	/**
	 * @return whether the exercise has shot detection paused and is still running, so that something
	 *         turning detection back on for its own reasons (the end of a calibration) can leave it off
	 */
	public synchronized boolean isShotDetectionPaused() {
		return detectionPaused && !stopped;
	}

	// ---- Names

	/**
	 * @return where <tt>targetFile</tt> is: in the exercise's jar, else in ShootOFF's folder (or its
	 *         <tt>targets/</tt> folder)
	 */
	public TargetSource findTarget(String targetFile) {
		final String name = ExercisePaths.resourceName(targetFile);
		final Optional<URL> resource = findResource(name);
		if (resource.isPresent()) return new JarTarget(name, resource.get());

		final Optional<File> file = ExercisePaths.shootoffFile(targetFile, "targets");
		if (file.isPresent()) return new FileTarget(file.get());

		return new MissingTarget(targetFile);
	}

	/**
	 * Opens a sound: from the exercise's jar, else from ShootOFF's folder or its <tt>sounds/</tt> folder.
	 *
	 * @return empty if it can't be found or read, or the exercise has stopped
	 */
	public Optional<InputStream> openSound(String resourceOrFile) {
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

	/**
	 * @return whether {@link #openSound} would find the sound
	 */
	public boolean hasSound(String resourceOrFile) {
		return findResource(ExercisePaths.resourceName(resourceOrFile)).isPresent()
				|| ExercisePaths.shootoffFile(resourceOrFile, "sounds").isPresent();
	}

	/**
	 * Fills the virtual magazine back to its capacity, if it is on.
	 */
	public static void reloadVirtualMagazine(Settings settings) {
		if (!settings.useVirtualMagazine()) return;

		for (final ShotProcessor processor : settings.getShotProcessors()) {
			if (processor instanceof VirtualMagazineProcessor) processor.reset();
		}
	}

	public void playSound(String resourceOrFile, SoundSink sink) {
		openSound(resourceOrFile).ifPresent(sound -> sink.play(resourceOrFile, sound, () -> {}));
	}

	/**
	 * Plays the sounds one after another, skipping any that can't be found, until the exercise stops.
	 */
	public void playSounds(List<String> resourcesOrFiles, SoundSink sink) {
		playInOrder(List.copyOf(resourcesOrFiles), 0, sink);
	}

	private void playInOrder(List<String> sounds, int index, SoundSink sink) {
		if (index >= sounds.size() || isStopped()) return;

		final Optional<InputStream> sound = openSound(sounds.get(index));
		if (sound.isPresent()) {
			sink.play(sounds.get(index), sound.get(), () -> playInOrder(sounds, index + 1, sink));
		} else {
			playInOrder(sounds, index + 1, sink);
		}
	}

	/**
	 * Opens an image, for example a background: from the exercise's jar, else from ShootOFF's own
	 * resources (e.g. <tt>arena/backgrounds/...</tt>).
	 *
	 * @param name
	 *            a resource name (see {@link ExercisePaths#resourceName})
	 * @return empty if it is in neither place
	 */
	public Optional<InputStream> openImage(String name) throws IOException {
		final Optional<URL> resource = findResource(name);
		if (resource.isPresent()) return Optional.of(open(resource.get()));

		return Optional.ofNullable(ExerciseHostSupport.class.getResourceAsStream("/" + name));
	}

	/**
	 * @return a resource from the exercise's own jar
	 */
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

	/**
	 * @return a resource in the exercise's own jar only: its class loader's <tt>getResource</tt> would
	 *         search ShootOFF's classpath first
	 */
	public Optional<URL> findResource(String name) {
		if (resources == null || name.isEmpty()) return Optional.empty();

		return Optional
				.ofNullable(resources instanceof URLClassLoader jar ? jar.findResource(name) : resources.getResource(name));
	}

	/**
	 * Opens a resource uncached, so the exercise's jar isn't held open and can be replaced while ShootOFF
	 * runs.
	 */
	public static InputStream open(URL url) throws IOException {
		final URLConnection connection = url.openConnection();
		connection.setUseCaches(false);
		return new BufferedInputStream(connection.getInputStream());
	}

	/**
	 * @return <tt>&lt;shootoff.home&gt;/exercise-data/&lt;exercise class&gt;/</tt>, created if needed
	 */
	public Path dataDirectory() {
		final Path directory = Paths.get(System.getProperty("shootoff.home", System.getProperty("user.dir")),
				"exercise-data", exercise.getClass().getName());

		try {
			return Files.createDirectories(directory);
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// ---- Timer rows

	/**
	 * @return the shot a row that no shot made stands for: v1's drill injected a red shot at (-10, -10)
	 *         at the row's time, so the row reads Laser "red"
	 */
	public static Shot noShot(long timeMillis) {
		return new Shot(ShotColor.RED, -10, -10, timeMillis);
	}

	/**
	 * @param previous
	 *            the shot of the timer's latest row, if any
	 * @return the row {@link ExerciseHost#addTimerRow} adds: it reads like a red shot's row at
	 *         <tt>timeMillis</tt>
	 */
	public static TimerRow timerRow(long timeMillis, Optional<Shot> previous) {
		return TimerRow.of(noShot(timeMillis), previous, false, false);
	}

	// ---- The shared par time and start delay

	public double parTime() {
		return parTime;
	}

	/**
	 * Sets the par time, as the exercise does: its own listeners don't hear it.
	 */
	public void setParTime(double seconds) {
		parTime = seconds;
	}

	public void onParTimeChanged(DoubleConsumer listener) {
		parListeners.add(listener);
	}

	/**
	 * The user set the par time: the exercise's listeners hear it on its thread, unless it didn't change.
	 */
	public void userChangedParTime(double seconds) {
		if (Double.compare(seconds, parTime) == 0) return;

		parTime = seconds;
		for (final DoubleConsumer listener : parListeners) {
			executor.execute(() -> listener.accept(seconds));
		}
	}

	public DelayRange delayedStart() {
		return delayedStart;
	}

	/**
	 * Sets the start delay, as the exercise does: its own listeners don't hear it.
	 */
	public void setDelayedStart(DelayRange range) {
		delayedStart = range;
	}

	public void onDelayedStartChanged(Consumer<DelayRange> listener) {
		delayListeners.add(listener);
	}

	/**
	 * The user set the start delay: the exercise's listeners hear it on its thread, unless it didn't
	 * change or the minimum is above the maximum.
	 */
	public void userChangedDelayedStart(int minSeconds, int maxSeconds) {
		if (minSeconds > maxSeconds) return;

		final DelayRange range = new DelayRange(minSeconds, maxSeconds);
		if (range.equals(delayedStart)) return;

		delayedStart = range;
		for (final Consumer<DelayRange> listener : delayListeners) {
			executor.execute(() -> listener.accept(range));
		}
	}
}
