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

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A target as a .target file describes it: its tags (the <tt>&lt;target&gt;</tt> element's
 * attributes, in file order) and its regions in paint order.
 *
 * @param file
 *            the file the target was loaded from, empty for targets read from a stream or made in
 *            code (e.g. the manual calibration rectangle)
 */
public record TargetDefinition(Optional<File> file, Map<String, String> tags, List<Region> regions) {
	public static final String TAG_FILL_CANVAS = "fillCanvas";
	public static final String TAG_DEFAULT_PERCEIVED_WIDTH = "defaultPerceivedWidth";
	public static final String TAG_DEFAULT_PERCEIVED_HEIGHT = "defaultPerceivedHeight";
	public static final String TAG_DEFAULT_DISTANCE = "defaultDistance";

	public TargetDefinition {
		Objects.requireNonNull(file, "file");
		tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
		regions = List.copyOf(regions);

		for (int i = 0; i < regions.size(); i++) {
			if (regions.get(i).index() != i) {
				throw new IllegalArgumentException("Region " + i + " has index " + regions.get(i).index());
			}
		}
	}

	public TargetDefinition withFile(File file) {
		return new TargetDefinition(Optional.ofNullable(file), tags, regions);
	}

	/**
	 * @return <tt>true</tt> if the target should be stretched over its whole canvas (the POI
	 *         adjustment target)
	 */
	public boolean fillsCanvas() {
		return Boolean.parseBoolean(tags.get(TAG_FILL_CANVAS));
	}

	/**
	 * @return the default perceived width, height and distance, if all three tags are present and
	 *         are integers
	 */
	public Optional<DefaultPerception> defaultPerception() {
		try {
			return Optional.of(new DefaultPerception(Integer.parseInt(tags.get(TAG_DEFAULT_PERCEIVED_WIDTH)),
					Integer.parseInt(tags.get(TAG_DEFAULT_PERCEIVED_HEIGHT)),
					Integer.parseInt(tags.get(TAG_DEFAULT_DISTANCE))));
		} catch (final NumberFormatException e) {
			return Optional.empty();
		}
	}
}
