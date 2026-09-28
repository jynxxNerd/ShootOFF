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

package com.shootoff.calibration;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.perspective.PerspectiveManager;
import com.shootoff.config.CalibrationOption;
import com.shootoff.config.Settings;
import com.shootoff.geom.ArenaGeometry;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

/**
 * Calibrating the projector arena, whatever draws it:
 * <ol>
 * <li><b>Start</b>: a running projector exercise is stopped (it could change the arena under the
 * camera), the arena hides its shots, and the camera stops detecting shots and forgets the old
 * bounds.</li>
 * <li><b>Full screen</b>: auto-calibration needs the arena full screen on the projector; until it is,
 * the user is asked to make it so.</li>
 * <li><b>Auto-detect</b>: the arena's background is saved and the calibration pattern shown; the
 * camera looks for it.</li>
 * <li><b>Success</b>: the camera reports the pattern's bounds, which become the arena's projection,
 * and calibration ends.</li>
 * <li><b>Timeout</b>: after 12 seconds the user gets a box to drag over the projection by hand
 * (headless: after 45 seconds calibration ends; unattended: after 30 seconds calibration is cancelled,
 * see {@link #startUnattended}).</li>
 * <li><b>End</b>: stopping with the box calibrates to it. Then the perspective is worked out, the
 * arena's background comes back, detection resumes after a moment, and the projector exercise that
 * was stopped starts again, last.</li>
 * </ol>
 * The flow runs on whatever thread calls it, as the JavaFX app always has (the camera reports success
 * on its own thread); its timers come back on the UI thread through {@link View#runOnUiThread}.
 */
public final class CalibrationFlow {
	private static final Logger logger = LoggerFactory.getLogger(CalibrationFlow.class);

	public static final long AUTO_CALIBRATION_TIMEOUT = 12 * 1000;
	public static final long AUTO_CALIBRATION_TIMEOUT_HEADLESS = 45 * 1000;
	// A camera just plugged in (or just opened) can take 10-15 seconds to settle its exposure, and until then
	// the pattern may be washed out: an unattended calibration, with no one to press Calibrate again, waits
	// that out (spec §8 Revision 3)
	public static final long AUTO_CALIBRATION_TIMEOUT_UNATTENDED = 30 * 1000;
	// The pattern going away can look like shots
	public static final long DETECTION_RESTART_DELAY = 600;
	// Before auto-calibrating once the arena is full screen (ShootOFF issue #444)
	public static final long FULL_SCREEN_SETTLE_DELAY = 100;

	/**
	 * The messages calibration shows the user on the calibrating camera's feed.
	 */
	public enum Message {
		/** Move the arena to the projector and make it full screen */
		FULL_SCREEN_REQUEST,
		/** Auto-calibration is looking for the pattern */
		AUTO_CALIBRATING,
		/** Drag the box over the projection */
		MANUAL_REQUEST
	}

	/**
	 * What calibration shows and asks of the user interface.
	 */
	public interface View {
		boolean isArenaFullScreen();

		void setArenaShotsVisible(boolean visible);

		/**
		 * @param calibrating
		 *            <tt>true</tt> while calibrating, e.g. to relabel the calibrate button
		 */
		void setCalibrating(boolean calibrating);

		/**
		 * The pattern is about to show: the arena hides its targets and its "needs calibration" label.
		 */
		void calibrationStarted();

		void saveArenaBackground();

		/**
		 * Shows the background saved by {@link #saveArenaBackground()} again, or none.
		 */
		void restoreArenaBackground();

		void showPattern();

		void showMessage(Message message);

		void hideMessage(Message message);

		/**
		 * Remembers which view the user was looking at and shows the calibrating camera's feed.
		 */
		void showCalibratingFeed();

		/**
		 * Shows the view remembered by {@link #showCalibratingFeed()}, if any.
		 */
		void restoreSelectedView();

		/**
		 * Shows a box the user drags and resizes over the projection on the calibrating camera's feed.
		 */
		void showManualBox();

		/**
		 * @return the box's bounds on the feed's canvas, if it is showing
		 */
		Optional<Rect> manualBox();

		void removeManualBox();

		/**
		 * The arena's projection on the calibrating camera feed's canvas changed; shots inside it now go
		 * to the arena.
		 */
		void projectionCalibrated(Rect canvasBounds);

