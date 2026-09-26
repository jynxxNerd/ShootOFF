package com.shootoff.targets.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

class TestTargetSet {
	private static TargetDefinition rectangles(RectangleRegion... regions) {
		return new TargetDefinition(Optional.empty(), Map.of(), List.of(regions));
	}

	private static RectangleRegion rect(int index, double x, double y, double width, double height,
			Map<String, String> tags) {
		return new RectangleRegion(index, x, y, width, height, "black", tags);
	}

	@Test
	void addAppendsOnTopWithUniqueIds() {
		final TargetSet set = new TargetSet();
		final PlacedTarget a = set.add(rectangles(rect(0, 0, 0, 10, 10, Map.of())));
		final PlacedTarget b = set.add(rectangles(rect(0, 0, 0, 10, 10, Map.of())));
		final PlacedTarget c = set.add(rectangles(rect(0, 0, 0, 10, 10, Map.of())), new Placement(5, 6, 2, 3, false));

		assertEquals(List.of(a, b, c), set.getTargets());
		assertNotEquals(a.getId(), b.getId());
		assertEquals(2, set.indexOf(c.getId()));
		assertEquals(new Placement(5, 6, 2, 3, false), c.getPlacement());
		assertEquals(Placement.ORIGIN, a.getPlacement());

		set.remove(b.getId());
		assertEquals(1, set.indexOf(c.getId()));
		assertEquals(-1, set.indexOf(b.getId()));
		assertEquals(Optional.empty(), set.get(b.getId()));
		assertEquals(2, set.size());
	}

	@Test
	void listenersHearAddsChangesAndRemoves() {
		final TargetSet set = new TargetSet();
		final List<String> heard = new ArrayList<>();
		set.addListener(new TargetSetListener() {
			@Override
			public void targetAdded(PlacedTarget target) {
				heard.add("added");
			}

			@Override
			public void targetChanged(PlacedTarget target) {
				heard.add("changed " + target.getPosition() + " " + target.isVisible());
			}

			@Override
			public void targetRemoved(PlacedTarget target) {
				heard.add("removed");
			}
		});

		final PlacedTarget target = set.add(rectangles(rect(0, 0, 0, 10, 10, Map.of())));
		set.move(target.getId(), 3, 4);
		set.setVisible(target.getId(), false);
		set.remove(target.getId());

		assertEquals(List.of("added", "changed " + new Point(3, 4) + " true", "changed " + new Point(3, 4) + " false",
				"removed"), heard);
	}

	@Test
	void boundsScaleAboutTheCenterLikeJavaFx() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(rectangles(rect(0, 0, 0, 100, 50, Map.of())));

		set.place(target.getId(), new Placement(10, 20, 2, 3, true));

		// Pivot (50, 25): x = 10 + 50 - 50 * 2 = -40, y = 20 + 25 - 25 * 3 = -30
		assertEquals(new Rect(-40, -30, 200, 150), target.getBounds());
		assertEquals(new Size(200, 150), target.getSize());
		assertEquals(new Point(10, 20), target.getPosition());
		assertEquals(new Rect(0, 0, 100, 50), target.getLocalBounds());
		assertEquals(new Point(50, 25), target.getPivot());
	}

	@Test
	void resizeFollowsSetDimensions() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(rectangles(rect(0, 0, 0, 100, 50, Map.of())));

		// Within 0.001 of the current width: unchanged; the height doubles
		set.resize(target.getId(), 100.0005, 100);
		assertEquals(1, target.getScaleX());
		assertEquals(2, target.getScaleY(), 1e-12);

		set.resize(target.getId(), 250, 100);
		assertEquals(2.5, target.getScaleX(), 1e-12);
		assertEquals(250, target.getSize().getWidth(), 1e-9);
	}

	@Test
	void updatesToRemovedTargetsAreIgnored() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(rectangles(rect(0, 0, 0, 10, 10, Map.of())));
		final List<String> heard = new ArrayList<>();
		set.remove(target.getId());
		set.addListener(new TargetSetListener() {
			@Override
			public void targetChanged(PlacedTarget changed) {
				heard.add("changed");
			}
		});

		set.move(target.getId(), 1, 2);
		set.resize(target.getId(), 30, 40);
		set.place(target.getId(), Placement.ORIGIN);
		set.remove(target.getId());

		assertTrue(heard.isEmpty());
		assertEquals(Placement.ORIGIN, target.getPlacement());
	}

	@Test
	void hiddenRegionsDontCountTowardTheBounds() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(rectangles(rect(0, 0, 0, 10, 10, Map.of()),
				rect(1, 0, 0, 100, 100, Map.of(Region.TAG_VISIBLE, "false"))));

		assertFalse(target.isRegionVisible(1));
		assertEquals(new Rect(0, 0, 10, 10), target.getLocalBounds());

		set.setRegionVisible(target.getId(), 1, true);
		assertEquals(new Rect(0, 0, 100, 100), target.getLocalBounds());
	}
}
