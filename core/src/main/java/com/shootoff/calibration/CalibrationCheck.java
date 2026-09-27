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

import java.util.Optional;
import java.util.function.LongSupplier;

import com.shootoff.geom.Rect;

/**
 * Checks a remembered calibration against the projection the camera sees now. While the calibration
 * pattern shows on the arena, the caller offers camera frames; each is looked at with a
 * {@link Detector} and the pattern's bounds compared with the saved ones:
 * <ul>
 * <li>every edge within the tolerance of the saved one: the calibration is {@link Kept};</li>
 * <li>two frames in a row agree with each other but not with the saved bounds: the projection has
 * {@link Moved} (one frame alone could be caught mid-change);</li>
 * <li>no answer within the time limit, the camera gone, or the check cancelled: {@link NotVerified}.</li>
 * </ul>
 * The first outcome is final. The check keeps no threads or timers of its own: the caller offers frames
 * and calls {@link #tick()}, from any thread, and the clock decides the time limit.
 *
 * @param <F>
 *            a camera frame
 */
public final class CalibrationCheck<F> {
	/** How far, in camera pixels, an edge may be from the saved one */
	public static final double DEFAULT_TOLERANCE = 5.0;
	/** How long the check waits to see the pattern, in milliseconds */
	public static final long DEFAULT_TIME_LIMIT = 3000;

	/** Finds the calibration pattern in a camera frame */
	@FunctionalInterface
	public interface Detector<F> {
		Optional<Rect> detect(F frame);
	}

	public sealed interface Outcome permits Kept, Moved, NotVerified {}

	/** The saved calibration still fits */
	public record Kept(Rect detected) implements Outcome {}

	/**
	 * The projection is somewhere else now.
	 *
	 * @param distance
	 *            how far the furthest edge moved, in camera pixels
	 */
	public record Moved(Rect detected, double distance) implements Outcome {}

	public record NotVerified(Reason reason) implements Outcome {}

	public enum Reason {
		/** The pattern wasn't seen in time (projector off, camera pointed elsewhere, poor light) */
		PATTERN_NOT_SEEN,
		/** No camera to look with, or it went away */
		NO_CAMERA,
		/** The user, or something that replaced the check, stopped it */
		CANCELLED
	}

	private final Rect saved;
	private final Detector<F> detector;
	private final LongSupplier clock;
	private final double tolerance;
	private final long timeLimit;
	private final long startedAt;

	private Rect lastDetected = null;
	private Outcome outcome = null;

	public CalibrationCheck(Rect saved, Detector<F> detector, LongSupplier clock) {
		this(saved, detector, clock, DEFAULT_TOLERANCE, DEFAULT_TIME_LIMIT);
	}

	/**
	 * Starts the time limit now, by <tt>clock</tt>.
	 */
	public CalibrationCheck(Rect saved, Detector<F> detector, LongSupplier clock, double tolerance, long timeLimit) {
		this.saved = saved;
		this.detector = detector;
		this.clock = clock;
		this.tolerance = tolerance;
		this.timeLimit = timeLimit;
		startedAt = clock.getAsLong();
	}

	/**
	 * Looks for the pattern in <tt>frame</tt>, unless the check is over.
	 *
	 * @return the outcome, once there is one
	 */
	public synchronized Optional<Outcome> offer(F frame) {
		if (outcome != null || timedOut()) return tick();

		final Optional<Rect> detected = detector.detect(frame);
		if (detected.isEmpty()) {
			lastDetected = null;
			return Optional.empty();
		}

		final Rect found = detected.get();
		if (distance(found, saved) <= tolerance) {
			outcome = new Kept(found);
		} else if (lastDetected != null && distance(found, lastDetected) <= tolerance) {
			outcome = new Moved(found, distance(found, saved));
		} else {
			lastDetected = found;
		}

		return Optional.ofNullable(outcome);
	}

	/**
	 * Ends the check if its time is up.
	 *
	 * @return the outcome, once there is one
	 */
	public synchronized Optional<Outcome> tick() {
		if (outcome == null && timedOut()) outcome = new NotVerified(Reason.PATTERN_NOT_SEEN);
		return Optional.ofNullable(outcome);
	}

	/**
	 * Stops the check, unless it is already over.
	 *
	 * @return the outcome: <tt>reason</tt>, or the one it already had
	 */
	public synchronized Outcome stop(Reason reason) {
		if (outcome == null) outcome = new NotVerified(reason);
		return outcome;
	}

	public synchronized Optional<Outcome> outcome() {
		return Optional.ofNullable(outcome);
	}

	private boolean timedOut() {
		return clock.getAsLong() - startedAt >= timeLimit;
	}

	/**
	 * @return how far apart the furthest pair of matching edges are
	 */
	public static double distance(Rect a, Rect b) {
		return Math.max(Math.max(Math.abs(a.getMinX() - b.getMinX()), Math.abs(a.getMaxX() - b.getMaxX())),
				Math.max(Math.abs(a.getMinY() - b.getMinY()), Math.abs(a.getMaxY() - b.getMaxY())));
	}
}
