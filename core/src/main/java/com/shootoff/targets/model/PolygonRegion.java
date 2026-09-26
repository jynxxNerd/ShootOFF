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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.shootoff.geom.Point;

/**
 * A <tt>&lt;polygon&gt;</tt> region: its <tt>&lt;point&gt;</tt>s in order, closed back to the
 * first. <tt>fill</tt> is the color as written in the file.
 */
public record PolygonRegion(int index, List<Point> points, String fill, Map<String, String> tags) implements Region {
	public PolygonRegion {
		Objects.requireNonNull(fill, "fill");
		points = List.copyOf(points);
		if (points.isEmpty()) throw new IllegalArgumentException("A polygon needs at least one point");
		tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
	}
}
