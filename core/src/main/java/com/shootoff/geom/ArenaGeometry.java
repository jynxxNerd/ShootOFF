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

package com.shootoff.geom;

/**
 * How the camera feed, the canvas that shows it and the projector arena relate, once the arena is
 * calibrated.
 * <ul>
 * <li>The <b>camera feed</b> is in the camera's pixels (for example 640 x 480).</li>
 * <li>The <b>canvas</b> shows the feed at the display resolution (<tt>shootoff.properties</tt>),
 * so detected shots and calibrated bounds are scaled from the feed to it.</li>
 * <li>The <b>arena</b> is the projector window. Its calibrated projection is the rectangle the
 * camera sees it in, on the canvas or on the feed.</li>
 * </ul>
 * The arithmetic is exactly the JavaFX app's (formerly in <tt>CanvasManager</tt>), operation for
 * operation, so results match to the last bit.
 */
public final class ArenaGeometry {
	private ArenaGeometry() {}

	/**
	 * @return <tt>bounds</tt> on the camera feed, scaled to the canvas; <tt>bounds</tt> itself when
	 *         the feed and the display are the same size
	 */
	public static Rect cameraToCanvas(Rect bounds, Size feed, Size display) {
		if (display.getWidth() == feed.getWidth() && display.getHeight() == feed.getHeight()) return bounds;

		final double scaleX = display.getWidth() / feed.getWidth();
		final double scaleY = display.getHeight() / feed.getHeight();

		return new Rect(bounds.getMinX() * scaleX, bounds.getMinY() * scaleY, bounds.getWidth() * scaleX,
				bounds.getHeight() * scaleY);
	}

	/**
	 * @return <tt>bounds</tt> on the canvas, scaled to the camera feed; <tt>bounds</tt> itself when
	 *         the feed and the display are the same size
	 */
	public static Rect canvasToCamera(Rect bounds, Size feed, Size display) {
		if (display.getWidth() == feed.getWidth() && display.getHeight() == feed.getHeight()) return bounds;

		final double scaleX = feed.getWidth() / display.getWidth();
		final double scaleY = feed.getHeight() / display.getHeight();

		return new Rect(bounds.getMinX() * scaleX, bounds.getMinY() * scaleY, bounds.getWidth() * scaleX,
				bounds.getHeight() * scaleY);
	}

	/**
	 * @param projection
	 *            the calibrated projection on the canvas
	 * @param arena
	 *            the arena's current size
	 * @return a canvas point (normally one inside <tt>projection</tt>) on the arena
	 */
	public static Point canvasToArena(double x, double y, Rect projection, Size arena) {
		final double scaleX = arena.getWidth() / projection.getWidth();
		final double scaleY = arena.getHeight() / projection.getHeight();

		return new Point((x - projection.getMinX()) * scaleX, (y - projection.getMinY()) * scaleY);
	}

	/**
	 * @param projection
	 *            the calibrated projection on the camera feed
	 * @param arena
	 *            the arena's current size
	 * @return an arena point on the camera feed
	 */
	public static Point arenaToCamera(double x, double y, Rect projection, Size arena) {
		final double scaleX = projection.getWidth() / arena.getWidth();
		final double scaleY = projection.getHeight() / arena.getHeight();

		return new Point(projection.getMinX() + (x * scaleX), projection.getMinY() + (y * scaleY));
	}
}
