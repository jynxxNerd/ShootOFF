package com.shootoff.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import com.shootoff.geom.ProjectorScreens.Choice;
import com.shootoff.geom.ProjectorScreens.Reason;

class TestProjectorScreens {
	// The owner's desk: DP-2 on the left, DP-1 in the middle, the projector on HDMI-1
	private static final List<Rect> OWNER = List.of(new Rect(0, 0, 1920, 1080), new Rect(1920, 0, 2560, 1440),
			new Rect(4480, 0, 1280, 720));
	private static final List<Rect> TWO = List.of(new Rect(0, 0, 1920, 1080), new Rect(1920, 0, 1280, 720));

	@Test
	void withMoreThanTwoScreensTheSmallestIsTheProjector() {
		assertEquals(Optional.of(new Choice(2, Reason.SMALLEST)),
				ProjectorScreens.choose(OWNER, 1, OptionalInt.of(1), Optional.empty()));
	}

	@Test
	void theFirstOfEquallySmallScreensWins() {
		final List<Rect> screens = List.of(new Rect(0, 0, 1920, 1080), new Rect(1920, 0, 800, 600),
				new Rect(2720, 0, 600, 800));

		assertEquals(Optional.of(new Choice(1, Reason.SMALLEST)),
				ProjectorScreens.choose(screens, 0, OptionalInt.empty(), Optional.empty()));
	}

	@Test
	void withTwoScreensItIsTheOneShootoffIsNotOn() {
		assertEquals(Optional.of(new Choice(1, Reason.OTHER_OF_TWO)),
				ProjectorScreens.choose(TWO, 0, OptionalInt.of(0), Optional.empty()));
		assertEquals(Optional.of(new Choice(0, Reason.OTHER_OF_TWO)),
				ProjectorScreens.choose(TWO, 0, OptionalInt.of(1), Optional.empty()));
	}

	@Test
	void withTwoScreensAndNoKnownMainWindowScreenThereIsNoChoice() {
		assertEquals(Optional.empty(), ProjectorScreens.choose(TWO, 0, OptionalInt.empty(), Optional.empty()));
	}

	@Test
	void oneScreenIsNeverTheProjector() {
		assertEquals(Optional.empty(),
				ProjectorScreens.choose(List.of(OWNER.get(0)), 0, OptionalInt.of(0), Optional.empty()));
	}

	@Test
	void aSavedPositionOnAnotherScreenWins() {
		assertEquals(Optional.of(new Choice(0, Reason.SAVED_POSITION)),
				ProjectorScreens.choose(OWNER, 1, OptionalInt.of(1), Optional.of(new Point(100, 100))));
	}

	@Test
	void aSavedPositionOnTheArenasOwnScreenOrOffEveryScreenIsIgnored() {
		assertEquals(Optional.of(new Choice(2, Reason.SMALLEST)),
				ProjectorScreens.choose(OWNER, 1, OptionalInt.of(1), Optional.of(new Point(2000, 100))));
		assertEquals(Optional.of(new Choice(2, Reason.SMALLEST)),
				ProjectorScreens.choose(OWNER, 1, OptionalInt.of(1), Optional.of(new Point(9000, 100))));
	}

	@Test
	void aSavedPositionOnAScreensLeftEdgeIsOnThatScreen() {
		assertEquals(Optional.of(new Choice(2, Reason.SAVED_POSITION)),
				ProjectorScreens.choose(OWNER, 0, OptionalInt.of(0), Optional.of(new Point(4480, 0))));
		// One pixel left of it is the middle screen's last column
		assertEquals(Optional.of(new Choice(1, Reason.SAVED_POSITION)),
				ProjectorScreens.choose(OWNER, 0, OptionalInt.of(0), Optional.of(new Point(4479, 0))));
	}
}
