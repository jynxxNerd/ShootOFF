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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.ToDoubleFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.geom.Rect;

/**
 * Measures where the calibration pattern is on the camera's feed: the median, edge by edge, of several
 * detections. One detection jitters by 5–10 px from the next (the pattern's outer edges are extrapolated
 * from its inner corners and rounded), so a single frame can't tell a moved projection from noise; the
 * median of five can. Both sides of the remembered calibration's check are measured this way: the bounds
 * saved after a calibration, and the pattern found when they are checked.
 * <p>
 * Like {@link CalibrationCheck}, it keeps no threads or timers: the caller offers frames and calls
 * {@link #tick()}, from any thread, and the clock decides the time limit. The first result is final. Each
 * detection, and the result, is logged at INFO.
 *
 * @param <F>
 *            a camera frame
 */
public final class PatternMeasurement<F> {
	private static final Logger logger = LoggerFactory.getLogger(PatternMeasurement.class);

	/** How many detections make a measurement */
	public static final int DEFAULT_DETECTIONS = 5;
	/** How many are enough once the time is up */
	public static final int MIN_DETECTIONS = 3;
	/**
	 * How long the pattern is looked for, in milliseconds: a detection takes about 400–500 ms on the owner's
	 * machine, so five take two to three seconds, with room for frames that miss it
	 */
	public static final long DEFAULT_TIME_LIMIT = 8000;

	public sealed interface Result permits Measured, NotSeen {}

	/**
	 * @param median
	 *            the median of the detections, edge by edge
	 * @param detections
	 *            how many it was taken from
	 */
	public record Measured(Rect median, int detections) implements Result {}

	/** Too few detections in time (projector off, camera pointed elsewhere, poor light) */
	public record NotSeen(int detections) implements Result {}

	private final String purpose;
	private final CalibrationCheck.Detector<F> detector;
	private final LongSupplier clock;
	private final Optional<Rect> reference;
	private final int detections;
	private final long timeLimit;
	private final long startedAt;

	private final List<Rect> found = new ArrayList<>();
	private Result result = null;

	/**
	 * Starts the time limit now, by <tt>clock</tt>.
	 *
	 * @param purpose
	 *            what the measurement is for, in its log lines
	 * @param reference
	 *            bounds each detection's distance from is logged (the saved ones), if any
	 */
	public PatternMeasurement(String purpose, CalibrationCheck.Detector<F> detector, LongSupplier clock,
			Optional<Rect> reference) {
		this(purpose, detector, clock, reference, DEFAULT_DETECTIONS, DEFAULT_TIME_LIMIT);
	}

	public PatternMeasurement(String purpose, CalibrationCheck.Detector<F> detector, LongSupplier clock,
			Optional<Rect> reference, int detections, long timeLimit) {
		this.purpose = purpose;
		this.detector = detector;
		this.clock = clock;
		this.reference = reference;
		this.detections = detections;
		this.timeLimit = timeLimit;
		startedAt = clock.getAsLong();
	}

	/**
	 * Looks for the pattern in <tt>frame</tt>, unless the measurement is over.
	 *
	 * @return the result, once there is one
	 */
	public synchronized Optional<Result> offer(F frame) {
		if (result != null || timedOut()) return tick();

		final long before = System.nanoTime();
		final Optional<Rect> detected = detector.detect(frame);
		final long took = (System.nanoTime() - before) / 1_000_000;

		if (detected.isEmpty()) {
			logger.info("{}: no pattern in this frame ({} ms)", purpose, took);
			return Optional.empty();
		}

		final Rect bounds = detected.get();
		found.add(bounds);
		if (reference.isPresent()) {
			logger.info("{}: detection {}/{} at {}, {} px from the saved bounds ({} ms)", purpose, found.size(),
					detections, describe(bounds), round(CalibrationCheck.distance(bounds, reference.get())), took);
		} else {
			logger.info("{}: detection {}/{} at {} ({} ms)", purpose, found.size(), detections, describe(bounds), took);
		}

		if (found.size() >= detections) finish(new Measured(median(found), found.size()));

		return Optional.ofNullable(result);
	}

	/**
	 * Ends the measurement if its time is up: with the median of what was found, if that is at least
	 * {@link #MIN_DETECTIONS}.
	 *
	 * @return the result, once there is one
	 */
	public synchronized Optional<Result> tick() {
		if (result == null && timedOut()) {
			finish(found.size() >= Math.min(MIN_DETECTIONS, detections) ? new Measured(median(found), found.size())
					: new NotSeen(found.size()));
		}
		return Optional.ofNullable(result);
	}

	public synchronized Optional<Result> result() {
		return Optional.ofNullable(result);
	}

	private void finish(Result result) {
		this.result = result;
		if (result instanceof Measured measured) {
			logger.info("{}: measured {} from {} detections", purpose, describe(measured.median()), measured.detections());
		} else {
			logger.info("{}: the pattern wasn't seen ({} detections in {} ms)", purpose, found.size(), timeLimit);
		}
	}

	private boolean timedOut() {
		return clock.getAsLong() - startedAt >= timeLimit;
	}

	/**
	 * @return the median of <tt>rects</tt>' left, top, right and bottom edges, each on its own (the mean of
	 *         the middle two for an even number)
	 */
	public static Rect median(List<Rect> rects) {
		final double left = median(rects, Rect::getMinX);
		final double top = median(rects, Rect::getMinY);
		final double right = median(rects, Rect::getMaxX);
		final double bottom = median(rects, Rect::getMaxY);
		return new Rect(left, top, right - left, bottom - top);
	}

	private static double median(List<Rect> rects, ToDoubleFunction<Rect> edge) {
		final double[] values = rects.stream().mapToDouble(edge).sorted().toArray();
		final int middle = values.length / 2;
		return values.length % 2 == 1 ? values[middle] : (values[middle - 1] + values[middle]) / 2;
	}

	/** A rectangle as the log shows it: left,top width×height */
	static String describe(Rect rect) {
		return round(rect.getMinX()) + "," + round(rect.getMinY()) + " " + round(rect.getWidth()) + "×"
				+ round(rect.getHeight());
	}

	private static String round(double value) {
		return value == Math.rint(value) ? Long.toString((long) value) : String.format("%.1f", value);
	}
}
