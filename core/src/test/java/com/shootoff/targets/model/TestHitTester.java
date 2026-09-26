package com.shootoff.targets.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.shootoff.geom.Point;

class TestHitTester {
	private static TargetDefinition definition(Region... regions) {
		return new TargetDefinition(Optional.empty(), Map.of(), List.of(regions));
	}

	private static RectangleRegion rect(int index, double x, double y, double width, double height,
			Map<String, String> tags) {
		return new RectangleRegion(index, x, y, width, height, "black", tags);
	}

	private static int regionAt(PlacedTarget target, double x, double y) {
		return HitTester.hit(target, x, y).map(hit -> hit.region().index()).orElse(-1);
	}

	// A 4x4 image whose left two columns are opaque
	private static AlphaMask leftHalfOpaque() {
		final BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 4; y++) {
			for (int x = 0; x < 2; x++) {
				image.setRGB(x, y, 0xff000000);
			}
		}
		return AlphaMask.of(image);
	}

	@Test
	void topmostTargetWins() {
		final TargetSet set = new TargetSet();
		final PlacedTarget bottom = set.add(definition(rect(0, 0, 0, 100, 100, Map.of())));
		final PlacedTarget top = set.add(definition(rect(0, 50, 50, 100, 100, Map.of())));

		assertEquals(top.getId(), HitTester.hit(set, 75, 75).get().targetId());
		assertEquals(bottom.getId(), HitTester.hit(set, 25, 25).get().targetId());
		assertFalse(HitTester.hit(set, 175, 25).isPresent());
	}

	@Test
	void topmostRegionWins() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(definition(rect(0, 0, 0, 100, 100, Map.of()),
				new EllipseRegion(1, 50, 50, 10, 10, "red", Map.of())));

		assertEquals(1, regionAt(target, 50, 50));
		assertEquals(0, regionAt(target, 5, 5));
	}

	@Test
	void rectangleEdgesAreHalfOpenLikeJavaFx() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(definition(rect(0, 10, 20, 30, 40, Map.of())));

		assertEquals(0, regionAt(target, 10, 20));
		assertEquals(0, regionAt(target, 39.99, 59.99));
		assertEquals(-1, regionAt(target, 40, 30));
		assertEquals(-1, regionAt(target, 20, 60));
	}

	@Test
	void regionHiddenByTagIsStillHit() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(definition(rect(0, 0, 0, 100, 100, Map.of()),
				rect(1, 40, 40, 20, 20, Map.of(Region.TAG_VISIBLE, "false"))));

		assertFalse(target.isRegionVisible(1));
		assertEquals(1, regionAt(target, 50, 50));
	}

	@Test
	void hiddenTargetIsNotHit() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(definition(rect(0, 0, 0, 100, 100, Map.of())));

		set.setVisible(target.getId(), false);

		assertFalse(HitTester.hit(set, 50, 50).isPresent());
		// The single-target test ignores visibility, like the v1 Target.isHit
		assertTrue(HitTester.hit(target, 50, 50).isPresent());
	}

	@Test
	void ignoreHitRegionsAreSkipped() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(definition(rect(0, 0, 0, 100, 100, Map.of()),
				rect(1, 40, 40, 20, 20, Map.of(Region.TAG_IGNORE_HIT, "true"))));

		assertEquals(0, regionAt(target, 50, 50));
	}

	@Test
	void imageUsesItsBoundsWithoutAMaskAndItsAlphaWithOne() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(definition(new ImageRegion(0, 0, 0, "unused.png", 4, 4, Map.of())));

		assertEquals(0, regionAt(target, 3.5, 1));

		set.setImageMask(target.getId(), 0, leftHalfOpaque());
		assertEquals(0, regionAt(target, 1.5, 1));
		assertEquals(-1, regionAt(target, 3.5, 1));
	}

	@Test
	void scaledImageUsesTheScaledAlpha() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(definition(new ImageRegion(0, 0, 0, "unused.png", 4, 4, Map.of())));
		set.setImageMask(target.getId(), 0, leftHalfOpaque());

		// Scaled 2x about its center (2, 2), the image covers -2..6: columns 0-3 are opaque
		set.scale(target.getId(), 2, 2);

		assertEquals(0, regionAt(target, -0.5, 1));
		assertEquals(-1, regionAt(target, 4.5, 1));
	}

	@Test
	void impactIsInTargetCoordinates() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(definition(rect(0, 0, 0, 100, 100, Map.of())));

		set.move(target.getId(), 10, 20);
		assertEquals(new Point(5, 10), HitTester.hit(target, 15, 30).get().impact());

		// Scaled 2x about (50, 50) at position (0, 0), the canvas point (50, 50) is still the center
		set.place(target.getId(), new Placement(0, 0, 2, 2, true));
		assertEquals(new Point(50, 50), HitTester.hit(target, 50, 50).get().impact());
	}
}
