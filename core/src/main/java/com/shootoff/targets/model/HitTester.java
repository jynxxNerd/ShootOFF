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
import java.util.Optional;

import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;

/**
 * Finds the region a shot hits, exactly as the JavaFX app's hit test did.
 */
public final class HitTester {
	private HitTester() {}

	/**
	 * @return the topmost region under (x, y) of the topmost visible target that has one. A
	 *         hidden target is never hit; a region hidden by its <tt>visible</tt> tag still is.
	 */
	public static Optional<Hit> hit(TargetSet targets, double x, double y) {
		final List<PlacedTarget> all = targets.getTargets();

		for (int i = all.size() - 1; i >= 0; i--) {
			final PlacedTarget target = all.get(i);
			if (!target.isVisible()) continue;

			final Optional<Hit> hit = hit(target, x, y);
			if (hit.isPresent()) return hit;
		}

		return Optional.empty();
	}

	/**
	 * @return the topmost region of one target under (x, y), whether or not the target is
	 *         visible. Shapes are exact; an image is hit where its current frame isn't fully
	 *         transparent, or anywhere in its bounds if the UI hasn't given its frame. Regions
	 *         tagged <tt>ignoreHit</tt> let shots through.
	 */
	public static Optional<Hit> hit(PlacedTarget target, double x, double y) {
		if (!target.getBounds().contains(x, y)) return Optional.empty();

		final List<Region> regions = target.getDefinition().regions();
		for (int i = regions.size() - 1; i >= 0; i--) {
			final Region region = regions.get(i);
			final Rect regionBounds = target.regionBounds(region);

			if (!regionBounds.contains(x, y)) continue;
			if (region.ignoresHits()) continue;

			if (region instanceof ImageRegion) {
				final Optional<AlphaMask> mask = target.getImageMask(i);
				if (mask.isPresent() && !isOpaqueAt(mask.get(), regionBounds, x, y)) continue;
			} else {
				final Point local = target.parentToLocal(x, y);
				if (!target.regionContains(region, local.getX(), local.getY())) continue;
			}

			return Optional.of(new Hit(target.getId(), region, target.parentToLocal(x, y)));
		}

		return Optional.empty();
	}

	// The pixel under the shot, in the image scaled to the region's size on the canvas when that
	// differs from the image's own size (TargetView.isHit before the model)
	private static boolean isOpaqueAt(AlphaMask mask, Rect regionBounds, double x, double y) {
		final int adjustedX = (int) (x - regionBounds.getMinX());
		final int adjustedY = (int) (y - regionBounds.getMinY());

		AlphaMask frame = mask;
		if (Math.abs(mask.getWidth() - regionBounds.getWidth()) > .0000001
				|| Math.abs(mask.getHeight() - regionBounds.getHeight()) > .0000001) {
			final int width = (int) regionBounds.getWidth();
			final int height = (int) regionBounds.getHeight();
			if (width <= 0 || height <= 0) return false;

			frame = mask.scaledTo(width, height);
		}

		return adjustedX < frame.getWidth() && adjustedY < frame.getHeight() && frame.isOpaque(adjustedX, adjustedY);
	}
}
