package com.shootoff.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class TestSize {
	@Test
	void exposesWidthAndHeight() {
		final Size size = new Size(1280, 720);

		assertEquals(1280, size.getWidth());
		assertEquals(720, size.getHeight());
	}

	@Test
	void sizesWithTheSameDimensionsAreEqual() {
		assertEquals(new Size(640, 480), new Size(640, 480));
		assertEquals(new Size(640, 480).hashCode(), new Size(640, 480).hashCode());
		assertNotEquals(new Size(640, 480), new Size(480, 640));
	}
}
