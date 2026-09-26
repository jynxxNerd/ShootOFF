package com.shootoff.gui.targets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.io.File;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;
import com.shootoff.gui.JavaFXThreadingRule;
import com.shootoff.gui.MockCanvasManager;
import com.shootoff.targets.io.TargetIO;
import com.shootoff.targets.model.Placement;
import com.shootoff.targets.model.TargetSet;

import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

public class TestTargetViewPlacement {
	@Rule public JavaFXThreadingRule javafxRule = new JavaFXThreadingRule();

	private MockCanvasManager canvas;
	private TargetView ipsc;

	@Before
	public void setUp() throws ConfigurationException {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		canvas = new MockCanvasManager(new Configuration(new String[0]));
		ipsc = new TargetView(TargetIO.loadTarget(new File("targets/IPSC.target"), false).get(), canvas, true);
	}

	// A mouse event at a canvas point; JavaFX converts it to the target group's coordinates
	private void fireMouse(EventType<MouseEvent> type, double x, double y) {
		Event.fireEvent(ipsc.getTargetGroup(), new MouseEvent(type, x, y, x, y, MouseButton.PRIMARY, 1, false, false,
				false, false, true, false, false, false, false, false, null));
	}

	@Test
	public void placementLivesInTheTargetSet() {
		canvas.addTarget(ipsc);
		final TargetSet set = canvas.getTargetSet();
		assertSame(set, ipsc.getTargetSet());
		assertEquals(0, set.indexOf(ipsc.getPlacedTarget().getId()));

		ipsc.setPosition(12, 34);
		assertEquals(new Point(12, 34), ipsc.getPlacedTarget().getPosition());
		assertEquals(12, ipsc.getTargetGroup().getLayoutX(), 0);

		set.move(ipsc.getPlacedTarget().getId(), 56, 78);
		assertEquals(56, ipsc.getTargetGroup().getLayoutX(), 0);
		assertEquals(78, ipsc.getPosition().getY(), 0);

		ipsc.setVisible(false);
		assertFalse(ipsc.getPlacedTarget().isVisible());
		assertFalse(ipsc.getTargetGroup().isVisible());
	}

	@Test
	public void setDimensionsScalesAboutTheCenter() {
		final Rect before = ipsc.getPlacedTarget().getBounds();

		ipsc.setDimensions(before.getWidth() * 2, before.getHeight());

		final Rect after = ipsc.getPlacedTarget().getBounds();
		assertEquals(before.getWidth() * 2, after.getWidth(), 0.001);
		assertEquals(before.getHeight(), after.getHeight(), 0.001);
		assertEquals(before.getMinX() + before.getWidth() / 2, after.getMinX() + after.getWidth() / 2, 0.001);

		// The JavaFX nodes are drawn where the model says the target is
		final Bounds drawn = ipsc.getTargetGroup().getBoundsInParent();
		assertEquals(after.getMinX(), drawn.getMinX(), 0.01);
		assertEquals(after.getWidth(), drawn.getWidth(), 0.01);
	}

	@Test
	public void joiningAndLeavingACanvasKeepsThePlacement() {
		ipsc.setPosition(10, 20);
		ipsc.setDimensions(100, 150);

		canvas.addTarget(ipsc);
		assertEquals(10, ipsc.getPosition().getX(), 0);
		assertEquals(100, ipsc.getDimension().getWidth(), 0.001);

		canvas.removeTarget(ipsc);
		assertNotSame(canvas.getTargetSet(), ipsc.getTargetSet());
		assertEquals(-1, canvas.getTargetSet().indexOf(ipsc.getPlacedTarget().getId()));
		assertEquals(20, ipsc.getPosition().getY(), 0);
		assertEquals(150, ipsc.getDimension().getHeight(), 0.001);
	}

	@Test
	public void draggingMovesTheTargetWithTheMouse() {
		canvas.addTarget(ipsc);
		ipsc.setDimensions(ipsc.getDimension().getWidth() * 2, ipsc.getDimension().getHeight() * 2);
		final Placement start = ipsc.getPlacement();
		final Rect bounds = ipsc.getPlacedTarget().getBounds();
		final double x = bounds.getMinX() + bounds.getWidth() / 2;
		final double y = bounds.getMinY() + bounds.getHeight() / 2;

		fireMouse(MouseEvent.MOUSE_MOVED, x, y);
		fireMouse(MouseEvent.MOUSE_PRESSED, x, y);
		fireMouse(MouseEvent.MOUSE_DRAGGED, x + 10, y + 5);

		assertEquals(start.x() + 10, ipsc.getPosition().getX(), 0.001);
		assertEquals(start.y() + 5, ipsc.getPosition().getY(), 0.001);
	}

	@Test
	public void draggingTheRightEdgeWidensTheTargetAndKeepsItsLeftEdge() {
		canvas.addTarget(ipsc);
		final Rect before = ipsc.getPlacedTarget().getBounds();
		final double y = before.getMinY() + before.getHeight() / 2;
		final double edge = before.getMaxX() - 1; // inside the 5-pixel resize margin

		fireMouse(MouseEvent.MOUSE_MOVED, edge, y);
		fireMouse(MouseEvent.MOUSE_PRESSED, edge, y);
		fireMouse(MouseEvent.MOUSE_DRAGGED, edge + 20, y);

		final Rect after = ipsc.getPlacedTarget().getBounds();
		assertEquals(before.getMinX(), after.getMinX(), 0.01);
		assertEquals(before.getWidth() + 19, after.getWidth(), 0.01);
		assertEquals(before.getHeight(), after.getHeight(), 0.01);
	}
}