		/**
		 * @return how the calibrating feed treats the projection: crop to it, only detect in it, or
		 *         neither
		 */
		CalibrationOption calibratedFeedBehavior();

		/**
		 * @return the arena window's size
		 */
		Size arenaResolution();

		/**
		 * Calibration ended.
		 */
		void calibrated(Optional<PerspectiveManager> perspectiveManager);

		void runOnUiThread(Runnable action);
	}

	/**
	 * The running exercise, as calibration pauses it.
	 */
	@FunctionalInterface
	public interface Exercises {
		/**
		 * Stops the running exercise if it runs on the projector arena.
		 *
		 * @return what starts it again, or empty if none was running
		 */
		Optional<Runnable> stopProjectorExercise();
	}

	@FunctionalInterface
	public interface Scheduler {
		/**
		 * Runs <tt>task</tt> on a background thread after <tt>delayMillis</tt>.
		 *
		 * @return the task, to cancel it; <tt>null</tt> if it can't be scheduled
		 */
		Future<?> schedule(Runnable task, long delayMillis);
	}

	private final CalibrationCamera camera;
	private final View view;
	private final Exercises exercises;
	private final Settings settings;
	private final Scheduler scheduler;
	private final Optional<Runnable> headlessTimeout;

	private final AtomicBoolean isCalibrating = new AtomicBoolean(false);
	private final AtomicBoolean isShowingPattern = new AtomicBoolean(false);
	private volatile boolean isFullScreen = false;
	private final Set<Message> shownMessages = EnumSet.noneOf(Message.class);
	private volatile Future<?> autoCalibrationTimer = null;
	private volatile Optional<Runnable> restartExercise = Optional.empty();
	private volatile Optional<Size> perspectivePaperDims = Optional.empty();
	// Set while an unattended calibration runs (startUnattended): what its timeout runs instead of the box
	private volatile Optional<Runnable> unattendedTimeout = Optional.empty();

	/**
	 * @param headlessTimeout
	 *            for headless use: runs when auto-calibration times out after 45 seconds, and calibration
	 *            then ends; without it, auto-calibration times out after 12 seconds to the manual box
	 */
	public CalibrationFlow(CalibrationCamera camera, View view, Exercises exercises, Settings settings,
			Scheduler scheduler, Optional<Runnable> headlessTimeout) {
		this.camera = camera;
		this.view = view;
		this.exercises = exercises;
		this.settings = settings;
		this.scheduler = scheduler;
		this.headlessTimeout = headlessTimeout;
	}

	public boolean isCalibrating() {
		return isCalibrating.get();
	}

	/**
	 * Starts calibrating.
	 */
	public void start() {
		// Projector exercises change what is on the arena, which gets in the way of calibration. The
		// exercise is stopped first, so it has put the arena back before the pattern's background is
		// saved over it.
		restartExercise = exercises.stopProjectorExercise();

		view.setArenaShotsVisible(false);

		isCalibrating.set(true);

		view.setCalibrating(true);

		// Calibrating, and so not detecting
		camera.setCalibrating(true);
		camera.setProjectionBounds(null);

		if (view.isArenaFullScreen()) {
			startAutoCalibration();
		} else {
			showMessage(Message.FULL_SCREEN_REQUEST);
		}
	}

	/**
	 * Starts calibrating with no one there to drag the manual box (the Compose app calibrating by itself as
	 * the arena opens). It runs as {@link #start()} does, except when auto-calibration times out, after
	 * {@link #AUTO_CALIBRATION_TIMEOUT_UNATTENDED}: then calibration is cancelled, as {@link #cancel()} does,
	 * and <tt>onTimeout</tt> runs, instead of the box being shown. Stopping or cancelling ends it; the next
	 * {@link #start()} is attended again.
	 *
	 * @param onTimeout
	 *            runs, after the cancel, if the pattern isn't found in time
	 */
	public void startUnattended(Runnable onTimeout) {
		if (isCalibrating()) return;

		unattendedTimeout = Optional.of(onTimeout);
		setFullScreen(view.isArenaFullScreen());
	}

