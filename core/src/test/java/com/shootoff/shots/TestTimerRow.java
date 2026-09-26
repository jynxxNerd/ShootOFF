package com.shootoff.shots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;

class TestTimerRow {
	@Test
	void readsTheShotsTimeSplitAndLaser() {
		final Shot previous = new Shot(ShotColor.RED, 0, 0, 1000);

		final TimerRow first = TimerRow.of(previous, Optional.empty(), false, false);
		final TimerRow green = TimerRow.of(new Shot(ShotColor.GREEN, 5, 5, 2500), Optional.of(previous), true, false);
		final TimerRow infrared = TimerRow.of(new Shot(ShotColor.INFRARED, 5, 5, 3125), Optional.of(previous), false,
				true);

		assertEquals(String.format("%.2f", 1.0f), first.time());
		assertEquals("-", first.split());
		assertEquals("red", first.laser());
		assertEquals(String.format("%.2f", 2.5f), green.time());
		assertEquals(String.format("%.2f", 1.5f), green.split());
		assertEquals("green", green.laser());
		assertTrue(green.hadMalfunction());
		assertEquals("infrared", infrared.laser());
		assertEquals(String.format("%.2f", 2.125f), infrared.split());
		assertTrue(infrared.hadReload());
	}
}
