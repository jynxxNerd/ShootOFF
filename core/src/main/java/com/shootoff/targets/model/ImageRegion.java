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
import java.util.Map;
import java.util.Objects;

/**
 * An <tt>&lt;image&gt;</tt> region with its top-left corner at (x, y). <tt>imagePath</tt> is as
 * written in the file (see {@link ResourceResolver}). The size is the one the JavaFX app shows: a
 * GIF's logical screen size, otherwise the first frame's size. The pixels, and an animation's
 * current frame, belong to the UI; it hands the current frame to the hit tester as an alpha mask.
 */
public record ImageRegion(int index, double x, double y, String imagePath, int imageWidth, int imageHeight,
		Map<String, String> tags) implements Region {
	public ImageRegion {
		Objects.requireNonNull(imagePath, "imagePath");
		tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
	}
}
