package com.shootoff.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.shootoff.calibration.CalibrationCheck.Kept;
import com.shootoff.calibration.CalibrationCheck.Moved;
import com.shootoff.calibration.CalibrationCheck.NotVerified;
import com.shootoff.calibration.CalibrationCheck.Outcome;
import com.shootoff.calibration.CalibrationCheck.Reason;
import com.shootoff.geom.Rect;

class TestCalibrationCheck {
	private static final Rect SAVED = new Rect(100, 80, 400, 300);

	// Fake frames: each names what the detector finds in it
	private static final Map<String, Optional<Rect>> FRAMES = Map.of(
			"no pattern", Optional.empty(),
			"pattern in place", Optional.of(new Rect(104, 77, 398, 302)),
			"pattern moved", Optional.of(new Rect(120, 80, 400, 300)),
			"pattern mid-change", Optional.of(new Rect(160, 120, 300, 200)));

	private long now = 5_000;
	private final List<String> looked = new ArrayList<>();

	private final CalibrationCheck<String> check = new CalibrationCheck<>(SAVED, frame -> {
		looked.add(frame);
		return FRAMES.get(frame);
	}, () -> now);

	// Offers <tt>frame</tt> until there is an outcome, or five times
	private Optional<Outcome> offerFiveTimes(CalibrationCheck<String> check, String frame) {
		Optional<Outcome> outcome = Optional.empty();
		for (int i = 0; i < PatternMeasurement.DEFAULT_DETECTIONS && outcome.isEmpty(); i++) outcome = check.offer(frame);
		return outcome;
	}

	@Test
	void withinTheToleranceTheSavedCalibrationIsKeptAsSaved() {
		assertEquals(Optional.empty(), check.offer("no pattern"));

		final Optional<Outcome> outcome = offerFiveTimes(check, "pattern in place");

		// 4 px from the saved bounds, inside the 8 px tolerance
		assertEquals(Optional.of(new Kept(new Rect(104, 77, 398, 302), 4.0, SAVED)), outcome);
	}

	@Test
	void theOwnersTwoCalibrationsOfAnUnmovedSetupAreNotAMove() {
		final Rect saved = new Rect(95, 59, 450, 262);
		final Rect measured = new Rect(93, 56, 442, 262);
		final CalibrationCheck<String> owners = new CalibrationCheck<>(saved, frame -> Optional.of(measured), () -> now);

		// 10 px apart (the right edge), past the 9 px tolerance (2% of 450) but not twice it: kept, at the
		// fresh measurement
		assertEquals(Optional.of(new Kept(measured, 10.0, measured)), offerFiveTimes(owners, "any"));
	}

	@Test
	void clearlyBeyondTheToleranceItMoved() {
		final Optional<Outcome> outcome = offerFiveTimes(check, "pattern moved");

		assertEquals(Optional.of(new Moved(new Rect(120, 80, 400, 300), 20.0)), outcome);
	}

	@Test
	void aFrameCaughtMidChangeDoesntSwayTheMeasurement() {
		assertEquals(Optional.empty(), check.offer("pattern mid-change"));

		assertEquals(Optional.of(new Kept(new Rect(104, 77, 398, 302), 4.0, SAVED)),
				offerFiveTimes(check, "pattern in place"));
	}

	@Test
	void withNoPatternInTimeItIsNotVerifiedAndStopsLooking() {
		now += CalibrationCheck.DEFAULT_TIME_LIMIT - 1;
		assertEquals(Optional.empty(), check.tick());
		assertEquals(Optional.empty(), check.offer("no pattern"));

		now += 1;
		assertEquals(Optional.of(new NotVerified(Reason.PATTERN_NOT_SEEN)), check.tick());

		// A frame arriving afterwards isn't even looked at
		assertEquals(Optional.of(new NotVerified(Reason.PATTERN_NOT_SEEN)), check.offer("pattern in place"));
		assertEquals(List.of("no pattern"), looked);
	}

	@Test
	void stoppingGivesItsReasonUnlessThereIsAlreadyAnOutcome() {
		assertEquals(new NotVerified(Reason.NO_CAMERA), check.stop(Reason.NO_CAMERA));
		assertEquals(new NotVerified(Reason.NO_CAMERA), check.stop(Reason.CANCELLED));

		final CalibrationCheck<String> kept = new CalibrationCheck<>(SAVED, FRAMES::get, () -> now);
		offerFiveTimes(kept, "pattern in place");
		assertTrue(kept.stop(Reason.CANCELLED) instanceof Kept);
	}

	@Test
	void theDistanceIsTheFurthestEdge() {
		assertEquals(0.0, CalibrationCheck.distance(SAVED, SAVED));
		// Wider by 14 on the right, 3 higher at the top
		assertEquals(14.0, CalibrationCheck.distance(SAVED, new Rect(100, 77, 414, 303)));
	}

	@Test
	void theToleranceScalesWithThePattern() {
		assertEquals(8.0, CalibrationCheck.tolerance(SAVED));
		assertEquals(9.0, CalibrationCheck.tolerance(new Rect(95, 59, 450, 262)));
		assertEquals(20.0, CalibrationCheck.tolerance(new Rect(0, 0, 1000, 600)));
		assertEquals(8.0, CalibrationCheck.tolerance(new Rect(0, 0, 100, 80)));
	}
}
