/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.targets.model;

import java.util.List;

import com.shootoff.geom.Point;

/**
 * Region bounds and containment computed with JavaFX's float rounding and edge rules, so that the
 * hit tester agrees with the JavaFX app's hit test to the pixel (TestHitParity). Each method
 * names the OpenJFX code it follows.
 */
final class FloatGeometry {
	private FloatGeometry() {}

	/**
	 * @return the region's bounds in target coordinates, as the region node's boundsInParent
	 *         (Ellipse/Rectangle: Shape.computeBounds; Polygon: Path2D bounds of its float points;
	 *         ImageView at layoutX/Y: Translate2D.transform of (0, 0, width, height))
	 */
	static FloatBox box(Region region) {
		return switch (region) {
		case EllipseRegion e -> {
			final double x = e.centerX() - e.radiusX();
			final double y = e.centerY() - e.radiusY();
			final double width = 2.0 * e.radiusX();
			final double height = 2.0 * e.radiusY();

			yield width < 0 || height < 0 ? FloatBox.EMPTY
					: new FloatBox((float) x, (float) y, (float) (width + x), (float) (height + y));
		}
		case RectangleRegion r -> r.width() < 0 || r.height() < 0 ? FloatBox.EMPTY
				: new FloatBox((float) r.x(), (float) r.y(), (float) (r.width() + r.x()),
						(float) (r.height() + r.y()));
		case PolygonRegion p -> polygonBox(p.points());
		case ImageRegion i -> new FloatBox((float) i.x(), (float) i.y(), (float) (i.imageWidth() + i.x()),
				(float) (i.imageHeight() + i.y()));
		};
	}

	// Polygon.doComputeGeomBounds: fewer than two points (one coordinate pair) has empty bounds
	private static FloatBox polygonBox(List<Point> points) {
		if (points.size() < 2) return FloatBox.EMPTY;

		float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
		for (final Point point : points) {
			final float x = (float) point.getX();
			final float y = (float) point.getY();
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
		}

		return new FloatBox(minX, minY, maxX, maxY);
	}

	/**
	 * @return <tt>true</tt> if the shape contains the point (target coordinates, already rounded
	 *         to float). Images always contain points in their bounds; their alpha is tested
	 *         separately.
	 */
	static boolean contains(Region region, float x, float y) {
		return switch (region) {
		case EllipseRegion e -> ellipseContains(e, x, y);
		case RectangleRegion r -> rectangleContains(r, x, y);
		case PolygonRegion p -> polygonContains(p.points(), x, y);
		case ImageRegion i -> true;
		};
	}

	// Ellipse.doConfigShape + com.sun.javafx.geom.Ellipse2D.contains
	private static boolean ellipseContains(EllipseRegion e, float px, float py) {
		final float x = (float) (e.centerX() - e.radiusX());
		final float y = (float) (e.centerY() - e.radiusY());
		final float w = (float) (e.radiusX() * 2.0);
		final float h = (float) (e.radiusY() * 2.0);

		if (w <= 0) return false;
		final float normx = (px - x) / w - 0.5f;
		if (h <= 0) return false;
		final float normy = (py - y) / h - 0.5f;

		return (normx * normx + normy * normy) < 0.25f;
	}

	// Rectangle.doConfigShape + RoundRectangle2D.contains with no arcs: left/top edges in,
	// right/bottom edges out
	private static boolean rectangleContains(RectangleRegion r, float px, float py) {
		final float x0 = (float) r.x();
		final float y0 = (float) r.y();
		final float w = (float) r.width();
		final float h = (float) r.height();

		if (w <= 0f || h <= 0f) return false;

		return px >= x0 && py >= y0 && px < x0 + w && py < y0 + h;
	}

	// Polygon.doConfigShape (moveTo, lineTo..., closePath) + Path2D.contains/pointCrossings with
	// the non-zero winding rule
	private static boolean polygonContains(List<Point> points, float px, float py) {
		if (!(px * 0f + py * 0f == 0f)) return false; // NaN or infinite

		final float movx = (float) points.get(0).getX();
		final float movy = (float) points.get(0).getY();
		float curx = movx;
		float cury = movy;
		int crossings = 0;

		for (int i = 1; i < points.size(); i++) {
			final float endx = (float) points.get(i).getX();
			final float endy = (float) points.get(i).getY();
			crossings += pointCrossingsForLine(px, py, curx, cury, endx, endy);
			curx = endx;
			cury = endy;
		}

		if (cury != movy) crossings += pointCrossingsForLine(px, py, curx, cury, movx, movy);

		return crossings != 0;
	}

	// com.sun.javafx.geom.Shape.pointCrossingsForLine
	private static int pointCrossingsForLine(float px, float py, float x0, float y0, float x1, float y1) {
		if (py < y0 && py < y1) return 0;
		if (py >= y0 && py >= y1) return 0;
		if (px >= x0 && px >= x1) return 0;
		if (px < x0 && px < x1) return (y0 < y1) ? 1 : -1;

		final float xintercept = x0 + (py - y0) * (x1 - x0) / (y1 - y0);
		if (px >= xintercept) return 0;

		return (y0 < y1) ? 1 : -1;
	}
}
