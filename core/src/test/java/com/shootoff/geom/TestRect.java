package com.shootoff.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TestRect {
	private final Rect rect = new Rect(10, 20, 30, 40);

	@Test
	void exposesJavaFxStyleBounds() {
		assertEquals(10, rect.getMinX());
		assertEquals(20, rect.getMinY());
		assertEquals(30, rect.getWidth());
		assertEquals(40, rect.getHeight());
		assertEquals(40, rect.getMaxX());
		assertEquals(60, rect.getMaxY());
	}

	@Test
	void containsInteriorPoint() {
		assertTrue(rect.contains(25, 40));
	}

	@Test
	void edgesAndCornersAreInside() {
		assertTrue(rect.contains(10, 20));
		assertTrue(rect.contains(40, 60));
		assertTrue(rect.contains(10, 60));
		assertTrue(rect.contains(40, 20));
	}

	@Test
	void pointsJustOutsideAreOutside() {
		assertFalse(rect.contains(9.999, 30));
		assertFalse(rect.contains(40.001, 30));
		assertFalse(rect.contains(20, 19.999));
		assertFalse(rect.contains(20, 60.001));
	}

	@Test
	void negativeSizeIsEmptyAndContainsNothing() {
		final Rect empty = new Rect(0, 0, -1, 5);

		assertTrue(empty.isEmpty());
		assertFalse(empty.contains(0, 0));
	}

	@Test
	void zeroSizeIsNotEmptyAndContainsItsCorner() {
		final Rect point = new Rect(5, 5, 0, 0);

		assertFalse(point.isEmpty());
		assertTrue(point.contains(5, 5));
	}

	@Test
	void rectsWithTheSameBoundsAreEqual() {
		assertEquals(new Rect(1, 2, 3, 4), new Rect(1, 2, 3, 4));
		assertEquals(new Rect(1, 2, 3, 4).hashCode(), new Rect(1, 2, 3, 4).hashCode());
		assertNotEquals(new Rect(1, 2, 3, 4), new Rect(1, 2, 4, 3));
	}
}
