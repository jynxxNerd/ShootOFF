package com.shootoff.camera.shot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestScaledShot {
	@Test
	void scalesTheBoundsAdjustedPositionToTheDisplay() {
		final ScaledShot shot = new ScaledShot(ShotColor.GREEN, 100, 100, 50, 3);
		shot.adjustBounds(10, 10);

		shot.setDisplayVals(100, 100, 200, 200);

		assertEquals(55, shot.getX(), 0.001);
		assertEquals(55, shot.getY(), 0.001);
		assertEquals(55, shot.getDisplayX(), 0.001);
		assertEquals(110, shot.getBoundsX(), 0.001);
		assertEquals(100, shot.getOrigX(), 0.001);
		assertEquals(50, shot.getTimestamp());
		assertEquals(3, shot.getFrame());
		assertEquals(ShotColor.GREEN, shot.getColor());
	}

	@Test
	void unscaledShotReportsItsBoundsPosition() {
		final ScaledShot shot = new ScaledShot(ShotColor.RED, 5, 6, 0);

		assertEquals(5, shot.getX(), 0.001);
		assertEquals(6, shot.getDisplayY(), 0.001);
	}

	@Test
	void copyKeepsBoundsAndDisplayValues() {
		final ScaledShot shot = new ScaledShot(ShotColor.RED, 100, 100, 0);
		shot.adjustBounds(10, 10);
		shot.setDisplayVals(100, 100, 200, 200);

		final ScaledShot copy = new ScaledShot(shot);

		assertEquals(55, copy.getX(), 0.001);
		assertEquals(110, copy.getBoundsX(), 0.001);
	}
}
