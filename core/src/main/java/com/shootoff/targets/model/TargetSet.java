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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.shootoff.geom.Rect;

/**
 * The targets on one canvas, bottom to top. Every change to a placed target goes through its set,
 * which tells its listeners. Updates to a target that isn't in the set (for example one another
 * thread just removed) are ignored.
 */
public final class TargetSet {
	private static final AtomicLong NEXT_ID = new AtomicLong(1);

	private final List<PlacedTarget> targets = new ArrayList<>();
	private final List<TargetSetListener> listeners = new CopyOnWriteArrayList<>();

	/**
	 * Adds a target on top at {@link Placement#ORIGIN}.
	 */
	public PlacedTarget add(TargetDefinition definition) {
		return add(definition, Placement.ORIGIN);
	}

	/**
	 * Adds a target on top.
	 */
	public PlacedTarget add(TargetDefinition definition, Placement placement) {
		final PlacedTarget target = new PlacedTarget(new TargetId(NEXT_ID.getAndIncrement()), definition, placement);

		synchronized (this) {
			targets.add(target);
		}

		for (final TargetSetListener listener : listeners) {
			listener.targetAdded(target);
		}

		return target;
	}

	public void remove(TargetId id) {
		final PlacedTarget removed;

		synchronized (this) {
			final int index = indexOf(id);
			if (index < 0) return;
			removed = targets.remove(index);
		}

		for (final TargetSetListener listener : listeners) {
			listener.targetRemoved(removed);
		}
	}

	/**
	 * @return the targets, bottom (first added) to top
	 */
	public synchronized List<PlacedTarget> getTargets() {
		return List.copyOf(targets);
	}

	public synchronized Optional<PlacedTarget> get(TargetId id) {
		final int index = indexOf(id);
		return index < 0 ? Optional.empty() : Optional.of(targets.get(index));
	}

	/**
	 * @return the target's position in the set (0 is the bottom), or -1 if it isn't in the set
	 */
	public synchronized int indexOf(TargetId id) {
		for (int i = 0; i < targets.size(); i++) {
			if (targets.get(i).getId().equals(id)) return i;
		}
		return -1;
	}

	public synchronized int size() {
		return targets.size();
	}

	public void place(TargetId id, Placement placement) {
		update(id, target -> target.setPlacement(placement));
	}

	public void move(TargetId id, double x, double y) {
		update(id, target -> target.setPlacement(target.getPlacement().withPosition(x, y)));
	}

	public void scale(TargetId id, double scaleX, double scaleY) {
		update(id, target -> target.setPlacement(target.getPlacement().withScale(scaleX, scaleY)));
	}

	/**
	 * Scales the target to a width and height on the canvas, as the JavaFX app's setDimensions
	 * always did: an axis whose size is within 0.001 of the request is left alone, and an axis
	 * without a size can't be scaled.
	 */
	public void resize(TargetId id, double width, double height) {
		update(id, target -> {
			final Placement p = target.getPlacement();
			final Rect bounds = target.getBounds();
			double scaleX = p.scaleX();
			double scaleY = p.scaleY();

			if (bounds.getWidth() > 0 && Math.abs(bounds.getWidth() - width) > .001) {
				scaleX = scaleX * (1.0 + ((width - bounds.getWidth()) / bounds.getWidth()));
			}

			if (bounds.getHeight() > 0 && Math.abs(bounds.getHeight() - height) > .001) {
				scaleY = scaleY * (1.0 + ((height - bounds.getHeight()) / bounds.getHeight()));
			}

			target.setPlacement(p.withScale(scaleX, scaleY));
		});
	}

	public void setVisible(TargetId id, boolean visible) {
		update(id, target -> target.setPlacement(target.getPlacement().withVisible(visible)));
	}

	public void setRegionVisible(TargetId id, int regionIndex, boolean visible) {
		update(id, target -> target.setRegionVisible(regionIndex, visible));
	}

	/**
	 * Gives the hit tester an image region's current frame. Masks change no geometry, so
	 * listeners aren't told.
	 */
	public void setImageMask(TargetId id, int regionIndex, AlphaMask mask) {
		get(id).ifPresent(target -> target.setImageMask(regionIndex, mask));
	}

	public void addListener(TargetSetListener listener) {
		listeners.add(listener);
	}

	public void removeListener(TargetSetListener listener) {
		listeners.remove(listener);
	}

	private void update(TargetId id, Consumer<PlacedTarget> change) {
		final PlacedTarget target;

		synchronized (this) {
			final int index = indexOf(id);
			if (index < 0) return;
			target = targets.get(index);
			change.accept(target);
		}

		for (final TargetSetListener listener : listeners) {
			listener.targetChanged(target);
		}
	}
}
