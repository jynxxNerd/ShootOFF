package com.shootoff.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

import javafx.geometry.BoundingBox;
import javafx.geometry.Dimension2D;

class TestFxGeometry {
	@Test
	void toRectKeepsPositionAndSize() {
		assertEquals(new Rect(1.5, 2.5, 30, 40), FxGeometry.toRect(new BoundingBox(1.5, 2.5, 30, 40)));
	}

	@Test
	void toSizeKeepsWidthAndHeight() {
		assertEquals(new Size(1280, 720), FxGeometry.toSize(new Dimension2D(1280, 720)));
	}

	// Projection bounds decide which shots are detected and which go to the arena, so the core
	// type must agree with JavaFX everywhere, including edges and degenerate boxes
	@Test
	void rectContainsMatchesJavaFxBoundingBox() {
		final double[][] boxes = { { 109, 104, 379, 297 }, { 0, 0, 64, 48 }, { 5, 5, 0, 0 }, { 10, 10, -1, 20 },
				{ 10.25, 3.75, 7.5, 0.5 } };

		for (final double[] b : boxes) {
			final BoundingBox fx = new BoundingBox(b[0], b[1], b[2], b[3]);
			final Rect rect = FxGeometry.toRect(fx);

			for (double x = b[0] - 2; x <= b[0] + Math.abs(b[2]) + 2; x += 0.25) {
				for (double y = b[1] - 2; y <= b[1] + Math.abs(b[3]) + 2; y += 0.25) {
					if (fx.contains(x, y) != rect.contains(x, y)) {
						throw new AssertionError("Rect and BoundingBox disagree at (" + x + ", " + y + ") for " + fx);
					}
				}
			}
		}
	}
}
