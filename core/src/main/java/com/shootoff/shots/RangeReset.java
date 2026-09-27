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

package com.shootoff.shots;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CamerasSupervisor;

/**
 * What Reset does, whatever user interface asks for it (the Reset button, or a target region's
 * <tt>reset</tt> command): reset every camera (its shots, its view's targets' animations) and the shot
 * processors, then the running exercise, then pause shot detection for a second so the reset itself
 * isn't seen as shots.
 */
public final class RangeReset {
	private static final Logger logger = LoggerFactory.getLogger(RangeReset.class);

	public static final long DETECTION_PAUSE_MILLIS = 1000;

	@FunctionalInterface
	public interface Scheduler {
		/**
		 * Runs <tt>task</tt> on a background thread after <tt>delayMillis</tt>.
		 */
		void schedule(Runnable task, long delayMillis);
	}

	private final CamerasSupervisor cameras;
	private final BooleanSupplier calibrating;
	private final Scheduler scheduler;

	/**
	 * @param calibrating
	 *            <tt>true</tt> while the arena is calibrating: detection then stays off
	 */
	public RangeReset(CamerasSupervisor cameras, BooleanSupplier calibrating, Scheduler scheduler) {
		this.cameras = cameras;
		this.calibrating = calibrating;
		this.scheduler = scheduler;
	}

	/**
	 * @param resetExercise
	 *            resets the running exercise, if there is one
	 */
	public void reset(Runnable resetExercise) {
		cameras.reset();

		resetExercise.run();

		disableShotDetection(DETECTION_PAUSE_MILLIS);
	}

	/**
	 * Turns shot detection off for <tt>millis</tt>, unless it is already off everywhere (e.g. an exercise
	 * paused it). Cameras that were already off stay off, and none comes back on while calibrating.
	 * Technically a shorter pause could be asked for while a longer one runs; pauses are short, so that
	 * isn't handled.
	 */
	public void disableShotDetection(long millis) {
		if (!cameras.areDetecting()) return;

		// Keep track of cameras that already had shot detection off so that
		// we can ensure they stay off when we re-enable shot detection
		final Set<CameraManager> alreadyOff = new HashSet<>();

		for (final CameraManager cm : cameras.getCameraManagers()) {
			if (!cm.isDetecting()) alreadyOff.add(cm);
		}

		cameras.setDetectingAll(false);

		final Runnable restartDetection = () -> {
			if (!calibrating.getAsBoolean()) {
				if (alreadyOff.isEmpty()) {
					cameras.setDetectingAll(true);
				} else {
					for (final CameraManager cm : cameras.getCameraManagers()) {
						if (!alreadyOff.contains(cm)) cm.setDetecting(true);
					}
				}
			} else {
				logger.info("disableShotDetectionTimer did not re-enable shot detection, isCalibrating is true");
			}
		};

		scheduler.schedule(restartDetection, millis);
	}
}
