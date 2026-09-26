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
import java.util.Map;
import java.util.Optional;

/**
 * One shape or image of a target, in target coordinates. A target lists its regions in paint
 * order: a later region is drawn over, and hit before, an earlier one.
 */
public sealed interface Region permits EllipseRegion, RectangleRegion, PolygonRegion, ImageRegion {
	String TAG_VISIBLE = "visible";
	String TAG_IGNORE_HIT = "ignoreHit";
	String TAG_RESIZABLE = "isResizable";
	String TAG_OPACITY = "opacity";
	String TAG_COMMAND = "command";

	/**
	 * @return this region's position in its target's region list
	 */
	int index();

	/**
	 * @return the region's tags in file order (unmodifiable)
	 */
	Map<String, String> tags();

	default Optional<String> tag(String name) {
		return Optional.ofNullable(tags().get(name));
	}

	/**
	 * @return <tt>false</tt> only if the region has a <tt>visible</tt> tag that isn't "true"
	 */
	default boolean isVisibleByDefault() {
		return !tags().containsKey(TAG_VISIBLE) || Boolean.parseBoolean(tags().get(TAG_VISIBLE));
	}

	/**
	 * @return <tt>false</tt> only if the region has an <tt>isResizable</tt> tag that isn't "true";
	 *         such a region keeps its size when its target is resized
	 */
	default boolean isResizable() {
		return !tags().containsKey(TAG_RESIZABLE) || Boolean.parseBoolean(tags().get(TAG_RESIZABLE));
	}

	/**
	 * @return <tt>true</tt> if shots pass through this region to the ones below it
	 */
	default boolean ignoresHits() {
		return Boolean.parseBoolean(tags().get(TAG_IGNORE_HIT));
	}

	default List<RegionCommand> commands() {
		return RegionCommand.parse(tags().get(TAG_COMMAND));
	}
}
