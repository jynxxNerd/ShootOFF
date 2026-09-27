package com.shootoff.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.shootoff.calibration.PatternMeasurement.Measured;
import com.shootoff.calibration.PatternMeasurement.NotSeen;
import com.shootoff.geom.Rect;

class TestPatternMeasurement {
	// The owner's C270 finding the pattern of one unmoved projection five times: each detection is a few
	// pixels off the others (the estimator extrapolates from the inner corners and rounds)
	private static final Map<String, Optional<Rect>> FRAMES = Map.of(
			"no pattern", Optional.empty(),
			"first", Optional.of(new Rect(95, 59, 450, 262)),
			"second", Optional.of(new Rect(93, 56, 442, 262)),
			"third", Optional.of(new Rect(94, 58, 446, 262)),
			"fourth", Optional.of(new Rect(96, 57, 448, 262)),
			"fifth", Optional.of(new Rect(93, 59, 444, 262)));

	private long now = 5_000;
	private final List<String> looked = new ArrayList<>();

	private final PatternMeasurement<String> measurement = new PatternMeasurement<>("Test", frame -> {
		looked.add(frame);
		return FRAMES.get(frame);
	}, () -> now, Optional.empty());

	@Test
	void theMeasurementIsTheMedianOfFiveDetectionsEdgeByEdge() {
		assertEquals(Optional.empty(), measurement.offer("first"));
		assertEquals(Optional.empty(), measurement.offer("second"));
		assertEquals(Optional.empty(), measurement.offer("third"));
		assertEquals(Optional.empty(), measurement.offer("fourth"));

		final Optional<PatternMeasurement.Result> result = measurement.offer("fifth");

		// Left 93,93,94,95,96; top 56,57,58,59,59; right 535,537,540,544,545; bottom 318,319,320,321,321
		assertEquals(Optional.of(new Measured(new Rect(94, 58, 446, 262), 5)), result);
	}

	@Test
	void framesWithoutThePatternDontCount() {
		for (final String frame : List.of("first", "no pattern", "second", "third", "no pattern", "fourth")) {
			assertEquals(Optional.empty(), measurement.offer(frame));
		}

		assertEquals(Optional.of(new Measured(new Rect(94, 58, 446, 262), 5)), measurement.offer("fifth"));
	}

	@Test
	void atTheTimeLimitThreeDetectionsAreEnoughAndFewerAreNotSeen() {
		measurement.offer("first");
		measurement.offer("second");
		measurement.offer("third");
		now += PatternMeasurement.DEFAULT_TIME_LIMIT;

		assertEquals(Optional.of(new Measured(new Rect(94, 58, 446, 262), 3)), measurement.tick());

		final PatternMeasurement<String> dim = new PatternMeasurement<>("Test", FRAMES::get, () -> now, Optional.empty());
		dim.offer("first");
		dim.offer("no pattern");
		dim.offer("second");
		now += PatternMeasurement.DEFAULT_TIME_LIMIT - 1;
		assertEquals(Optional.empty(), dim.tick());
		now += 1;
		assertEquals(Optional.of(new NotSeen(2)), dim.tick());
	}

	@Test
	void onceMeasuredFramesArentLookedAt() {
		for (final String frame : List.of("first", "second", "third", "fourth", "fifth")) measurement.offer(frame);

		measurement.offer("first");

		assertEquals(List.of("first", "second", "third", "fourth", "fifth"), looked);
	}

	@Test
	void anEvenNumberOfDetectionsTakesTheMiddleTwosMean() {
		assertEquals(new Rect(94.5, 58.5, 447.5, 263),
				PatternMeasurement.median(List.of(new Rect(95, 59, 450, 262), new Rect(93, 56, 442, 262),
						new Rect(94, 58, 446, 264), new Rect(96, 60, 448, 262))));
	}
}