	/**
	 * Ends calibrating: with the manual box's bounds if it is showing, otherwise with the bounds found
	 * so far (possibly none).
	 */
	public void stop() {
		isCalibrating.set(false);
		unattendedTimeout = Optional.empty();

		final Optional<Rect> manualBox = view.manualBox();
		if (manualBox.isPresent()) calibrated(manualBox.get(), Optional.empty(), true);

		camera.disableAutoCalibration();

		cancelAutoCalibrationTimer();

		view.setCalibrating(false);

		hideMessage(Message.FULL_SCREEN_REQUEST);
		hideMessage(Message.AUTO_CALIBRATING);
		hideMessage(Message.MANUAL_REQUEST);
		view.removeManualBox();

		view.restoreSelectedView();

		view.calibrated(Optional.ofNullable(perspectiveManager()));

		view.restoreArenaBackground();

		camera.setCalibrating(false);

		isShowingPattern.set(false);

		// Shot detection stays off briefly because the pattern going away can cause false shots. This
		// applies to every camera feed rather than just the arena's.
		camera.setDetecting(false);
		scheduler.schedule(() -> camera.setDetecting(true), DETECTION_RESTART_DELAY);

		view.setArenaShotsVisible(settings.showArenaShotMarkers());

		restartExercise.ifPresent(Runnable::run);
	}

	/**
	 * Ends calibrating abruptly, for when whatever was being calibrated (the arena) is gone: unlike
	 * {@link #stop()}, this never calibrates to the manual box, never restores the arena's background
	 * (there may be no arena left to show it on) and never restarts a stopped exercise. Shot detection
	 * still comes back after the usual delay, so it isn't left off everywhere else.
	 */
	public void cancel() {
		isCalibrating.set(false);
		unattendedTimeout = Optional.empty();

		cancelAutoCalibrationTimer();

		camera.disableAutoCalibration();

		hideMessage(Message.FULL_SCREEN_REQUEST);
		hideMessage(Message.AUTO_CALIBRATING);
		hideMessage(Message.MANUAL_REQUEST);
		view.removeManualBox();

		view.setCalibrating(false);

		view.restoreSelectedView();

		camera.setCalibrating(false);

		isShowingPattern.set(false);

		camera.setDetecting(false);
		scheduler.schedule(() -> camera.setDetecting(true), DETECTION_RESTART_DELAY);

		restartExercise = Optional.empty();
	}

	/**
	 * The arena's projection was found: by the camera (<tt>calibratedFromCanvas</tt> false, bounds on
	 * the camera feed) or with the manual box (true, bounds on the feed's canvas). Ends calibrating if it
	 * is still going.
	 */
	public void calibrated(Rect arenaBounds, Optional<Size> perspectivePaperDims, boolean calibratedFromCanvas) {
		view.removeManualBox();

		final Rect canvasBounds = calibratedFromCanvas ? arenaBounds
				: ArenaGeometry.cameraToCanvas(arenaBounds, feedSize(), displaySize());

		final Rect cameraBounds = ArenaGeometry.canvasToCamera(canvasBounds, feedSize(), displaySize());
		view.projectionCalibrated(canvasBounds);
		configureArenaCamera(view.calibratedFeedBehavior());
		camera.setProjectionBounds(cameraBounds);

		logger.debug("calibrate {} {} {}", canvasBounds, perspectivePaperDims, calibratedFromCanvas);

		this.perspectivePaperDims = perspectivePaperDims;

		if (isCalibrating()) stop();
	}

	/**
	 * Applies a calibration found in an earlier session (the Compose app's remembered one) without
	 * calibrating: the projection, the calibrated feed behavior and the perspective, as the end of a
	 * calibration would. Nothing is stopped or restarted and the arena's background is left alone.
	 *
	 * @param cameraBounds
	 *            the projection on the camera's feed
	 * @throws IllegalStateException
	 *             while calibrating
	 */
	public void applySaved(Rect cameraBounds, Optional<Size> perspectivePaperDims) {
		if (isCalibrating()) throw new IllegalStateException("A saved calibration can't be applied while calibrating");

		calibrated(cameraBounds, perspectivePaperDims, false);
		view.calibrated(Optional.ofNullable(perspectiveManager()));
	}

	/**
	 * Applies how the calibrating feed treats the projection.
	 */
	public void configureArenaCamera(CalibrationOption option) {
		camera.setCropFeedToProjection(CalibrationOption.CROP.equals(option));
		camera.setLimitDetectProjection(CalibrationOption.ONLY_IN_BOUNDS.equals(option));
	}

	/**
	 * The arena window is closing: its projection is gone.
	 */
	public void arenaClosing() {
		camera.setProjectionBounds(null);
	}

