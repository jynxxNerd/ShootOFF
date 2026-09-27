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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.geom.Rect;

/**
 * Checks a remembered calibration against the projection the camera sees now. While the calibration
 * pattern shows on the arena, the caller offers camera frames; they are measured with a
 * {@link PatternMeasurement} (the median of five detections, as the saved bounds were) and the measurement
 * compared with the saved bounds, against a tolerance that scales with the pattern ({@link #tolerance}):
 * <ul>
 * <li>within the tolerance: the calibration is {@link Kept}, as saved;</li>
 * <li>beyond it, but not twice it: measurement noise, or a nudge too small to matter; {@link Kept}, at the
 * fresh measurement;</li>
 * <li>more than twice the tolerance: the projection has {@link Moved};</li>
 * <li>too few detections within the time limit, the camera gone, or the check cancelled:
 * {@link NotVerified}.</li>
 * </ul>
 * The first outcome is final, and logged at INFO. The check keeps no threads or timers of its own: the
 * caller offers frames and calls {@link #tick()}, from any thread, and the clock decides the time limit.
 *
 * @param <F>
 *            a camera frame
 */
public final class CalibrationCheck<F> {
	private static final Logger logger = LoggerFactory.getLogger(CalibrationCheck.class);

	/** The smallest tolerance, in camera pixels */
	public static final double MIN_TOLERANCE = 8.0;
	/** The tolerance's share of the saved pattern's larger side */
	public static final double TOLERANCE_FRACTION = 0.02;
	/** How long the check waits to measure the pattern, in milliseconds */
	public static final long DEFAULT_TIME_LIMIT = PatternMeasurement.DEFAULT_TIME_LIMIT;

	/** Finds the calibration pattern in a camera frame */
	@FunctionalInterface
	public interface Detector<F> {
		Optional<Rect> detect(F frame);
	}

	public sealed interface Outcome permits Kept, Moved, NotVerified {}

	/**
	 * The saved calibration still fits.
	 *
	 * @param detected
	 *            the pattern as measured now
	 * @param distance
	 *            how far its furthest edge is from the saved one, in camera pixels
	 * @param bounds
	 *            the calibration to use: the saved bounds when within the tolerance, else the fresh measurement
	 */
	public record Kept(Rect detected, double distance, Rect bounds) implements Outcome {}

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
	private final PatternMeasurement<F> measurement;

	private Outcome outcome = null;

	public CalibrationCheck(Rect saved, Detector<F> detector, LongSupplier clock) {
		this(saved, detector, clock, PatternMeasurement.DEFAULT_DETECTIONS, DEFAULT_TIME_LIMIT);
	}

	/**
	 * Starts the time limit now, by <tt>clock</tt>.
	 */
	public CalibrationCheck(Rect saved, Detector<F> detector, LongSupplier clock, int detections, long timeLimit) {
		this.saved = saved;
		measurement = new PatternMeasurement<>("Calibration check", detector, clock, Optional.of(saved), detections,
				timeLimit);
	}

	/**
	 * Looks for the pattern in <tt>frame</tt>, unless the check is over.
	 *
	 * @return the outcome, once there is one
	 */
	public synchronized Optional<Outcome> offer(F frame) {
		if (outcome != null) return Optional.of(outcome);
		measurement.offer(frame).ifPresent(this::judge);
		return Optional.ofNullable(outcome);
	}

	/**
	 * Ends the check if its time is up.
	 *
	 * @return the outcome, once there is one
	 */
	public synchronized Optional<Outcome> tick() {
		if (outcome == null) measurement.tick().ifPresent(this::judge);
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

	private void judge(PatternMeasurement.Result result) {
		if (result instanceof PatternMeasurement.Measured measured) {
			outcome = judge(saved, measured.median());
		} else {
			outcome = new NotVerified(Reason.PATTERN_NOT_SEEN);
			logger.info("Calibration check: not verified, the pattern wasn't seen");
		}
	}

	/**
	 * @return what <tt>measured</tt> says about <tt>saved</tt>: kept as saved within the tolerance, kept at
	 *         <tt>measured</tt> up to twice it, moved beyond that
	 */
	public static Outcome judge(Rect saved, Rect measured) {
		final double distance = distance(measured, saved);
		final double tolerance = tolerance(saved);
		final Outcome outcome;
		if (distance <= tolerance) {
			outcome = new Kept(measured, distance, saved);
		} else if (distance <= 2 * tolerance) {
			outcome = new Kept(measured, distance, measured);
		} else {
			outcome = new Moved(measured, distance);
		}
		logger.info("Calibration check: measured {}, {} px from the saved {} (tolerance {} px): {}",
				PatternMeasurement.describe(measured), String.format("%.1f", distance),
				PatternMeasurement.describe(saved), String.format("%.1f", tolerance),
				outcome instanceof Moved ? "moved" : distance <= tolerance ? "kept" : "kept at the new measurement");
		return outcome;
	}

	/**
	 * @return how far an edge may be from the saved one, in camera pixels: the larger of
	 *         {@link #MIN_TOLERANCE} and {@link #TOLERANCE_FRACTION} of the saved pattern's larger side
	 */
	public static double tolerance(Rect saved) {
		return Math.max(MIN_TOLERANCE, TOLERANCE_FRACTION * Math.max(saved.getWidth(), saved.getHeight()));
	}

	/**
	 * @return how far apart the furthest pair of matching edges are
	 */
	public static double distance(Rect a, Rect b) {
		return Math.max(Math.max(Math.abs(a.getMinX() - b.getMinX()), Math.abs(a.getMaxX() - b.getMaxX())),
				Math.max(Math.abs(a.getMinY() - b.getMinY()), Math.abs(a.getMaxY() - b.getMaxY())));
	}
}
