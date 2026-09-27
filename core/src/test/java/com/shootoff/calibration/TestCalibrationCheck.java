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
			"pattern moved", Optional.of(new Rect(114, 80, 400, 300)),
			"pattern moved again", Optional.of(new Rect(115, 81, 400, 300)),
			"pattern mid-change", Optional.of(new Rect(160, 120, 300, 200)));

	private long now = 5_000;
	private final List<String> looked = new ArrayList<>();

	private final CalibrationCheck<String> check = new CalibrationCheck<>(SAVED, frame -> {
		looked.add(frame);
		return FRAMES.get(frame);
	}, () -> now);

	@Test
	void theSavedBoundsFoundWithinFivePixelsKeepTheCalibration() {
		assertEquals(Optional.empty(), check.offer("no pattern"));

		assertEquals(Optional.of(new Kept(new Rect(104, 77, 398, 302))), check.offer("pattern in place"));
	}

	@Test
	void twoFramesAgreeingAwayFromTheSavedBoundsReportHowFarItMoved() {
		assertEquals(Optional.empty(), check.offer("pattern moved"));

		final Optional<Outcome> outcome = check.offer("pattern moved again");

		assertEquals(Optional.of(new Moved(new Rect(115, 81, 400, 300), 15.0)), outcome);
	}

	@Test
	void oneFrameCaughtMidChangeIsNotReportedAsAMove() {
		assertEquals(Optional.empty(), check.offer("pattern mid-change"));
		assertEquals(Optional.empty(), check.offer("pattern moved"));
		assertEquals(Optional.empty(), check.offer("no pattern"));

		assertEquals(Optional.of(new Kept(new Rect(104, 77, 398, 302))), check.offer("pattern in place"));
	}

	@Test
	void withNoPatternForThreeSecondsItIsNotVerifiedAndStopsLooking() {
		now += 2_999;
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
		kept.offer("pattern in place");
		assertTrue(kept.stop(Reason.CANCELLED) instanceof Kept);
	}

	@Test
	void theDistanceIsTheFurthestEdge() {
		assertEquals(0.0, CalibrationCheck.distance(SAVED, SAVED));
		// Wider by 14 on the right, 3 higher at the top
		assertEquals(14.0, CalibrationCheck.distance(SAVED, new Rect(100, 77, 414, 303)));
	}
}
