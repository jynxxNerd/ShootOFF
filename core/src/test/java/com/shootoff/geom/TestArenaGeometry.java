package com.shootoff.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * ArenaGeometry against the math CanvasManager used before it moved to core. Each legacy method below
 * is the old body with the JavaFX types and the logging taken out.
 */
class TestArenaGeometry {
	private static final List<Size> FEEDS = List.of(new Size(640, 480), new Size(1280, 720), new Size(1920, 1080),
			new Size(320, 240), new Size(800, 600));
	private static final List<Size> DISPLAYS = List.of(new Size(640, 480), new Size(800, 600), new Size(1280, 960),
			new Size(320, 240), new Size(1366, 768));
	private static final List<Rect> PROJECTIONS = List.of(new Rect(100, 100, 540, 260), new Rect(113, 32, 422, 316),
			new Rect(0, 0, 640, 480), new Rect(12.5, 7.25, 301.75, 199.5), new Rect(250, 180, 90, 60));
	private static final List<Size> ARENAS = List.of(new Size(640, 360), new Size(1280, 720), new Size(1920, 1080),
			new Size(1024, 768), new Size(1366.5, 767.25));
	private static final double[][] POINTS = { { 0, 0 }, { 100, 100 }, { 381, 235 }, { 271.37, 125.61 },
			{ 639.99, 479.99 }, { 640, 360 }, { -10, -10 }, { 1919, 1079 } };

	// CanvasManager.translateCameraToCanvas
	private static Rect legacyCameraToCanvas(Rect bounds, int feedWidth, int feedHeight, int displayWidth,
			int displayHeight) {
		if (displayWidth == feedWidth && displayHeight == feedHeight) return bounds;

		final double scaleX = (double) displayWidth / (double) feedWidth;
		final double scaleY = (double) displayHeight / (double) feedHeight;

		final double minX = (bounds.getMinX() * scaleX);
		final double minY = (bounds.getMinY() * scaleY);
		final double width = (bounds.getWidth() * scaleX);
		final double height = (bounds.getHeight() * scaleY);

		return new Rect(minX, minY, width, height);
	}

	// CanvasManager.translateCanvasToCamera
	private static Rect legacyCanvasToCamera(Rect bounds, int feedWidth, int feedHeight, int displayWidth,
			int displayHeight) {
		if (displayWidth == feedWidth && displayHeight == feedHeight) return bounds;

		final double scaleX = (double) feedWidth / (double) displayWidth;
		final double scaleY = (double) feedHeight / (double) displayHeight;

		final double minX = (bounds.getMinX() * scaleX);
		final double minY = (bounds.getMinY() * scaleY);
		final double width = (bounds.getWidth() * scaleX);
		final double height = (bounds.getHeight() * scaleY);

		return new Rect(minX, minY, width, height);
	}

	// CanvasManager.scaleShotToArenaBounds, with the arena pane's width and height
	private static Point legacyCanvasToArena(double shotX, double shotY, Rect projectionBounds, double arenaWidth,
			double arenaHeight) {
		final double x_scale = arenaWidth / projectionBounds.getWidth();
		final double y_scale = arenaHeight / projectionBounds.getHeight();

		return new Point((shotX - projectionBounds.getMinX()) * x_scale, (shotY - projectionBounds.getMinY()) * y_scale);
	}

	// CanvasManager.translateCanvasToCameraPoint, with the camera's projection bounds
	private static Point legacyArenaToCamera(double x, double y, Rect b, double arenaWidth, double arenaHeight) {
		final double x_scale = b.getWidth() / arenaWidth;
		final double y_scale = b.getHeight() / arenaHeight;

		return new Point(b.getMinX() + (x * x_scale), b.getMinY() + (y * y_scale));
	}

	@Test
	void cameraAndCanvasBoundsMatchTheLegacyMathForEveryFeedAndDisplay() {
		int compared = 0;
		for (final Size feed : FEEDS) {
			for (final Size display : DISPLAYS) {
				for (final Rect bounds : PROJECTIONS) {
					final int fw = (int) feed.getWidth(), fh = (int) feed.getHeight();
					final int dw = (int) display.getWidth(), dh = (int) display.getHeight();

					assertEquals(legacyCameraToCanvas(bounds, fw, fh, dw, dh),
							ArenaGeometry.cameraToCanvas(bounds, feed, display), feed + " to " + display + ": " + bounds);
					assertEquals(legacyCanvasToCamera(bounds, fw, fh, dw, dh),
							ArenaGeometry.canvasToCamera(bounds, feed, display), display + " to " + feed + ": " + bounds);
					compared++;
				}
			}
		}
		assertEquals(FEEDS.size() * DISPLAYS.size() * PROJECTIONS.size(), compared);
	}

	@Test
	void aFeedTheSizeOfTheDisplayLeavesBoundsAsTheyAre() {
		final Rect bounds = new Rect(113, 32, 422, 316);

		assertSame(bounds, ArenaGeometry.cameraToCanvas(bounds, new Size(640, 480), new Size(640, 480)));
		assertSame(bounds, ArenaGeometry.canvasToCamera(bounds, new Size(640, 480), new Size(640, 480)));
	}

	@Test
	void canvasPointsMatchTheLegacyArenaMathForEveryPlacementAndArenaSize() {
		for (final Rect projection : PROJECTIONS) {
			for (final Size arena : ARENAS) {
				for (final double[] point : POINTS) {
					assertEquals(legacyCanvasToArena(point[0], point[1], projection, arena.getWidth(), arena.getHeight()),
							ArenaGeometry.canvasToArena(point[0], point[1], projection, arena),
							"(" + point[0] + ", " + point[1] + ") in " + projection + " on " + arena);
				}
			}
		}
	}

	@Test
	void arenaPointsMatchTheLegacyCameraMathForEveryPlacementAndArenaSize() {
		for (final Rect projection : PROJECTIONS) {
			for (final Size arena : ARENAS) {
				for (final double[] point : POINTS) {
					assertEquals(legacyArenaToCamera(point[0], point[1], projection, arena.getWidth(), arena.getHeight()),
							ArenaGeometry.arenaToCamera(point[0], point[1], projection, arena),
							"(" + point[0] + ", " + point[1] + ") on " + arena + " into " + projection);
				}
			}
		}
	}

	@Test
	void projectionCornersLandOnArenaCorners() {
		final Rect projection = new Rect(100, 100, 540, 260);
		final Size arena = new Size(640, 360);

		assertEquals(new Point(0, 0), ArenaGeometry.canvasToArena(100, 100, projection, arena));
		assertEquals(new Point(640, 360), ArenaGeometry.canvasToArena(640, 360, projection, arena));
		assertEquals(new Point(100, 100), ArenaGeometry.arenaToCamera(0, 0, projection, arena));
		assertEquals(new Point(640, 360), ArenaGeometry.arenaToCamera(640, 360, projection, arena));
	}
}