	/**
	 * The arena went full screen, or left it. Starts calibrating if it isn't already. Otherwise leaving
	 * full screen pauses auto-calibration and asks for full screen again, and going full screen resumes
	 * it after a moment.
	 */
	public void setFullScreen(boolean fullScreen) {
		isFullScreen = fullScreen;

		logger.trace("setFullScreenStatus - {} {}", fullScreen, isCalibrating);

		if (!isCalibrating.get()) {
			start();
		} else if (!fullScreen) {
			camera.disableAutoCalibration();

			view.removeManualBox();
			hideMessage(Message.AUTO_CALIBRATING);
			hideMessage(Message.MANUAL_REQUEST);

			showMessage(Message.FULL_SCREEN_REQUEST);
		} else {
			hideMessage(Message.FULL_SCREEN_REQUEST);
			// Delay slightly to prevent #444 bug
			scheduler.schedule(() -> view.runOnUiThread(() -> {
				if (isCalibrating.get()) startAutoCalibration();
			}), FULL_SCREEN_SETTLE_DELAY);
		}
	}

	private void startAutoCalibration() {
		logger.trace("enableAutoCalibration");

		view.calibrationStarted();
		// We may already be calibrating if the user decided to move the arena to another screen while
		// calibrating. If we save the background in that case we are saving the calibration pattern as
		// the background.
		if (!isShowingPattern.get()) view.saveArenaBackground();
		view.showPattern();
		isShowingPattern.set(true);

		camera.enableAutoCalibration(false);

		showMessage(Message.AUTO_CALIBRATING);

		launchAutoCalibrationTimer();
	}

	private void launchAutoCalibrationTimer() {
		cancelAutoCalibrationTimer();

		autoCalibrationTimer = scheduler.schedule(() -> view.runOnUiThread(() -> {
			if (isCalibrating.get() && isFullScreen) {
				final Optional<Runnable> unattended = unattendedTimeout;
				if (headlessTimeout.isPresent()) {
					headlessTimeout.get().run();
					stop();
				} else if (unattended.isPresent()) {
					cancel();
					unattended.get().run();
				} else {
					camera.disableAutoCalibration();
					startManualCalibration();
				}
			}
			// Keep waiting
			else if (!isFullScreen) launchAutoCalibrationTimer();
		}), timeout());
	}

	private long timeout() {
		if (headlessTimeout.isPresent()) return AUTO_CALIBRATION_TIMEOUT_HEADLESS;
		if (unattendedTimeout.isPresent()) return AUTO_CALIBRATION_TIMEOUT_UNATTENDED;
		return AUTO_CALIBRATION_TIMEOUT;
	}

	private void cancelAutoCalibrationTimer() {
		final Future<?> timer = autoCalibrationTimer;
		if (timer != null) timer.cancel(false);
	}

	private void startManualCalibration() {
		logger.trace("enableManualCalibration");

		hideMessage(Message.AUTO_CALIBRATING);

		view.showCalibratingFeed();

		showMessage(Message.MANUAL_REQUEST);

		view.showManualBox();
	}

	private void showMessage(Message message) {
		synchronized (shownMessages) {
			if (!shownMessages.add(message)) return;
		}

		view.showMessage(message);
	}

	private void hideMessage(Message message) {
		synchronized (shownMessages) {
			if (!shownMessages.remove(message)) return;
		}

		view.hideMessage(message);
	}

	private PerspectiveManager perspectiveManager() {
		final Optional<Rect> bounds = camera.getProjectionBounds();
		if (bounds.isEmpty()) return null;

		final Size feedDim = feedSize();
		final Optional<Size> paper = perspectivePaperDims;

		if (PerspectiveManager.isCameraSupported(camera.getName(), feedDim)) {
			if (paper.isPresent()) {
				return new PerspectiveManager(camera.getName(), bounds.get(), feedDim, paper.get(),
						view.arenaResolution());
			} else {
				return new PerspectiveManager(camera.getName(), bounds.get(), feedDim, view.arenaResolution());
			}
		} else if (paper.isPresent()) {
			return new PerspectiveManager(bounds.get(), feedDim, paper.get(), view.arenaResolution());
		}

		logger.debug("Too many perspective parameters are unknown to create a perspective manager.");
		return null;
	}

	private Size feedSize() {
		return new Size(camera.getFeedWidth(), camera.getFeedHeight());
	}

	private Size displaySize() {
		return new Size(settings.getDisplayWidth(), settings.getDisplayHeight());
	}
}
