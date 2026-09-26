package com.shootoff.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class TestPoint {
	@Test
	void exposesCoordinates() {
		final Point point = new Point(1.5, -2.0);

		assertEquals(1.5, point.getX());
		assertEquals(-2.0, point.getY());
	}

	@Test
	void pointsWithTheSameCoordinatesAreEqual() {
		assertEquals(new Point(3, 4), new Point(3, 4));
		assertEquals(new Point(3, 4).hashCode(), new Point(3, 4).hashCode());
		assertNotEquals(new Point(3, 4), new Point(4, 3));
	}
}
