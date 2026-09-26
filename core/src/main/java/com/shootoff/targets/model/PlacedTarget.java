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
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

/**
 * A target on a canvas: its definition plus where and how it is drawn. Only its
 * {@link TargetSet} changes it.
 *
 * The geometry reproduces JavaFX's Group: the local bounds are the float union of the visible
 * regions' bounds, the scale pivots about their center, and points map to target coordinates
 * through the same double-then-float steps as Node.parentToLocal.
 */
public final class PlacedTarget {
	private final TargetId id;
	private final TargetDefinition definition;
	private volatile Placement placement;
	private final AtomicIntegerArray regionVisible;
	private final AtomicReferenceArray<AlphaMask> imageMasks;

	PlacedTarget(TargetId id, TargetDefinition definition, Placement placement) {
		this.id = id;
		this.definition = definition;
		this.placement = placement;

		final List<Region> regions = definition.regions();
		regionVisible = new AtomicIntegerArray(regions.size());
		for (int i = 0; i < regions.size(); i++) {
			regionVisible.set(i, regions.get(i).isVisibleByDefault() ? 1 : 0);
		}
		imageMasks = new AtomicReferenceArray<>(regions.size());
	}

	public TargetId getId() {
		return id;
	}

	public TargetDefinition getDefinition() {
		return definition;
	}

	public Placement getPlacement() {
		return placement;
	}

	public Point getPosition() {
		final Placement p = placement;
		return new Point(p.x(), p.y());
	}

	public double getScaleX() {
		return placement.scaleX();
	}

	public double getScaleY() {
		return placement.scaleY();
	}

	public boolean isVisible() {
		return placement.visible();
	}

	public boolean isRegionVisible(int regionIndex) {
		return regionVisible.get(regionIndex) != 0;
	}

	/**
	 * @return the current frame of an image region, if the UI has given it
	 */
	public Optional<AlphaMask> getImageMask(int regionIndex) {
		return Optional.ofNullable(imageMasks.get(regionIndex));
	}

	/**
	 * @return the width and height of {@link #getBounds()}
	 */
	public Size getSize() {
		final Rect bounds = getBounds();
		return new Size(bounds.getWidth(), bounds.getHeight());
	}

	/**
	 * @return the union of the visible regions' bounds in target coordinates (JavaFX's
	 *         layoutBounds of the target's group); empty (negative size) if no region is visible
	 */
	public Rect getLocalBounds() {
		return localBox(placement).toRect();
	}

	/**
	 * @return the center of {@link #getLocalBounds()}, which the target is scaled about
	 */
	public Point getPivot() {
		final Rect local = getLocalBounds();
		return new Point(local.getMinX() + local.getWidth() / 2, local.getMinY() + local.getHeight() / 2);
	}

	/**
	 * @return the target's bounds on its canvas (JavaFX's boundsInParent)
	 */
	public Rect getBounds() {
		return boundsAt(placement);
	}

	/**
	 * @return the bounds the target would have on its canvas with another placement
	 */
	public Rect boundsAt(Placement placement) {
		final Transform t = transform(placement);
		return localBox(placement).transform(t.scaleX(), t.scaleY(), t.tx(), t.ty()).toRect();
	}

	/**
	 * @return the canvas point (x, y) in target coordinates, rounded to float like
	 *         Node.parentToLocal
	 */
	public Point parentToLocal(double x, double y) {
		final Transform t = transform(placement);
		final double px = (float) x;
		final double py = (float) y;

		return new Point((float) ((px - t.tx()) / t.scaleX()), (float) ((py - t.ty()) / t.scaleY()));
	}

	/**
	 * @return the target point (x, y) on the canvas, rounded to float like Node.localToParent
	 */
	public Point localToParent(double x, double y) {
		final Transform t = transform(placement);
		return new Point((float) ((float) x * t.scaleX() + t.tx()), (float) ((float) y * t.scaleY() + t.ty()));
	}

	/**
	 * @return the region's bounds on the canvas, as the JavaFX app computed them for a hit: the
	 *         region node's bounds through the target's transform, in double
	 */
	public Rect regionBounds(Region region) {
		final Placement p = placement;
		final FloatBox box = regionBox(region, p);
		if (box.isEmpty()) return new Rect(0, 0, -1, -1);

		final Rect local = box.toRect();
		final Transform t = transform(p);
		final double x1 = local.getMinX() * t.scaleX() + t.tx();
		final double x2 = local.getMaxX() * t.scaleX() + t.tx();
		final double y1 = local.getMinY() * t.scaleY() + t.ty();
		final double y2 = local.getMaxY() * t.scaleY() + t.ty();

		return new Rect(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1));
	}

	/**
	 * @return <tt>true</tt> if the region's shape contains the point given in target coordinates
	 */
	boolean regionContains(Region region, double localX, double localY) {
		float x = (float) localX;
		float y = (float) localY;
		final Placement p = placement;

		if (!region.isResizable() && isScaled(p)) {
			// Undo the unresizable region's own inverse scale about its center
			final Rect box = FloatGeometry.box(region).toRect();
			final double cx = box.getMinX() + box.getWidth() / 2;
			final double cy = box.getMinY() + box.getHeight() / 2;
			x = (float) (cx + (x - cx) * p.scaleX());
			y = (float) (cy + (y - cy) * p.scaleY());
		}

		return FloatGeometry.contains(region, x, y);
	}

	void setPlacement(Placement placement) {
		this.placement = placement;
	}

	void setRegionVisible(int regionIndex, boolean visible) {
		regionVisible.set(regionIndex, visible ? 1 : 0);
	}

	void setImageMask(int regionIndex, AlphaMask mask) {
		imageMasks.set(regionIndex, mask);
	}

	private static boolean isScaled(Placement p) {
		return p.scaleX() != 1 || p.scaleY() != 1;
	}

	// The region's bounds in target coordinates. An unresizable region keeps its size: it is
	// scaled by the inverse of the target's scale about its own center.
	private FloatBox regionBox(Region region, Placement p) {
		final FloatBox box = FloatGeometry.box(region);
		if (region.isResizable() || box.isEmpty() || !isScaled(p)) return box;

		final double sx = 1 / p.scaleX();
		final double sy = 1 / p.scaleY();
		final Rect r = box.toRect();
		final double cx = r.getMinX() + r.getWidth() / 2;
		final double cy = r.getMinY() + r.getHeight() / 2;

		return box.transform(sx, sy, cx - cx * sx, cy - cy * sy);
	}

	private FloatBox localBox(Placement p) {
		FloatBox union = FloatBox.EMPTY;
		final List<Region> regions = definition.regions();
		for (int i = 0; i < regions.size(); i++) {
			if (isRegionVisible(i)) union = union.union(regionBox(regions.get(i), p));
		}
		return union;
	}

	// Node.updateLocalToParentTransform: layout translation only when unscaled, otherwise
	// translate(layout + pivot) * scale * translate(-pivot), which leaves
	// tx = (layoutX + pivotX) - pivotX * scaleX
	private Transform transform(Placement p) {
		if (!isScaled(p)) return new Transform(1, 1, p.x(), p.y());

		final Rect local = localBox(p).toRect();
		final double pivotX = local.getMinX() + local.getWidth() / 2;
		final double pivotY = local.getMinY() + local.getHeight() / 2;

		return new Transform(p.scaleX(), p.scaleY(), (p.x() + pivotX) - pivotX * p.scaleX(),
				(p.y() + pivotY) - pivotY * p.scaleY());
	}

	private record Transform(double scaleX, double scaleY, double tx, double ty) {}
}
